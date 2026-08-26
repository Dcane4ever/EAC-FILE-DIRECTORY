package ph.edu.eac.filedirectory.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import ph.edu.eac.filedirectory.file.FileEntity;
import ph.edu.eac.filedirectory.file.FileRepository;
import ph.edu.eac.filedirectory.file.FileStatus;
import ph.edu.eac.filedirectory.saved.SavedFile;
import ph.edu.eac.filedirectory.saved.SavedFileRepository;
import ph.edu.eac.filedirectory.security.EacUserDetails;
import ph.edu.eac.filedirectory.user.AppUser;
import ph.edu.eac.filedirectory.user.Role;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Controller
public class SavedFileController {

    private static final int PAGE_SIZE = 12;

    private final SavedFileRepository savedFileRepository;
    private final FileRepository fileRepository;

    public SavedFileController(SavedFileRepository savedFileRepository, FileRepository fileRepository) {
        this.savedFileRepository = savedFileRepository;
        this.fileRepository = fileRepository;
    }

    @GetMapping("/saved-files")
    public String savedFiles(@AuthenticationPrincipal EacUserDetails principal,
                             @RequestParam(defaultValue = "0") int page,
                             Model model) {
        AppUser user = requireUser(principal);
        Page<SavedFile> savedFiles = savedFileRepository.findByUserOrderByCreatedAtDesc(
                user, PageRequest.of(page, PAGE_SIZE));

        model.addAttribute("savedFiles", savedFiles);
        model.addAttribute("savedCount", savedFileRepository.countByUser(user));
        return "saved-files";
    }

    @PostMapping("/files/{id}/save")
    @Transactional
    public String save(@PathVariable Long id,
                       @AuthenticationPrincipal EacUserDetails principal,
                       HttpServletRequest request) {
        AppUser user = requireUser(principal);
        FileEntity file = fileRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));

        requireViewable(file, user);
        savedFileRepository.findByUserAndFile(user, file)
                .orElseGet(() -> savedFileRepository.save(new SavedFile(user, file)));

        return redirectBack(request, file.getId());
    }

    @PostMapping("/files/{id}/unsave")
    @Transactional
    public String unsave(@PathVariable Long id,
                         @AuthenticationPrincipal EacUserDetails principal,
                         HttpServletRequest request) {
        AppUser user = requireUser(principal);
        FileEntity file = fileRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));

        savedFileRepository.findByUserAndFile(user, file).ifPresent(savedFileRepository::delete);
        return redirectBack(request, file.getId());
    }

    private AppUser requireUser(EacUserDetails principal) {
        if (principal == null) {
            throw new AccessDeniedException("Sign-in required");
        }
        return principal.getAppUser();
    }

    private void requireViewable(FileEntity file, AppUser user) {
        if (file.getStatus() == FileStatus.APPROVED) {
            return;
        }
        boolean isOwner = user.getId().equals(file.getUploader().getId());
        boolean isStaff = user.getRole() == Role.MODERATOR || user.getRole() == Role.ADMIN;
        if (!isOwner && !isStaff) {
            throw new AccessDeniedException("This file is not available to save.");
        }
    }

    private String redirectBack(HttpServletRequest request, Long fileId) {
        String referer = request.getHeader("Referer");
        if (referer != null && referer.contains("/saved-files")) {
            return "redirect:/saved-files";
        }
        return "redirect:/files/" + fileId;
    }
}
