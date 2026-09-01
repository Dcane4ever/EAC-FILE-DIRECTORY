package ph.edu.eac.filedirectory.file;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import ph.edu.eac.filedirectory.taxonomy.Department;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Year;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Handles writing uploaded files to local disk under eac.storage.root, in a
 * <department>/<year>/<uuid>_<original-name> layout, and reading them back
 * for download. The database only ever stores the resulting relative path.
 */
@Service
public class FileStorageService {

    private final Path storageRoot;

    public FileStorageService(@Value("${eac.storage.root}") String storageRoot) {
        this.storageRoot = Paths.get(storageRoot).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.storageRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create storage root: " + this.storageRoot, e);
        }
    }

    public record StoredFile(String relativePath, long size, String checksumSha256) {
    }

    /**
     * SHA-256 of the upload's content without writing anything to disk -
     * see UploadController's duplicate-detection check, which needs to know
     * the checksum before deciding whether to actually store the file.
     * MultipartFile.getInputStream() is safe to call more than once (Spring's
     * multipart implementations back it with a temp file or byte array, not
     * a single-use stream), so this doesn't interfere with store() reading
     * the same upload afterward.
     */
    public String computeChecksum(MultipartFile upload) {
        try (InputStream in = upload.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var digestIn = new java.security.DigestInputStream(in, digest)) {
                digestIn.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read uploaded file", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public StoredFile store(MultipartFile upload, Department department) {
        String originalName = StringUtils.cleanPath(upload.getOriginalFilename() == null ? "file" : upload.getOriginalFilename());
        String safeName = originalName.replaceAll("[^a-zA-Z0-9._-]", "_");
        String year = String.valueOf(Year.now().getValue());
        String storedName = UUID.randomUUID() + "_" + safeName;
        String relativePath = department.getCode() + "/" + year + "/" + storedName;

        Path target = storageRoot.resolve(relativePath).normalize();
        if (!target.startsWith(storageRoot)) {
            throw new IllegalArgumentException("Invalid file path");
        }

        try {
            Files.createDirectories(target.getParent());
            String checksum;
            try (InputStream in = upload.getInputStream()) {
                checksum = copyAndHash(in, target);
            }
            long size = Files.size(target);
            return new StoredFile(relativePath, size, checksum);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store uploaded file", e);
        }
    }

    public Path resolve(String relativePath) {
        Path target = storageRoot.resolve(relativePath).normalize();
        if (!target.startsWith(storageRoot)) {
            throw new IllegalArgumentException("Invalid file path");
        }
        return target;
    }

    /**
     * The configured root is exposed for the read-only maintenance scanner.
     * Callers must not use it to mutate storage; file writes remain owned by
     * store(), and Phase 1 maintenance only inspects this directory.
     */
    public Path storageRoot() {
        return storageRoot;
    }

    /** Returns false for a missing or malformed relative path without ever leaving storageRoot. */
    public boolean storedFileExists(String relativePath) {
        try {
            return relativePath != null && Files.isRegularFile(resolve(relativePath));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private String copyAndHash(InputStream in, Path target) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var out = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                 var digestOut = new java.security.DigestOutputStream(out, digest)) {
                in.transferTo(digestOut);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
