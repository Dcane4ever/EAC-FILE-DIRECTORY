package ph.edu.eac.filedirectory.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ph.edu.eac.filedirectory.audit.AuditService;
import ph.edu.eac.filedirectory.notification.NotificationService;
import ph.edu.eac.filedirectory.security.EacUserDetails;
import ph.edu.eac.filedirectory.security.ratelimit.RateLimiter;
import ph.edu.eac.filedirectory.user.AppUser;
import ph.edu.eac.filedirectory.user.AppUserRepository;
import ph.edu.eac.filedirectory.user.Role;

import java.time.Duration;

/**
 * Admin-only account/role management - see SecurityConfig, /admin/users/**
 * requires ROLE_ADMIN specifically (not MODERATOR - promoting/demoting
 * accounts is a stricter action than approving uploads). Two ways an
 * account gets here: self-registration (see RegistrationController, always
 * starts as USER) or an admin creating one directly from this screen
 * (email still restricted to @eac.edu.ph, but pre-verified - for
 * dummy/test accounts with no real inbox to click a verification link
 * from). A system-wide cap of MAX_ADMINS keeps ADMIN a genuinely small,
 * deliberate set, whether reached by promotion or direct creation.
 * Granting ADMIN (either path) requires the acting admin to re-enter their
 * own current password as confirmation - that confirmation step is
 * rate-limited per acting-admin-account (see verifyOwnPassword), since it's
 * otherwise a password-guessing surface against whoever is currently
 * signed in as an admin.
 */
@Controller
@RequestMapping("/admin/users")
public class UserManagementController {

    /** System-wide cap on ADMIN-role accounts, enforced whether reached by promotion or direct creation. */
    private static final int MAX_ADMINS = 3;

    private static final int MAX_PASSWORD_CONFIRM_ATTEMPTS = 5;
    private static final Duration PASSWORD_CONFIRM_WINDOW = Duration.ofMinutes(15);

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final NotificationService notificationService;
    private final RateLimiter rateLimiter;
    private final AuditService auditService;
    private final String allowedDomain;

    public UserManagementController(AppUserRepository userRepository,
                                     PasswordEncoder passwordEncoder,
                                     NotificationService notificationService,
                                     RateLimiter rateLimiter,
                                     AuditService auditService,
                                     @Value("${eac.allowed-email-domain}") String allowedDomain) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.notificationService = notificationService;
        this.rateLimiter = rateLimiter;
        this.auditService = auditService;
        this.allowedDomain = allowedDomain.toLowerCase();
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("users", userRepository.findAll(Sort.by("email")));
        model.addAttribute("roles", Role.values());
        model.addAttribute("allowedDomain", allowedDomain);
        model.addAttribute("adminCount", userRepository.countByRole(Role.ADMIN));
        model.addAttribute("maxAdmins", MAX_ADMINS);
        return "admin/users";
    }

    @PostMapping("/{id}/role")
    @Transactional
    public String changeRole(@PathVariable Long id,
                              @RequestParam Role role,
                              @RequestParam(required = false) String confirmPassword,
                              @AuthenticationPrincipal EacUserDetails principal,
                              RedirectAttributes redirectAttributes) {
        AppUser actingAdmin = requireSelf(principal);
        AppUser target = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        boolean isSelf = actingAdmin.getId().equals(target.getId());
        if (isSelf && target.getRole() == Role.ADMIN && role != Role.ADMIN
                && userRepository.countByRole(Role.ADMIN) <= 1) {
            // Never let the last admin demote themselves - that would lock
            // everyone out of role/approval management with no way back in
            // short of a direct database edit.
            redirectAttributes.addFlashAttribute("errorMessage",
                    "You're the only admin - promote someone else to ADMIN before changing your own role.");
            return "redirect:/admin/users";
        }

        if (role == Role.ADMIN && target.getRole() != Role.ADMIN) {
            if (userRepository.countByRole(Role.ADMIN) >= MAX_ADMINS) {
                redirectAttributes.addFlashAttribute("errorMessage",
                        "Cannot promote " + target.getEmail() + " - the system-wide limit of " + MAX_ADMINS + " admins has been reached.");
                return "redirect:/admin/users";
            }
            String passwordError = verifyOwnPassword(actingAdmin, confirmPassword);
            if (passwordError != null) {
                redirectAttributes.addFlashAttribute("errorMessage", passwordError);
                return "redirect:/admin/users";
            }
        }

        Role previousRole = target.getRole();
        boolean roleActuallyChanged = previousRole != role;
        target.setRole(role);
        userRepository.save(target);

        if (roleActuallyChanged) {
            notificationService.roleChanged(target, role.name());
            auditService.roleChanged(actingAdmin, target, previousRole.name(), role.name());
        }

        redirectAttributes.addFlashAttribute("infoMessage",
                target.getEmail() + " is now " + role.name() + ".");
        return "redirect:/admin/users";
    }

    @PostMapping("/create")
    @Transactional
    public String create(@RequestParam String fullName,
                          @RequestParam String email,
                          @RequestParam String password,
                          @RequestParam Role role,
                          @RequestParam(required = false) String confirmPassword,
                          @AuthenticationPrincipal EacUserDetails principal,
                          RedirectAttributes redirectAttributes) {
        AppUser actingAdmin = requireSelf(principal);

        String normalizedEmail = email.trim().toLowerCase();
        if (!normalizedEmail.endsWith("@" + allowedDomain)) {
            redirectAttributes.addFlashAttribute("errorMessage", "Only @" + allowedDomain + " addresses can be created.");
            return "redirect:/admin/users";
        }
        if (password.length() < 8) {
            redirectAttributes.addFlashAttribute("errorMessage", "Password must be at least 8 characters.");
            return "redirect:/admin/users";
        }
        if (userRepository.findByEmail(normalizedEmail).isPresent()) {
            redirectAttributes.addFlashAttribute("errorMessage", "An account with this email already exists.");
            return "redirect:/admin/users";
        }

        if (role == Role.ADMIN) {
            if (userRepository.countByRole(Role.ADMIN) >= MAX_ADMINS) {
                redirectAttributes.addFlashAttribute("errorMessage",
                        "Cannot create another admin - the system-wide limit of " + MAX_ADMINS + " admins has been reached.");
                return "redirect:/admin/users";
            }
            String passwordError = verifyOwnPassword(actingAdmin, confirmPassword);
            if (passwordError != null) {
                redirectAttributes.addFlashAttribute("errorMessage", passwordError);
                return "redirect:/admin/users";
            }
        }

        // Pre-verified: admin-created accounts (e.g. dummy/test accounts
        // with no real inbox) skip the emailed verification link entirely -
        // see AppUser.registerManually + RegistrationController for the
        // normal self-registration path this bypasses.
        AppUser user = AppUser.registerManually(normalizedEmail, fullName.trim(), passwordEncoder.encode(password));
        user.setEmailVerified(true);
        user.setRole(role);
        userRepository.save(user);

        auditService.accountCreatedByAdmin(actingAdmin, user, role.name());

        redirectAttributes.addFlashAttribute("infoMessage",
                "Account created for " + user.getEmail() + " (" + role.name() + ", pre-verified).");
        return "redirect:/admin/users";
    }

    /** Confirms the acting admin's own current password - required before granting ADMIN, either via promotion or direct creation. Rate-limited per acting-admin-account so this can't be used to brute-force a signed-in admin's password. */
    private String verifyOwnPassword(AppUser actingAdmin, String confirmPassword) {
        String rateLimitKey = "admin-password-confirm:" + actingAdmin.getId();
        if (!rateLimiter.allow(rateLimitKey, MAX_PASSWORD_CONFIRM_ATTEMPTS, PASSWORD_CONFIRM_WINDOW)) {
            return "Too many incorrect attempts. Please wait before trying again.";
        }
        if (confirmPassword == null || confirmPassword.isBlank()) {
            return "Re-enter your password to confirm granting ADMIN access.";
        }
        if (!passwordEncoder.matches(confirmPassword, actingAdmin.getPasswordHash())) {
            return "Incorrect password - ADMIN was not granted.";
        }
        return null;
    }

    private AppUser requireSelf(EacUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return principal.getAppUser();
    }
}
