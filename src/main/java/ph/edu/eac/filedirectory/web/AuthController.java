package ph.edu.eac.filedirectory.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.web.WebAttributes;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AuthController {

    @GetMapping("/login")
    public String login(@RequestParam(value = "error", required = false) String error,
                         @RequestParam(value = "logout", required = false) String logout,
                         HttpServletRequest request,
                         Model model) {
        if (error != null) {
            model.addAttribute("errorMessage", errorMessageFor(request));
        }
        if (logout != null) {
            model.addAttribute("toastMessage", "Successfully logged out");
        }
        return "auth/login";
    }

    /**
     * Spring Security's formLogin doesn't distinguish the exact
     * AuthenticationException reason from the redirect URL alone (bad
     * password, unverified email, wrong domain, and rate-limited all land
     * on /login?error identically) - but it does stash the actual exception
     * in the session under WebAttributes.AUTHENTICATION_EXCEPTION, which is
     * readable here. Only the rate-limit case gets a specific message
     * (users need to know to wait, not just retry immediately); every other
     * failure reason stays deliberately generic so a failed attempt can't
     * be used to enumerate which part was wrong (email vs password vs
     * verification state).
     */
    private String errorMessageFor(HttpServletRequest request) {
        Object sessionException = request.getSession(false) == null ? null
                : request.getSession(false).getAttribute(WebAttributes.AUTHENTICATION_EXCEPTION);
        if (sessionException instanceof LockedException locked) {
            return locked.getMessage();
        }
        return "Sign-in failed. Check your email/password, that your email is verified, and that you're using an @eac.edu.ph account.";
    }
}
