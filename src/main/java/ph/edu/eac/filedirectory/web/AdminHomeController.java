package ph.edu.eac.filedirectory.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import ph.edu.eac.filedirectory.file.FileRepository;
import ph.edu.eac.filedirectory.file.FileStatus;
import ph.edu.eac.filedirectory.file.FileVersionRepository;
import ph.edu.eac.filedirectory.security.EacUserDetails;
import ph.edu.eac.filedirectory.taxonomy.DepartmentRepository;
import ph.edu.eac.filedirectory.user.AppUserRepository;
import ph.edu.eac.filedirectory.user.Role;

/**
 * Admin/moderator landing page (/admin/home) - what a staff account sees
 * right after signing in via /admin/login (or the regular /login - see
 * RoleBasedLoginSuccessHandler), instead of the student /home. Same visual
 * language as the student landing page (header pattern, EAC theme, card
 * style) but admin-relevant content: a real-data overview + quick links
 * into the Approval Queue and Manage Users, both gated the same way those
 * pages already are.
 */
@Controller
public class AdminHomeController {

    private final FileRepository fileRepository;
    private final FileVersionRepository fileVersionRepository;
    private final DepartmentRepository departmentRepository;
    private final AppUserRepository userRepository;

    public AdminHomeController(FileRepository fileRepository,
                                FileVersionRepository fileVersionRepository,
                                DepartmentRepository departmentRepository,
                                AppUserRepository userRepository) {
        this.fileRepository = fileRepository;
        this.fileVersionRepository = fileVersionRepository;
        this.departmentRepository = departmentRepository;
        this.userRepository = userRepository;
    }

    @GetMapping("/admin/home")
    public String home(@AuthenticationPrincipal EacUserDetails principal, Model model) {
        if (principal != null) {
            model.addAttribute("user", principal.getAppUser());
        }
        model.addAttribute("pendingCount", fileRepository.countByStatus(FileStatus.PENDING)
                + fileVersionRepository.findByStatusOrderByCreatedAtAsc(FileStatus.PENDING).size());
        model.addAttribute("departmentCount", departmentRepository.count());
        model.addAttribute("autoApproveCount", departmentRepository.countByAutoApproveTrue());
        model.addAttribute("userCount", userRepository.count());
        model.addAttribute("adminCount", userRepository.countByRole(Role.ADMIN));
        model.addAttribute("moderatorCount", userRepository.countByRole(Role.MODERATOR));
        return "admin/home";
    }
}
