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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ph.edu.eac.filedirectory.audit.AuditService;
import ph.edu.eac.filedirectory.file.*;
import ph.edu.eac.filedirectory.onlyoffice.OnlyOfficeProperties;
import ph.edu.eac.filedirectory.onlyoffice.OnlyOfficeService;
import ph.edu.eac.filedirectory.security.EacUserDetails;
import ph.edu.eac.filedirectory.user.AppUser;
import ph.edu.eac.filedirectory.user.Role;

import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Controller
public class FileVersionController {

    private final FileRepository fileRepository;
    private final FileVersionRepository fileVersionRepository;
    private final FileStorageService storageService;
    private final FileTypeValidator fileTypeValidator;
    private final AuditService auditService;
    private final OnlyOfficeService onlyOfficeService;
    private final OnlyOfficeProperties onlyOfficeProperties;

    public FileVersionController(FileRepository fileRepository,
                                 FileVersionRepository fileVersionRepository,
                                 FileStorageService storageService,
                                 FileTypeValidator fileTypeValidator,
                                 AuditService auditService,
                                 OnlyOfficeService onlyOfficeService,
                                 OnlyOfficeProperties onlyOfficeProperties) {
        this.fileRepository = fileRepository;
        this.fileVersionRepository = fileVersionRepository;
        this.storageService = storageService;
        this.fileTypeValidator = fileTypeValidator;
        this.auditService = auditService;
        this.onlyOfficeService = onlyOfficeService;
        this.onlyOfficeProperties = onlyOfficeProperties;
    }

    @PostMapping("/files/{id}/versions")
    @Transactional
    public String uploadVersion(@PathVariable Long id,
                                @RequestParam("file") MultipartFile upload,
                                @RequestParam(required = false) String note,
                                @AuthenticationPrincipal EacUserDetails principal,
                                RedirectAttributes redirectAttributes) {
        FileEntity file = fileRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
        AppUser actor = requireCanVersion(file, principal);

        if (upload.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Please choose a replacement file.");
            return "redirect:/files/" + file.getId();
        }

        FileTypeValidator.ValidationResult typeCheck = fileTypeValidator.validate(upload);
        if (!typeCheck.valid()) {
            redirectAttributes.addFlashAttribute("errorMessage", typeCheck.reason());
            return "redirect:/files/" + file.getId();
        }
        String incomingExtension = extensionOf(upload.getOriginalFilename());
        if (!file.extension().equals(incomingExtension)) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "New versions must use the same file type as the current file (." + file.extension() + ").");
            return "redirect:/files/" + file.getId();
        }

        int currentVersion = file.getVersionNumber();
        if (!fileVersionRepository.existsByFileAndVersionNumber(file, currentVersion)) {
            fileVersionRepository.save(snapshot(file, currentVersion, file.getUploader(), "Original uploaded file"));
        }

        FileStorageService.StoredFile stored = storageService.store(upload, file.getDepartment());
        int latestKnownVersion = fileVersionRepository.findByFileOrderByVersionNumberDesc(file).stream()
                .mapToInt(FileVersion::getVersionNumber)
                .max()
                .orElse(currentVersion);
        int nextVersion = latestKnownVersion + 1;
        String originalFilename = upload.getOriginalFilename() == null ? "file" : upload.getOriginalFilename();
        String mimeType = upload.getContentType() == null ? "application/octet-stream" : upload.getContentType();
        String cleanNote = blankToNull(note);

        FileVersion version = new FileVersion(file, nextVersion, stored.relativePath(), originalFilename,
                stored.size(), mimeType, stored.checksumSha256(), actor, cleanNote);

        if (isStaff(actor)) {
            promoteVersion(file, version, actor);
        } else if (file.getStatus() == FileStatus.APPROVED) {
            rejectOlderPendingVersions(file, actor, "Superseded by version " + nextVersion);
            version.setStatus(FileStatus.PENDING);
            fileVersionRepository.save(version);
        } else {
            promoteVersion(file, version, actor);
            file.setStatus(FileStatus.PENDING);
            file.setApprovedBy(null);
            file.setApprovedAt(null);
            file.setRejectionReason(null);
        }

        fileRepository.save(file);
        fileVersionRepository.save(version);
        auditService.fileVersionUploaded(actor, file.getId(), file.getTitle(), nextVersion, cleanNote);

        redirectAttributes.addFlashAttribute("infoMessage",
                isStaff(actor)
                        ? "Version " + nextVersion + " uploaded for \"" + file.getTitle() + "\"."
                        : "Version " + nextVersion + " uploaded. It will appear after staff approval.");
        return "redirect:/files/" + file.getId();
    }

    @GetMapping("/files/{id}/versions/{versionNumber}/download")
    public ResponseEntity<Resource> downloadVersion(@PathVariable Long id,
                                                    @PathVariable int versionNumber,
                                                    @AuthenticationPrincipal EacUserDetails principal) {
        FileEntity file = fileRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
        requireCanVersion(file, principal);
        FileVersion version = fileVersionRepository.findByFileAndVersionNumber(file, versionNumber)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));

        Path path = storageService.resolve(version.getFilePath());
        try {
            Resource resource = new UrlResource(path.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw new ResponseStatusException(NOT_FOUND, "Version file missing on disk");
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(version.getMimeType()))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + version.getOriginalFilename() + "\"")
                    .body(resource);
        } catch (MalformedURLException e) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "Could not read version file", e);
        }
    }

    @GetMapping("/files/{id}/versions/{versionNumber}/preview")
    public String previewVersion(@PathVariable Long id,
                                 @PathVariable int versionNumber,
                                 @AuthenticationPrincipal EacUserDetails principal,
                                 org.springframework.ui.Model model) {
        FileEntity file = fileRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
        requireCanVersion(file, principal);
        FileVersion version = requirePreviewableVersion(file, versionNumber);

        model.addAttribute("file", file);
        model.addAttribute("version", version);
        return "version-preview";
    }

    @GetMapping("/files/{id}/versions/{versionNumber}/preview-content")
    public ResponseEntity<Resource> previewVersionContent(@PathVariable Long id,
                                                          @PathVariable int versionNumber,
                                                          @AuthenticationPrincipal EacUserDetails principal) {
        FileEntity file = fileRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
        requireCanVersion(file, principal);
        FileVersion version = requirePreviewableVersion(file, versionNumber);
        if (!version.isPreviewable()) {
            throw new ResponseStatusException(NOT_FOUND, "No PDF preview for this version");
        }
        Path path = storageService.resolve(version.getFilePath());
        try {
            Resource resource = new UrlResource(path.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw new ResponseStatusException(NOT_FOUND, "Version file missing on disk");
            }
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).body(resource);
        } catch (MalformedURLException e) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "Could not read version file", e);
        }
    }

    @GetMapping("/files/{id}/versions/{versionNumber}/text-content")
    public ResponseEntity<String> textVersionContent(@PathVariable Long id,
                                                     @PathVariable int versionNumber,
                                                     @AuthenticationPrincipal EacUserDetails principal) {
        FileEntity file = fileRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
        requireCanVersion(file, principal);
        FileVersion version = requirePreviewableVersion(file, versionNumber);
        if (!version.isTextPreviewable()) {
            throw new ResponseStatusException(NOT_FOUND, "No text preview for this version");
        }
        Path path = storageService.resolve(version.getFilePath());
        if (!Files.isReadable(path)) {
            throw new ResponseStatusException(NOT_FOUND, "Version file missing on disk");
        }
        try {
            long fileSize = Files.size(path);
            byte[] bytes;
            boolean truncated;
            try (var in = Files.newInputStream(path)) {
                bytes = in.readNBytes((int) Math.min(fileSize, 1_048_576));
                truncated = fileSize > 1_048_576;
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (truncated) {
                text += "\n\n[Preview truncated - download the version to see the rest.]";
            }
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(text);
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "Could not read version file", e);
        }
    }

    @GetMapping("/files/{id}/versions/{versionNumber}/onlyoffice-config")
    @ResponseBody
    public Map<String, Object> onlyOfficeVersionConfig(@PathVariable Long id,
                                                       @PathVariable int versionNumber,
                                                       @AuthenticationPrincipal EacUserDetails principal) {
        FileEntity file = fileRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
        requireCanVersion(file, principal);
        FileVersion version = requirePreviewableVersion(file, versionNumber);
        if (!onlyOfficeService.isSupported(version)) {
            throw new ResponseStatusException(NOT_FOUND, "No ONLYOFFICE preview for this version");
        }
        return Map.of(
                "serverUrl", onlyOfficeProperties.url(),
                "config", onlyOfficeService.createPreviewConfig(version)
        );
    }

    @GetMapping("/files/{id}/versions/{versionNumber}/onlyoffice-content")
    public ResponseEntity<Resource> onlyOfficeVersionContent(@PathVariable Long id,
                                                             @PathVariable int versionNumber,
                                                             @RequestParam String token) {
        FileEntity file = fileRepository.findById(id).orElse(null);
        if (file == null) {
            throw new ResponseStatusException(NOT_FOUND);
        }
        OnlyOfficeService.DocumentAccessTokenResult result = onlyOfficeService.verifyDocumentAccessToken(token, id, versionNumber);
        if (!result.valid()) {
            throw new ResponseStatusException(NOT_FOUND);
        }
        FileVersion version = fileVersionRepository.findByFileAndVersionNumber(file, versionNumber)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
        Path path = storageService.resolve(version.getFilePath());
        try {
            Resource resource = new UrlResource(path.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw new ResponseStatusException(NOT_FOUND, "Version file missing on disk");
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(version.getMimeType()))
                    .body(resource);
        } catch (MalformedURLException e) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "Could not read version file", e);
        }
    }

    private FileVersion snapshot(FileEntity file, int versionNumber, AppUser uploadedBy, String note) {
        FileVersion version = new FileVersion(file, versionNumber, file.getFilePath(), file.getOriginalFilename(),
                file.getFileSize(), file.getMimeType(), file.getChecksum(), uploadedBy, note);
        version.setStatus(file.getStatus() == FileStatus.APPROVED ? FileStatus.APPROVED : FileStatus.PENDING);
        version.setApprovedBy(file.getApprovedBy());
        version.setApprovedAt(file.getApprovedAt());
        version.setRejectionReason(file.getRejectionReason());
        return version;
    }

    private void promoteVersion(FileEntity file, FileVersion version, AppUser approver) {
        file.setFilePath(version.getFilePath());
        file.setOriginalFilename(version.getOriginalFilename());
        file.setFileSize(version.getFileSize());
        file.setMimeType(version.getMimeType());
        file.setChecksum(version.getChecksum());
        file.setVersionNumber(version.getVersionNumber());
        file.setStatus(FileStatus.APPROVED);
        file.setApprovedBy(approver);
        file.setApprovedAt(Instant.now());
        file.setRejectionReason(null);
        version.setStatus(FileStatus.APPROVED);
        version.setApprovedBy(approver);
        version.setApprovedAt(file.getApprovedAt());
        version.setRejectionReason(null);
    }

    private void rejectOlderPendingVersions(FileEntity file, AppUser actor, String reason) {
        for (FileVersion pending : fileVersionRepository.findByFileAndStatusOrderByVersionNumberDesc(file, FileStatus.PENDING)) {
            pending.setStatus(FileStatus.REJECTED);
            pending.setApprovedBy(actor);
            pending.setApprovedAt(Instant.now());
            pending.setRejectionReason(reason);
            fileVersionRepository.save(pending);
        }
    }

    private FileVersion requirePreviewableVersion(FileEntity file, int versionNumber) {
        if (versionNumber < Math.max(1, file.getVersionNumber() - 2)) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "Only the latest three versions can be previewed.");
        }
        return fileVersionRepository.findByFileAndVersionNumber(file, versionNumber)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
    }

    private AppUser requireCanVersion(FileEntity file, EacUserDetails principal) {
        if (principal == null) {
            throw new AccessDeniedException("Sign-in required");
        }
        AppUser user = principal.getAppUser();
        boolean isOwner = user.getId().equals(file.getUploader().getId());
        boolean isStaff = isStaff(user);
        if (!isOwner && !isStaff) {
            throw new AccessDeniedException("You can only manage versions for your own uploads.");
        }
        return user;
    }

    private boolean isStaff(AppUser user) {
        return user.getRole() == Role.MODERATOR || user.getRole() == Role.ADMIN;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }
}
