package ph.edu.eac.filedirectory.web;

import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import ph.edu.eac.filedirectory.file.FileEntity;
import ph.edu.eac.filedirectory.file.FileRepository;
import ph.edu.eac.filedirectory.file.FileStatus;
import ph.edu.eac.filedirectory.file.FileStorageService;
import ph.edu.eac.filedirectory.security.EacOAuth2User;
import ph.edu.eac.filedirectory.user.AppUser;
import ph.edu.eac.filedirectory.user.Role;

import java.net.MalformedURLException;
import java.nio.file.Path;

/**
 * File detail page and download endpoint. A file is visible/downloadable to
 * everyone once APPROVED; while PENDING/REJECTED only its uploader or a
 * moderator/admin may view it.
 */
@Controller
public class FileDetailController {

    private final FileRepository fileRepository;
    private final FileStorageService storageService;

    public FileDetailController(FileRepository fileRepository, FileStorageService storageService) {
        this.fileRepository = fileRepository;
        this.storageService = storageService;
    }

    @GetMapping("/files/{id}")
    public String detail(@PathVariable Long id, OAuth2User principal, Model model) {
        FileEntity file = fileRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        requireViewable(file, principal);

        model.addAttribute("file", file);
        return "file-detail";
    }

    @GetMapping("/files/{id}/download")
    @Transactional
    public ResponseEntity<Resource> download(@PathVariable Long id, OAuth2User principal) {
        FileEntity file = fileRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        requireViewable(file, principal);

        Path path = storageService.resolve(file.getFilePath());
        try {
            Resource resource = new UrlResource(path.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File missing on disk");
            }
            file.incrementDownloadCount();
            fileRepository.save(file);

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(file.getMimeType()))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getOriginalFilename() + "\"")
                    .body(resource);
        } catch (MalformedURLException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not read file", e);
        }
    }

    private void requireViewable(FileEntity file, OAuth2User principal) {
        if (file.getStatus() == FileStatus.APPROVED) {
            return;
        }
        if (!(principal instanceof EacOAuth2User eacUser)) {
            throw new AccessDeniedException("Sign-in required");
        }
        AppUser user = eacUser.getAppUser();
        boolean isOwner = user.getId().equals(file.getUploader().getId());
        boolean isModerator = user.getRole() == Role.MODERATOR || user.getRole() == Role.ADMIN;
        if (!isOwner && !isModerator) {
            throw new AccessDeniedException("This file is not yet public");
        }
    }
}
