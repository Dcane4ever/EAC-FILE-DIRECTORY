package ph.edu.eac.filedirectory.onlyoffice;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import ph.edu.eac.filedirectory.file.FileEntity;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything ONLYOFFICE-specific lives here, not scattered across a
 * controller - see OnlyOfficeController for the two endpoints this backs.
 *
 * Two completely independent JWTs are in play, easy to conflate, so naming
 * them explicitly:
 *
 *   1. The CONFIG JWT (signConfig): ONLYOFFICE's own contract - when its
 *      Document Server has JWT_ENABLED, every editor/viewer config it's
 *      given must carry a token signed over that same config with the
 *      shared secret, or it refuses to open the document. This is between
 *      this app and ONLYOFFICE, using onlyoffice.jwt.secret.
 *
 *   2. The DOCUMENT ACCESS token (generateSecureDocumentUrl /
 *      verifyDocumentAccessToken): our own invention, nothing to do with
 *      ONLYOFFICE's protocol. ONLYOFFICE Document Server fetches the actual
 *      file bytes as a plain server-to-server HTTP GET - it does NOT carry
 *      the user's browser session cookie, so requireViewable's normal
 *      Spring Security check can't run for that request the way it does
 *      for every other file-serving endpoint in this app. Instead,
 *      OnlyOfficeController's config endpoint checks the signed-in user's
 *      access first (reusing FileDetailController's own rule), and only
 *      THEN mints a short-lived, single-file, cryptographically signed URL
 *      that stands in for "yes, this specific request was pre-authorized" -
 *      deliberately stateless (a signed JWT, not a database row like
 *      AccessGrantToken) since it only needs to survive the few seconds
 *      between issuing the config and ONLYOFFICE fetching the file.
 */
@Service
public class OnlyOfficeService {

    /** Claim name for the file id inside a document-access token. */
    private static final String CLAIM_FILE_ID = "fid";
    /** Purpose marker so a token minted for a different feature (there are none today, but future-proofing) can never be replayed here. */
    private static final String CLAIM_PURPOSE = "purpose";
    private static final String PURPOSE_ONLYOFFICE_CONTENT = "onlyoffice-content";

    private final OnlyOfficeProperties properties;

    public OnlyOfficeService(OnlyOfficeProperties properties) {
        this.properties = properties;
    }

    public boolean isSupported(FileEntity file) {
        return file.isOfficePreviewable() && OnlyOfficeDocumentType.forExtension(file.extension()) != null;
    }

    public OnlyOfficeDocumentType determineDocumentType(FileEntity file) {
        return OnlyOfficeDocumentType.forExtension(file.extension());
    }

    /**
     * A stable-but-version-sensitive key for ONLYOFFICE's config.document.key
     * - it caches converted/rendered output keyed on this value, so it MUST
     * change if the underlying bytes ever could. Derived from the file's id,
     * upload timestamp, size, and SHA-256 checksum (the strongest signal
     * FileStorageService already computes at upload time - see
     * FileEntity.getChecksum) rather than the raw checksum itself, since
     * ONLYOFFICE keys have a length limit (128 chars) and there's no reason
     * to hand it a raw content hash when a shorter derived one does the job.
     * Not a security token - this is a cache key, not something that grants
     * access on its own (see generateSecureDocumentUrl for what actually does).
     */
    public String generateDocumentKey(FileEntity file) {
        String raw = file.getId() + ":" + file.getCreatedAt() + ":" + file.getFileSize() + ":" + file.getChecksum();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            // First 20 bytes (40 hex chars) is plenty of collision resistance
            // for a cache key and comfortably under ONLYOFFICE's 128-char limit.
            return HexFormat.of().formatHex(hash, 0, 20);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Mints the short-lived, single-file document-access token and builds
     * the full URL ONLYOFFICE's Document Server will fetch the file from -
     * built off onlyoffice.app-base-url (what ONLYOFFICE itself can reach),
     * never the browser-facing request URL, since those two can genuinely
     * differ (see OnlyOfficeProperties' class comment).
     */
    public String generateSecureDocumentUrl(FileEntity file) {
        String token = generateDocumentAccessToken(file);
        return UriComponentsBuilder.fromUriString(properties.appBaseUrl())
                .path("/files/{id}/onlyoffice-content")
                .queryParam("token", token)
                .buildAndExpand(file.getId())
                .toUriString();
    }

    private String generateDocumentAccessToken(FileEntity file) {
        Instant now = Instant.now();
        return Jwts.builder()
                .claim(CLAIM_FILE_ID, file.getId())
                .claim(CLAIM_PURPOSE, PURPOSE_ONLYOFFICE_CONTENT)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(properties.documentTokenTtlMinutes()))))
                .signWith(signingKey())
                .compact();
    }

    public record DocumentAccessTokenResult(boolean valid, Long fileId, String errorReason) {
        static DocumentAccessTokenResult ok(Long fileId) {
            return new DocumentAccessTokenResult(true, fileId, null);
        }

        static DocumentAccessTokenResult invalid(String reason) {
            return new DocumentAccessTokenResult(false, null, reason);
        }
    }

    /**
     * Validates a document-access token from generateSecureDocumentUrl and,
     * if valid, confirms it was actually issued for THIS file id (not just
     * any validly-signed token) - see OnlyOfficeController's content
     * endpoint, which treats any failure here as 404, not 403, so a
     * mismatched/expired/forged token doesn't reveal anything about what
     * does or doesn't exist.
     */
    public DocumentAccessTokenResult verifyDocumentAccessToken(String token, Long expectedFileId) {
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(signingKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            return DocumentAccessTokenResult.invalid("Invalid or expired document token");
        }

        if (!PURPOSE_ONLYOFFICE_CONTENT.equals(claims.get(CLAIM_PURPOSE, String.class))) {
            return DocumentAccessTokenResult.invalid("Wrong token purpose");
        }
        Number fileIdClaim = claims.get(CLAIM_FILE_ID, Number.class);
        if (fileIdClaim == null || !expectedFileId.equals(fileIdClaim.longValue())) {
            return DocumentAccessTokenResult.invalid("Token does not match the requested file");
        }
        return DocumentAccessTokenResult.ok(fileIdClaim.longValue());
    }

    /**
     * Builds the config handed to DocsAPI.DocEditor - view-only, no editing
     * permissions, document URL points at our own signed content endpoint
     * (never a raw filesystem path). If onlyoffice.jwt.enabled, the config
     * also carries a "token" field: ONLYOFFICE's own signature over this
     * same payload (see class comment's "CONFIG JWT"), required before the
     * Document Server will accept it.
     */
    public Map<String, Object> createPreviewConfig(FileEntity file) {
        OnlyOfficeDocumentType type = determineDocumentType(file);
        if (type == null) {
            throw new IllegalArgumentException("Unsupported file type for ONLYOFFICE preview: " + file.extension());
        }

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("fileType", file.extension());
        document.put("key", generateDocumentKey(file));
        document.put("title", file.getOriginalFilename());
        document.put("url", generateSecureDocumentUrl(file));
        Map<String, Object> permissions = new LinkedHashMap<>();
        // Read-only preview, not the collaborative editor - see class
        // comment and roadmap: this is Google-Drive-style "look, don't
        // touch", never a way to modify the stored original.
        permissions.put("edit", false);
        permissions.put("download", false);
        permissions.put("print", true);
        permissions.put("copy", true);
        document.put("permissions", permissions);

        Map<String, Object> editorConfig = new LinkedHashMap<>();
        editorConfig.put("mode", "view");
        editorConfig.put("lang", "en");
        Map<String, Object> customization = new LinkedHashMap<>();
        customization.put("compactToolbar", true);
        customization.put("hideRightMenu", true);
        editorConfig.put("customization", customization);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("document", document);
        config.put("documentType", type.apiValue());
        config.put("editorConfig", editorConfig);
        config.put("type", "desktop");

        if (properties.jwt().enabled()) {
            config.put("token", signConfig(config));
        }

        return config;
    }

    /** Signs the exact config payload per ONLYOFFICE's own JWT contract - see class comment's "CONFIG JWT". */
    private String signConfig(Map<String, Object> config) {
        return Jwts.builder()
                .claims(config)
                .signWith(configSigningKey())
                .compact();
    }

    private SecretKey signingKey() {
        // Our own document-access tokens are signed with the same shared
        // secret as ONLYOFFICE's config JWT - one secret, one trust
        // boundary between this app and the outside world for this
        // feature, rather than juggling two.
        return configSigningKey();
    }

    private SecretKey configSigningKey() {
        String secret = properties.jwt().secret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "onlyoffice.jwt.secret is not configured - set ONLYOFFICE_JWT_SECRET (see .env.example) before using ONLYOFFICE preview.");
        }
        // The RAW UTF-8 bytes of the configured secret are the HMAC key -
        // deliberately NOT hashed first. ONLYOFFICE's own Node
        // "jsonwebtoken" library (what its Document Server actually uses
        // to verify) takes the configured JWT_SECRET string's raw bytes
        // directly as the HS256 key, with no hashing step - signing with
        // SHA-256(secret) here instead produced a genuinely different key
        // than what ONLYOFFICE verified with, which is exactly what caused
        // "checkJwt error: invalid signature" server-side (see docservice
        // logs) even though both sides had the identical configured
        // secret string. Requires the configured secret to already be
        // reasonably long (32+ bytes) for HS256's minimum key-strength
        // requirement - see .env.example's `openssl rand -base64 48`.
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
