package ph.edu.eac.filedirectory.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.web.WebAttributes;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Separate admin-facing login page at /admin/login. Same accounts, same
 * password check as the student /login (both POST to the shared /login
 * processing URL - see SecurityConfig) - this is only a distinct entry
 * point/page, not a separate auth mechanism. An admin/moderator can still
 * sign in via the regular /login too; RoleBasedLoginSuccessHandler routes
 * them to /admin/home either way.
 */
@Controller
public class AdminAuthController {

    @GetMapping("/admin/login")
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
        return "auth/admin-login";
    }

    /** Same rationale as AuthController.errorMessageFor - only the rate-limit case gets a specific message. */
    private String errorMessageFor(HttpServletRequest request) {
        Object sessionException = request.getSession(false) == null ? null
                : request.getSession(false).getAttribute(WebAttributes.AUTHENTICATION_EXCEPTION);
        if (sessionException instanceof LockedException locked) {
            return locked.getMessage();
        }
        return "Sign-in failed. Check your email/password and that your email is verified.";
    }
}
