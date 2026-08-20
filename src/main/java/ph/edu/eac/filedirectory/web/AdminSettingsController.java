package ph.edu.eac.filedirectory.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ph.edu.eac.filedirectory.audit.AuditService;
import ph.edu.eac.filedirectory.security.EacUserDetails;
import ph.edu.eac.filedirectory.settings.SystemSettingService;

/**
 * Admin-only system-wide settings - see SecurityConfig, /admin/users/**
 * requires ROLE_ADMIN (this controller's mapping falls under that prefix,
 * same as AuditLogController, so it inherits that gate without a new
 * SecurityConfig matcher). Currently just the duplicate-upload toggle (see
 * SystemSetting) - a natural home for future system-wide toggles as they're
 * added, rather than crowding Manage Users with unrelated config.
 */
@Controller
@RequestMapping("/admin/users/settings")
public class AdminSettingsController {

    private final SystemSettingService systemSettingService;
    private final AuditService auditService;

    public AdminSettingsController(SystemSettingService systemSettingService, AuditService auditService) {
        this.systemSettingService = systemSettingService;
        this.auditService = auditService;
    }

    @GetMapping
    public String view(Model model) {
        model.addAttribute("allowDuplicateUploads", systemSettingService.isAllowDuplicateUploads());
        model.addAttribute("allowModeratorAccessRequestFallback", systemSettingService.isAllowModeratorAccessRequestFallback());
        return "admin/settings";
    }

    @PostMapping("/duplicate-uploads")
    public String updateDuplicateUploads(@RequestParam(required = false, defaultValue = "false") boolean allowed,
                                          @AuthenticationPrincipal EacUserDetails principal,
                                          RedirectAttributes redirectAttributes) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        boolean previous = systemSettingService.isAllowDuplicateUploads();
        systemSettingService.setAllowDuplicateUploads(allowed);

        if (previous != allowed) {
            auditService.systemSettingChanged(principal.getAppUser(), "allowDuplicateUploads",
                    String.valueOf(previous), String.valueOf(allowed));
        }

        redirectAttributes.addFlashAttribute("infoMessage",
                allowed
                        ? "Duplicate uploads are now allowed without a confirmation step."
                        : "Duplicate uploads will be flagged and require confirmation again.");
        return "redirect:/admin/users/settings";
    }

    /**
     * Toggles whether a moderator/admin may ever step in and decide an
     * access request themselves - see AccessRequestService.canDecide,
     * which re-checks this setting fresh on every approve/deny call, so
     * turning it off here takes effect immediately for any still-pending
     * request (not just new ones).
     */
    @PostMapping("/moderator-access-fallback")
    public String updateModeratorAccessRequestFallback(@RequestParam(required = false, defaultValue = "false") boolean allowed,
                                                         @AuthenticationPrincipal EacUserDetails principal,
                                                         RedirectAttributes redirectAttributes) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        boolean previous = systemSettingService.isAllowModeratorAccessRequestFallback();
        systemSettingService.setAllowModeratorAccessRequestFallback(allowed);

        if (previous != allowed) {
            auditService.systemSettingChanged(principal.getAppUser(), "allowModeratorAccessRequestFallback",
                    String.valueOf(previous), String.valueOf(allowed));
        }

        redirectAttributes.addFlashAttribute("infoMessage",
                allowed
                        ? "Moderators/admins can now step in on access requests the uploader hasn't answered after the fallback window."
                        : "Moderator/admin fallback for access requests is now off - only the uploader can decide.");
        return "redirect:/admin/users/settings";
    }
}
