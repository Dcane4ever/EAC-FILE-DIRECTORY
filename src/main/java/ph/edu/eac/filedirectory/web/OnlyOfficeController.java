package ph.edu.eac.filedirectory.web;

import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ph.edu.eac.filedirectory.file.FileEntity;
import ph.edu.eac.filedirectory.file.FileRepository;
import ph.edu.eac.filedirectory.file.FileStatus;
import ph.edu.eac.filedirectory.file.FileStorageService;
import ph.edu.eac.filedirectory.onlyoffice.OnlyOfficeProperties;
import ph.edu.eac.filedirectory.onlyoffice.OnlyOfficeService;
import ph.edu.eac.filedirectory.security.EacUserDetails;
import ph.edu.eac.filedirectory.user.AppUser;
import ph.edu.eac.filedirectory.user.Role;

import java.net.MalformedURLException;
import java.nio.file.Path;
import java.util.Map;

/**
 * The ONLYOFFICE preview HTTP surface - two endpoints, two different
 * callers, two different auth models:
 *
 *   - GET /files/{id}/onlyoffice-config: called by the SIGNED-IN USER'S
 *     BROWSER (an authenticated fetch(), same session cookie as every other
 *     page). Gated by the exact same visibility rule as everywhere else a
 *     file's content is reachable (requireViewable, mirroring
 *     FileDetailController - not reusing its private method since this is
 *     a separate controller, same pattern AccessRequestController already
 *     follows for its own authorization). Only after that check passes
 *     does it mint a document-access token and return the full ONLYOFFICE
 *     config, already signed - see OnlyOfficeService.
 *
 *   - GET /files/{id}/onlyoffice-content: called by the DOCUMENT SERVER
 *     itself, a plain server-to-server HTTP GET with no user session at
 *     all (see OnlyOfficeService's class comment for why). Authorization
 *     here is entirely the signed, short-lived, single-file token minted
 *     by the config endpoint above - not Spring Security's normal
 *     session-based gate, since there is no session to check. An invalid,
 *     expired, or mismatched-file token is treated as 404 (not 403), so a
 *     forged/leaked token doesn't reveal file existence either.
 */
@RestController
public class OnlyOfficeController {

    private final FileRepository fileRepository;
    private final FileStorageService storageService;
    private final OnlyOfficeService onlyOfficeService;
    private final OnlyOfficeProperties onlyOfficeProperties;

    public OnlyOfficeController(FileRepository fileRepository, FileStorageService storageService,
                                 OnlyOfficeService onlyOfficeService, OnlyOfficeProperties onlyOfficeProperties) {
        this.fileRepository = fileRepository;
        this.storageService = storageService;
        this.onlyOfficeService = onlyOfficeService;
        this.onlyOfficeProperties = onlyOfficeProperties;
    }

    @GetMapping("/files/{id}/onlyoffice-config")
    @ResponseBody
    public Map<String, Object> config(@PathVariable Long id, @AuthenticationPrincipal EacUserDetails principal) {
        FileEntity file = fileRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        requireViewable(file, principal);

        if (!onlyOfficeService.isSupported(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No ONLYOFFICE preview available for this file type");
        }

        return Map.of(
                "serverUrl", onlyOfficeProperties.url(),
                "config", onlyOfficeService.createPreviewConfig(file)
        );
    }

    @GetMapping("/files/{id}/onlyoffice-content")
    public ResponseEntity<Resource> content(@PathVariable Long id, @RequestParam String token) {
        FileEntity file = fileRepository.findById(id).orElse(null);
        if (file == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        OnlyOfficeService.DocumentAccessTokenResult result = onlyOfficeService.verifyDocumentAccessToken(token, id);
        if (!result.valid()) {
            // Deliberately 404, not 403 - an invalid/expired/mismatched
            // token shouldn't confirm or deny that a file with this id
            // exists (see class comment).
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        Path path = storageService.resolve(file.getFilePath());
        try {
            Resource resource = new UrlResource(path.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File missing on disk");
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(file.getMimeType()))
                    // No Content-Disposition: attachment - ONLYOFFICE fetches
                    // this to render, not to hand back to a person; the
                    // config's own permissions.download=false is the actual
                    // control on whether a VIEWER can save the original
                    // (see OnlyOfficeService.createPreviewConfig).
                    .body(resource);
        } catch (MalformedURLException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not read file", e);
        }
    }

    /** Mirrors FileDetailController.requireViewable exactly - APPROVED files are viewable by any signed-in user; PENDING/REJECTED only by their uploader or a moderator/admin. */
    private void requireViewable(FileEntity file, EacUserDetails principal) {
        if (file.getStatus() == FileStatus.APPROVED) {
            return;
        }
        if (principal == null) {
            throw new AccessDeniedException("Sign-in required");
        }
        AppUser user = principal.getAppUser();
        boolean isOwner = user.getId().equals(file.getUploader().getId());
        boolean isModerator = user.getRole() == Role.MODERATOR || user.getRole() == Role.ADMIN;
        if (!isOwner && !isModerator) {
            throw new AccessDeniedException("This file is not yet public");
        }
    }
}
