package ph.edu.eac.filedirectory.web;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ph.edu.eac.filedirectory.file.FileEntity;
import ph.edu.eac.filedirectory.file.FileRepository;
import ph.edu.eac.filedirectory.file.FileStatus;
import ph.edu.eac.filedirectory.security.EacOAuth2User;
import ph.edu.eac.filedirectory.user.AppUser;

import java.time.Instant;

/**
 * Moderator/admin approval queue - see SecurityConfig, /admin/** requires
 * ROLE_MODERATOR or ROLE_ADMIN.
 */
@Controller
@RequestMapping("/admin")
public class AdminController {

    private final FileRepository fileRepository;

    public AdminController(FileRepository fileRepository) {
        this.fileRepository = fileRepository;
    }

    @GetMapping("/queue")
    public String queue(@RequestParam(defaultValue = "0") int page, Model model) {
        Pageable pageable = PageRequest.of(page, 20, Sort.by(Sort.Direction.ASC, "createdAt"));
        Page<FileEntity> pending = fileRepository.findByStatus(FileStatus.PENDING, pageable);
        model.addAttribute("files", pending);
        return "admin/queue";
    }

    @PostMapping("/files/{id}/approve")
    public String approve(@PathVariable Long id, @AuthenticationPrincipal OAuth2User principal, RedirectAttributes redirectAttributes) {
        AppUser moderator = requireModerator(principal);
        FileEntity file = fileRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        file.setStatus(FileStatus.APPROVED);
        file.setApprovedBy(moderator);
        file.setApprovedAt(Instant.now());
        file.setRejectionReason(null);
        fileRepository.save(file);

        redirectAttributes.addFlashAttribute("infoMessage", "Approved \"" + file.getTitle() + "\".");
        return "redirect:/admin/queue";
    }

    @PostMapping("/files/{id}/reject")
    public String reject(@PathVariable Long id,
                          @RequestParam(required = false) String reason,
                          @AuthenticationPrincipal OAuth2User principal,
                          RedirectAttributes redirectAttributes) {
        AppUser moderator = requireModerator(principal);
        FileEntity file = fileRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        file.setStatus(FileStatus.REJECTED);
        file.setApprovedBy(moderator);
        file.setApprovedAt(Instant.now());
        file.setRejectionReason(reason == null || reason.isBlank() ? "No reason given" : reason.trim());
        fileRepository.save(file);

        redirectAttributes.addFlashAttribute("infoMessage", "Rejected \"" + file.getTitle() + "\".");
        return "redirect:/admin/queue";
    }

    private AppUser requireModerator(OAuth2User principal) {
        if (!(principal instanceof EacOAuth2User eacUser)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return eacUser.getAppUser();
    }
}
