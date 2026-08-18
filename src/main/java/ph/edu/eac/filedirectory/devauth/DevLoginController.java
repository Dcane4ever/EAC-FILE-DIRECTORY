package ph.edu.eac.filedirectory.devauth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ph.edu.eac.filedirectory.security.EacOAuth2User;
import ph.edu.eac.filedirectory.user.AppUser;
import ph.edu.eac.filedirectory.user.AppUserRepository;
import ph.edu.eac.filedirectory.user.Role;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TEST-ONLY login bypass. Only registered when the "test" Spring profile is
 * active (see application-test.properties / --spring.profiles.active=test) -
 * never enabled in the real deployment. Lets you sign in as any
 * @eac.edu.ph address without going through Google OAuth, purely so the
 * app can be clicked through locally before real Google Cloud OAuth
 * credentials exist. The permanent login path stays SecurityConfig's real
 * Google OAuth2 flow - this class does not touch it.
 */
@Controller
@Profile("test")
public class DevLoginController {

    private final AppUserRepository userRepository;
    private final String allowedDomain;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    public DevLoginController(AppUserRepository userRepository,
                               @Value("${eac.allowed-email-domain}") String allowedDomain) {
        this.userRepository = userRepository;
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
            String fakeSub = "dev-" + UUID.randomUUID();
            String displayName = normalized.substring(0, normalized.indexOf('@'));
            return new AppUser(fakeSub, normalized, displayName, null);
        });
        user.setLastLoginAt(Instant.now());
        if (user.getId() == null) {
            user.setRole(role);
        }
        userRepository.save(user);

        var delegate = new org.springframework.security.oauth2.core.user.DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())),
                Map.of("sub", user.getGoogleSub(), "email", user.getEmail(), "name", user.getFullName()),
                "sub");
        EacOAuth2User principal = new EacOAuth2User(delegate, user);

        // Use OAuth2AuthenticationToken (not UsernamePasswordAuthenticationToken) so the
        // authenticated principal is the same shape production gets from oauth2Login() -
        // controllers resolving a plain OAuth2User method parameter depend on this.
        Authentication auth = new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google");

        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        return "redirect:/home";
    }
}
