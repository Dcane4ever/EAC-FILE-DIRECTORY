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
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import ph.edu.eac.filedirectory.access.AccessRequest;
import ph.edu.eac.filedirectory.access.AccessRequestRepository;
import ph.edu.eac.filedirectory.access.AccessRequestStatus;
import ph.edu.eac.filedirectory.audit.AuditService;
import ph.edu.eac.filedirectory.file.FileEntity;
import ph.edu.eac.filedirectory.file.FileRepository;
import ph.edu.eac.filedirectory.file.FileStatus;
import ph.edu.eac.filedirectory.file.FileStorageService;
import ph.edu.eac.filedirectory.security.EacUserDetails;
import ph.edu.eac.filedirectory.user.AppUser;
import ph.edu.eac.filedirectory.user.Role;

import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * File detail page and direct-download endpoint.
 *
 * Visibility (requireViewable): APPROVED files are viewable by any
 * signed-in user; PENDING/REJECTED files only by their uploader or a
 * moderator/admin.
 *
 * Direct DOWNLOAD is stricter than viewing, as of Phase 9 (see
 * requireDirectDownload): only the uploader themselves, or a moderator/
 * admin, get the one-click Download button - see AdminController's queue,
 * which already needs to review PENDING files' actual content, and
 * "downloading your own upload" needing no permission is self-evident.
 * Everyone else who can view an APPROVED file (any other signed-in user)
 * sees "Request Access" instead - see AccessRequestController - and can
 * only get the original bytes through an uploader-approved,
 * identity-tied, time-limited grant link at
 * /files/access-requests/{id}/download.
 */
@Controller
public class FileDetailController {

    private final FileRepository fileRepository;
    private final FileStorageService storageService;
    private final AuditService auditService;
    private final AccessRequestRepository accessRequestRepository;

    public FileDetailController(FileRepository fileRepository, FileStorageService storageService,
                                 AuditService auditService, AccessRequestRepository accessRequestRepository) {
        this.fileRepository = fileRepository;
        this.storageService = storageService;
        this.auditService = auditService;
        this.accessRequestRepository = accessRequestRepository;
    }

    @GetMapping("/files/{id}")
    public String detail(@PathVariable Long id, @AuthenticationPrincipal EacUserDetails principal, Model model) {
        FileEntity file = fileRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        requireViewable(file, principal);

        model.addAttribute("file", file);
        // preview-guard.js's devtools-reload reaction is a best-effort
        // deterrent (see that file's header comment for exactly what it
        // can't actually stop) - staff are exempted so their own review
        // work isn't disrupted by a false positive.
        model.addAttribute("isStaff", isStaff(principal));

        boolean canDownloadDirectly = principal != null && canDownloadDirectly(file, principal.getAppUser());
        model.addAttribute("canDownloadDirectly", canDownloadDirectly);
        if (!canDownloadDirectly && principal != null) {
            AccessRequest existing = accessRequestRepository
                    .findFirstByFileAndRequesterAndStatus(file, principal.getAppUser(), AccessRequestStatus.PENDING)
                    .orElse(null);
            model.addAttribute("pendingAccessRequest", existing);
        }
        return "file-detail";
    }

    /**
     * Serves the raw PDF bytes for the in-browser preview (see
     * file-detail.html's PDF.js viewer) - same visibility rule as
     * everything else (requireViewable), and does NOT increment
     * downloadCount or write a FILE_DOWNLOADED audit event, since viewing a
     * preview is a materially different action from downloading the
     * original file (see AuditAction.FILE_DOWNLOADED's existing meaning).
     * 404s for anything that isn't currently previewable (see
     * FileEntity.isPreviewable) rather than serving non-PDF bytes into a
     * PDF renderer.
     */
    @GetMapping("/files/{id}/preview-content")
    public ResponseEntity<Resource> previewContent(@PathVariable Long id, @AuthenticationPrincipal EacUserDetails principal) {
        FileEntity file = fileRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        requireViewable(file, principal);

        if (!file.isPreviewable()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No preview available for this file type");
        }

        Path path = storageService.resolve(file.getFilePath());
        try {
            Resource resource = new UrlResource(path.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File missing on disk");
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    // No Content-Disposition: attachment - this is meant to
                    // be fetched and rendered by PDF.js, not saved directly
                    // (see preview-guard.js for the rest of that deterrent).
                    .body(resource);
        } catch (MalformedURLException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not read file", e);
        }
    }

    /** Read cap for the text preview endpoint - see textContent()'s javadoc for why this exists and how truncation is signaled. */
    private static final long TEXT_PREVIEW_MAX_BYTES = 1_048_576; // 1 MiB

    /**
     * Serves a plain-text preview of a .txt file - same visibility rule as
     * every other preview endpoint (requireViewable), same "don't count as
     * a download" reasoning as previewContent() above. Reads at most
     * TEXT_PREVIEW_MAX_BYTES so a genuinely huge .txt upload can't turn a
     * page load into streaming megabytes of text into the browser; a
     * truncated read gets an explicit notice appended rather than silently
     * cutting off mid-sentence with no indication anything was cut.
     * Content-Type is text/plain (never text/html or anything the browser
     * might try to render as markup) - this is someone's uploaded file
     * content, displayed as literal text in a <pre> block by
     * file-detail.html, not interpreted.
     */
    @GetMapping("/files/{id}/text-content")
    public ResponseEntity<String> textContent(@PathVariable Long id, @AuthenticationPrincipal EacUserDetails principal) {
        FileEntity file = fileRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        requireViewable(file, principal);

        if (!file.isTextPreviewable()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No text preview available for this file type");
        }

        Path path = storageService.resolve(file.getFilePath());
        if (!Files.isReadable(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File missing on disk");
        }

        try {
            long fileSize = Files.size(path);
            byte[] bytes;
            boolean truncated;
            try (var in = Files.newInputStream(path)) {
                bytes = in.readNBytes((int) Math.min(fileSize, TEXT_PREVIEW_MAX_BYTES));
                truncated = fileSize > TEXT_PREVIEW_MAX_BYTES;
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (truncated) {
                text += "\n\n[Preview truncated - download the file to see the rest.]";
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(text);
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not read file", e);
        }
    }

    /**
     * Direct download - as of Phase 9, gated by requireDirectDownload, not
     * just requireViewable. Anyone else who can view this file but not
     * download it directly is sent to AccessRequestController's request
     * flow instead (see file-detail.html for the "Request Access" button
     * shown in that case, and canDownloadDirectly()).
     */
    @GetMapping("/files/{id}/download")
    @Transactional
    public ResponseEntity<Resource> download(@PathVariable Long id, @AuthenticationPrincipal EacUserDetails principal) {
        FileEntity file = fileRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        requireViewable(file, principal);
        requireDirectDownload(file, principal);

        Path path = storageService.resolve(file.getFilePath());
        try {
            Resource resource = new UrlResource(path.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File missing on disk");
            }
            file.incrementDownloadCount();
            fileRepository.save(file);

            if (principal != null) {
                auditService.fileDownloaded(principal.getAppUser(), file.getId(), file.getTitle());
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(file.getMimeType()))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getOriginalFilename() + "\"")
                    .body(resource);
        } catch (MalformedURLException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not read file", e);
        }
    }

    private boolean isStaff(EacUserDetails principal) {
        if (principal == null) {
            return false;
        }
        Role role = principal.getAppUser().getRole();
        return role == Role.MODERATOR || role == Role.ADMIN;
    }

    private boolean canDownloadDirectly(FileEntity file, AppUser user) {
        boolean isOwner = user.getId().equals(file.getUploader().getId());
        boolean isModerator = user.getRole() == Role.MODERATOR || user.getRole() == Role.ADMIN;
        return isOwner || isModerator;
    }

    private void requireDirectDownload(FileEntity file, EacUserDetails principal) {
        if (principal == null || !canDownloadDirectly(file, principal.getAppUser())) {
            throw new AccessDeniedException("Direct download isn't available for this file - request access instead.");
        }
    }

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
