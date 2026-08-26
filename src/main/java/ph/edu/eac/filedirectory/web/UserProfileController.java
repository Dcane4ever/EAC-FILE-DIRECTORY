package ph.edu.eac.filedirectory.web;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;
import ph.edu.eac.filedirectory.file.FileEntity;
import ph.edu.eac.filedirectory.file.FileRepository;
import ph.edu.eac.filedirectory.file.FileStatus;
import ph.edu.eac.filedirectory.security.EacUserDetails;
import ph.edu.eac.filedirectory.user.AppUser;
import ph.edu.eac.filedirectory.user.AppUserRepository;

import java.util.Locale;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Controller
public class UserProfileController {

    private static final int RECENT_FILE_COUNT = 6;
    private static final int TOP_CATEGORY_COUNT = 4;

    private final AppUserRepository userRepository;
    private final FileRepository fileRepository;

    public UserProfileController(AppUserRepository userRepository, FileRepository fileRepository) {
        this.userRepository = userRepository;
        this.fileRepository = fileRepository;
    }

    @GetMapping("/users/{id}")
    public String profile(@PathVariable Long id,
                          @AuthenticationPrincipal EacUserDetails principal,
                          Model model) {
        AppUser profileUser = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));

        Page<FileEntity> approvedFiles = fileRepository.findByUploaderAndStatusOrderByCreatedAtDesc(
                profileUser, FileStatus.APPROVED, PageRequest.of(0, RECENT_FILE_COUNT));

        long approvedCount = fileRepository.countByUploaderAndStatus(profileUser, FileStatus.APPROVED);
        long pendingCount = isOwnProfile(profileUser, principal)
                ? fileRepository.countByUploaderAndStatus(profileUser, FileStatus.PENDING)
                : 0;
        long downloads = fileRepository.sumDownloadCountByUploaderAndStatus(profileUser, FileStatus.APPROVED);

        model.addAttribute("profileUser", profileUser);
        model.addAttribute("displayName", displayName(profileUser));
        model.addAttribute("initials", initials(profileUser));
        model.addAttribute("isOwnProfile", isOwnProfile(profileUser, principal));
        model.addAttribute("approvedCount", approvedCount);
        model.addAttribute("pendingCount", pendingCount);
        model.addAttribute("downloadCount", downloads);
        model.addAttribute("recentFiles", approvedFiles.getContent());
        model.addAttribute("topCategories", fileRepository.topCategoriesByUploaderAndStatus(
                profileUser, FileStatus.APPROVED, PageRequest.of(0, TOP_CATEGORY_COUNT)));

        return "user-profile";
    }

    private boolean isOwnProfile(AppUser profileUser, EacUserDetails principal) {
        return principal != null && profileUser.getId().equals(principal.getAppUser().getId());
    }

    private String displayName(AppUser user) {
        if (user.getFullName() != null && !user.getFullName().isBlank()) {
            return user.getFullName().trim();
        }
        return user.getEmail();
    }

    private String initials(AppUser user) {
        String source = displayName(user).replace("@eac.edu.ph", "").trim();
        String[] parts = source.split("\\s+");
        if (parts.length == 0 || parts[0].isBlank()) {
            return "EA";
        }
        if (parts.length == 1) {
            return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase(Locale.ROOT);
        }
        return (parts[0].substring(0, 1) + parts[parts.length - 1].substring(0, 1)).toUpperCase(Locale.ROOT);
    }
}
