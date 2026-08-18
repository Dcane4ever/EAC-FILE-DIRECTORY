package ph.edu.eac.filedirectory.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AuthController {

    @GetMapping("/login")
    public String login(@RequestParam(value = "error", required = false) String error,
                         @RequestParam(value = "logout", required = false) String logout,
                         Model model) {
        if (error != null) {
            // Spring Security's formLogin doesn't let us distinguish the exact
            // AuthenticationException reason from this redirect alone (bad
            // password, unverified email, wrong domain all land here).
            model.addAttribute("errorMessage",
                    "Sign-in failed. Check your email/password, that your email is verified, and that you're using an @eac.edu.ph account.");
        }
        if (logout != null) {
            model.addAttribute("infoMessage", "You have been signed out.");
        }
        return "auth/login";
    }
}
