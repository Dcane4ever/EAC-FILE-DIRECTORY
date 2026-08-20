package ph.edu.eac.filedirectory.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ph.edu.eac.filedirectory.audit.AuditService;
import ph.edu.eac.filedirectory.security.ratelimit.ClientKey;
import ph.edu.eac.filedirectory.security.ratelimit.RateLimiter;
import ph.edu.eac.filedirectory.user.*;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Account registration, restricted to @eac.edu.ph. New accounts start
 * unverified; /verify confirms the emailed link before password login is
 * allowed - see PasswordAuthenticationProvider. /register and
 * /resend-verification are IP-rate-limited (see RateLimiter) as a
 * complementary layer on top of resend-verification's existing per-account
 * cooldown - that cooldown alone doesn't stop someone spraying many
 * different @eac.edu.ph addresses from one source.
 */
@Controller
public class RegistrationController {

    private static final int MAX_REGISTER_ATTEMPTS_PER_IP = 5;
    private static final int MAX_RESEND_ATTEMPTS_PER_IP = 5;
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofMinutes(15);

    private final AppUserRepository userRepository;
    private final EmailVerificationTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final MailService mailService;
    private final RateLimiter rateLimiter;
    private final AuditService auditService;
    private final String allowedDomain;
    private final long tokenTtlHours;
    private final long resendCooldownMinutes;

    public RegistrationController(AppUserRepository userRepository,
                                   EmailVerificationTokenRepository tokenRepository,
                                   PasswordEncoder passwordEncoder,
                                   MailService mailService,
                                   RateLimiter rateLimiter,
                                   AuditService auditService,
                                   @Value("${eac.allowed-email-domain}") String allowedDomain,
                                   @Value("${eac.mail.verification-token-ttl-hours}") long tokenTtlHours,
                                   @Value("${eac.mail.resend-cooldown-minutes}") long resendCooldownMinutes) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.mailService = mailService;
        this.rateLimiter = rateLimiter;
        this.auditService = auditService;
        this.allowedDomain = allowedDomain.toLowerCase();
        this.tokenTtlHours = tokenTtlHours;
        this.resendCooldownMinutes = resendCooldownMinutes;
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
                          HttpServletRequest request,
                          Model model) {
        model.addAttribute("allowedDomain", allowedDomain);
        model.addAttribute("fullName", fullName);
        model.addAttribute("email", email);

        if (!rateLimiter.allow("register:" + ClientKey.ipOf(request), MAX_REGISTER_ATTEMPTS_PER_IP, RATE_LIMIT_WINDOW)) {
            model.addAttribute("errorMessage", "Too many registration attempts from this network. Please try again later.");
            return "auth/register";
        }

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
            model.addAttribute("errorMessage", "This verification link is invalid or has expired. Request a new one below.");
            return "auth/resend-verification";
        }

        AppUser user = tokenEntity.getUser();
        user.setEmailVerified(true);
        userRepository.save(user);

        tokenEntity.setUsed(true);
        tokenRepository.save(tokenEntity);

        auditService.accountVerified(user);

        model.addAttribute("infoMessage", "Email verified. You can now sign in.");
        return "auth/login";
    }

    @GetMapping("/resend-verification")
    public String resendForm() {
        return "auth/resend-verification";
    }

    @PostMapping("/resend-verification")
    public String resendVerification(@RequestParam String email, HttpServletRequest request, Model model) {
        String normalizedEmail = email.trim().toLowerCase();

        if (!rateLimiter.allow("resend-verification:" + ClientKey.ipOf(request), MAX_RESEND_ATTEMPTS_PER_IP, RATE_LIMIT_WINDOW)) {
            // Same generic wording style as the anti-enumeration message
            // below - still doesn't reveal anything about the account, just
            // asks them to slow down.
            model.addAttribute("errorMessage", "Too many requests from this network. Please try again later.");
            return "auth/resend-verification";
        }

        // Same response whether or not the account exists, is already
        // verified, or the email send fails - mirrors PasswordResetController's
        // anti-enumeration approach, for the same reason.
        model.addAttribute("infoMessage",
                "If an unverified account exists for " + normalizedEmail + ", a new verification link has been sent.");

        userRepository.findByEmail(normalizedEmail).ifPresent(user -> {
            if (user.isEmailVerified()) {
                return;
            }
            Instant now = Instant.now();
            boolean onCooldown = tokenRepository
                    .findFirstByUserAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(user, now)
                    .map(existing -> existing.getCreatedAt().isAfter(now.minus(resendCooldownMinutes, ChronoUnit.MINUTES)))
                    .orElse(false);
            if (onCooldown) {
                // A live token was already issued moments ago - the earlier
                // link still works, so don't spam a new email/token.
                return;
            }

            String token = UUID.randomUUID().toString().replace("-", "");
            tokenRepository.save(new EmailVerificationToken(token, user, now.plus(tokenTtlHours, ChronoUnit.HOURS)));
            mailService.sendVerificationEmail(user.getEmail(), user.getFullName(), token, true);
        });

        return "auth/resend-verification";
    }
}
