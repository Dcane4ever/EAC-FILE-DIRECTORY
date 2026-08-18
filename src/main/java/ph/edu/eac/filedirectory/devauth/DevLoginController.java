package ph.edu.eac.filedirectory.devauth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ph.edu.eac.filedirectory.security.EacUserDetails;
import ph.edu.eac.filedirectory.user.AppUser;
import ph.edu.eac.filedirectory.user.AppUserRepository;
import ph.edu.eac.filedirectory.user.Role;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.time.Instant;

/**
 * TEST-ONLY login bypass. Only registered when the "test" Spring profile is
 * active (see application-test.properties / --spring.profiles.active=test) -
 * never enabled in the real deployment. Lets you sign in as any
 * @eac.edu.ph address (auto-verified, no real password needed) purely so
 * the app can be clicked through locally without going through the real
 * registration + email verification flow every time. The permanent login
 * path stays SecurityConfig's real email+password flow - this class does
 * not touch it.
 */
@Controller
@Profile("test")
public class DevLoginController {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String allowedDomain;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    public DevLoginController(AppUserRepository userRepository,
                               PasswordEncoder passwordEncoder,
                               @Value("${eac.allowed-email-domain}") String allowedDomain) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.allowedDomain = allowedDomain.toLowerCase();
    }

    @GetMapping("/dev-login")
    public String form(Model model) {
        model.addAttribute("allowedDomain", allowedDomain);
        return "dev-login";
    }

    @PostMapping("/dev-login")
    public String login(@RequestParam String email,
                         @RequestParam(defaultValue = "USER") Role role,
                         HttpServletRequest request,
                         HttpServletResponse response,
                         Model model) {
        String normalized = email.trim().toLowerCase();
        if (!normalized.endsWith("@" + allowedDomain)) {
            model.addAttribute("allowedDomain", allowedDomain);
            model.addAttribute("errorMessage", "Only @" + allowedDomain + " addresses are accepted.");
            return "dev-login";
        }

        AppUser user = userRepository.findByEmail(normalized).orElseGet(() -> {
            String displayName = normalized.substring(0, normalized.indexOf('@'));
            AppUser created = AppUser.registerManually(normalized, displayName, passwordEncoder.encode("dev-login-placeholder"));
            created.setEmailVerified(true);
            return created;
        });
        user.setLastLoginAt(Instant.now());
        if (user.getId() == null) {
            user.setRole(role);
        }
        userRepository.save(user);

        EacUserDetails principal = new EacUserDetails(user);
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        ((UsernamePasswordAuthenticationToken) auth).setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        // Match RoleBasedLoginSuccessHandler's real-login behavior: staff
        // land on the admin dashboard, everyone else on the student home.
        boolean isStaff = user.getRole() == Role.ADMIN || user.getRole() == Role.MODERATOR;
        return isStaff ? "redirect:/admin/home" : "redirect:/home";
    }
}
