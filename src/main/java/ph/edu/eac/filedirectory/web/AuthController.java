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
            model.addAttribute("errorMessage",
                    "Sign-in failed. Only @eac.edu.ph accounts may access this directory.");
        }
        if (logout != null) {
            model.addAttribute("infoMessage", "You have been signed out.");
        }
        return "auth/login";
    }
}
