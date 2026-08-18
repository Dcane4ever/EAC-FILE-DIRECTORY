package ph.edu.eac.filedirectory.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ph.edu.eac.filedirectory.user.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Account registration, restricted to @eac.edu.ph. New accounts start
 * unverified; /verify confirms the emailed link before password login is
 * allowed - see PasswordAuthenticationProvider.
 */
@Controller
public class RegistrationController {

    private final AppUserRepository userRepository;
    private final EmailVerificationTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final MailService mailService;
    private final String allowedDomain;
    private final long tokenTtlHours;

    public RegistrationController(AppUserRepository userRepository,
                                   EmailVerificationTokenRepository tokenRepository,
                                   PasswordEncoder passwordEncoder,
                                   MailService mailService,
                                   @Value("${eac.allowed-email-domain}") String allowedDomain,
                                   @Value("${eac.mail.verification-token-ttl-hours}") long tokenTtlHours) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.mailService = mailService;
        this.allowedDomain = allowedDomain.toLowerCase();
        this.tokenTtlHours = tokenTtlHours;
    }

    @GetMapping("/register")
    public String form(Model model) {
        model.addAttribute("allowedDomain", allowedDomain);
        return "auth/register";
    }

    @PostMapping("/register")
    public String submit(@RequestParam String fullName,
                          @RequestParam String email,
                          @RequestParam String password,
                          @RequestParam String confirmPassword,
                          Model model) {
        model.addAttribute("allowedDomain", allowedDomain);
        model.addAttribute("fullName", fullName);
        model.addAttribute("email", email);

        String normalizedEmail = email.trim().toLowerCase();

        if (!normalizedEmail.endsWith("@" + allowedDomain)) {
            model.addAttribute("errorMessage", "Only @" + allowedDomain + " addresses can register.");
            return "auth/register";
        }
        if (password.length() < 8) {
            model.addAttribute("errorMessage", "Password must be at least 8 characters.");
            return "auth/register";
        }
        if (!password.equals(confirmPassword)) {
            model.addAttribute("errorMessage", "Passwords do not match.");
            return "auth/register";
        }
        if (userRepository.findByEmail(normalizedEmail).isPresent()) {
            model.addAttribute("errorMessage", "An account with this email already exists. Try signing in instead.");
            return "auth/register";
        }

        AppUser user = AppUser.registerManually(normalizedEmail, fullName.trim(), passwordEncoder.encode(password));
        userRepository.save(user);

        String token = UUID.randomUUID().toString().replace("-", "");
        Instant expiresAt = Instant.now().plus(tokenTtlHours, ChronoUnit.HOURS);
        tokenRepository.save(new EmailVerificationToken(token, user, expiresAt));

        mailService.sendVerificationEmail(user.getEmail(), user.getFullName(), token);

        model.addAttribute("infoMessage",
                "Account created. Check " + user.getEmail() + " for a verification link before signing in.");
        return "auth/register";
    }

    @GetMapping("/verify")
    public String verify(@RequestParam String token, Model model) {
        var tokenEntity = tokenRepository.findByToken(token).orElse(null);

        if (tokenEntity == null || !tokenEntity.isValid()) {
            model.addAttribute("errorMessage", "This verification link is invalid or has expired. Please register again or contact an administrator.");
            return "auth/login";
        }

        AppUser user = tokenEntity.getUser();
        user.setEmailVerified(true);
        userRepository.save(user);

        tokenEntity.setUsed(true);
        tokenRepository.save(tokenEntity);

        model.addAttribute("infoMessage", "Email verified. You can now sign in.");
        return "auth/login";
    }
}
