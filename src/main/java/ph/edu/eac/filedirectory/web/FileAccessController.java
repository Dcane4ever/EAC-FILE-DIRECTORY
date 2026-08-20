package ph.edu.eac.filedirectory.web;

import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import ph.edu.eac.filedirectory.access.AccessGrantToken;
import ph.edu.eac.filedirectory.access.AccessGrantTokenRepository;
import ph.edu.eac.filedirectory.access.AccessRequest;
import ph.edu.eac.filedirectory.audit.AuditService;
import ph.edu.eac.filedirectory.file.FileEntity;
import ph.edu.eac.filedirectory.file.FileRepository;
import ph.edu.eac.filedirectory.file.FileStorageService;
import ph.edu.eac.filedirectory.security.EacUserDetails;

import java.net.MalformedURLException;
import java.nio.file.Path;

/**
 * The download endpoint an approved AccessGrantToken actually unlocks - see
 * AccessRequestService.approve() for how the token gets created, and
 * FileDetailController's javadoc for how this fits alongside the (now
 * narrower) direct-download endpoint. Validates three things, all
 * required: the token exists and is still valid (unused, unexpired - see
 * AccessGrantToken.isValid), and the currently signed-in user IS the
 * original requester - so this is "a link for one specific person", not
 * "anyone who has the URL". The token flips to used the moment the file is
 * actually streamed - see the class comment on AccessGrantToken for what
 * "single-use" means in practice here.
 */
@Controller
public class FileAccessController {

    private final AccessGrantTokenRepository accessGrantTokenRepository;
    private final FileStorageService storageService;
    private final FileRepository fileRepository;
    private final AuditService auditService;

    public FileAccessController(AccessGrantTokenRepository accessGrantTokenRepository,
                                 FileStorageService storageService,
                                 FileRepository fileRepository,
                                 AuditService auditService) {
        this.accessGrantTokenRepository = accessGrantTokenRepository;
        this.storageService = storageService;
        this.fileRepository = fileRepository;
        this.auditService = auditService;
    }

    @GetMapping("/files/access-requests/{id}/download")
    @Transactional
    public ResponseEntity<Resource> downloadWithGrant(@PathVariable Long id,
                                                        @RequestParam String token,
                                                        @AuthenticationPrincipal EacUserDetails principal) {
        if (principal == null) {
            throw new AccessDeniedException("Sign-in required");
        }

        AccessGrantToken grant = accessGrantTokenRepository.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invalid or expired link"));

        AccessRequest accessRequest = grant.getAccessRequest();
        if (!accessRequest.getId().equals(id)) {
            // Token doesn't belong to the request ID in the URL - treat the
            // same as "not found" rather than leaking which part mismatched.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Invalid or expired link");
        }
        if (!accessRequest.getRequester().getId().equals(principal.getAppUser().getId())) {
            // Right token, wrong person - this link is tied to whoever
            // requested it, not to anyone who happens to have the URL.
            throw new AccessDeniedException("This download link isn't yours to use.");
        }
        if (!grant.isValid()) {
            throw new ResponseStatusException(HttpStatus.GONE, "This download link has expired or already been used.");
        }

        FileEntity file = accessRequest.getFile();
        Path path = storageService.resolve(file.getFilePath());
        try {
            Resource resource = new UrlResource(path.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File missing on disk");
            }

            grant.setUsed(true);
            accessGrantTokenRepository.save(grant);

            file.incrementDownloadCount();
            fileRepository.save(file);
            auditService.accessGrantDownloaded(principal.getAppUser(), file.getId(), file.getTitle());

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(file.getMimeType()))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getOriginalFilename() + "\"")
                    .body(resource);
        } catch (MalformedURLException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not read file", e);
        }
    }
}
