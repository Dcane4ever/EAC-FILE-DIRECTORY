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
 * "Forgot password?" flow - request a reset link by email, then set a new
 * password via a one-time token. Mirrors RegistrationController's
 * verification-token pattern (see PasswordResetToken). Deliberately never
 * reveals whether a given email is registered - see requestReset(), so this
 * can't be used to enumerate accounts. IP-rate-limited as a complementary
 * layer on top of the existing per-account cooldown, same rationale as
 * RegistrationController's /resend-verification.
 */
@Controller
public class PasswordResetController {

    private static final int MAX_ATTEMPTS_PER_IP = 5;
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofMinutes(15);

    private final AppUserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final MailService mailService;
    private final RateLimiter rateLimiter;
    private final AuditService auditService;
    private final long tokenTtlMinutes;
    private final long resendCooldownMinutes;

    public PasswordResetController(AppUserRepository userRepository,
                                    PasswordResetTokenRepository tokenRepository,
                                    PasswordEncoder passwordEncoder,
                                    MailService mailService,
                                    RateLimiter rateLimiter,
                                    AuditService auditService,
                                    @Value("${eac.mail.password-reset-token-ttl-minutes}") long tokenTtlMinutes,
                                    @Value("${eac.mail.resend-cooldown-minutes}") long resendCooldownMinutes) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.mailService = mailService;
        this.rateLimiter = rateLimiter;
        this.auditService = auditService;
        this.tokenTtlMinutes = tokenTtlMinutes;
        this.resendCooldownMinutes = resendCooldownMinutes;
    }

    @GetMapping("/forgot-password")
    public String requestForm() {
        return "auth/forgot-password";
    }

    @PostMapping("/forgot-password")
    public String requestReset(@RequestParam String email, HttpServletRequest request, Model model) {
        String normalizedEmail = email.trim().toLowerCase();

        if (!rateLimiter.allow("forgot-password:" + ClientKey.ipOf(request), MAX_ATTEMPTS_PER_IP, RATE_LIMIT_WINDOW)) {
            model.addAttribute("errorMessage", "Too many requests from this network. Please try again later.");
            return "auth/forgot-password";
        }

        // Same message whether or not the account exists, and whether or not
        // the email actually sends - an attacker probing emails must not be
        // able to tell registered addresses from unregistered ones by
        // watching the response. Real delivery failures are still logged
        // server-side by MailService. The audit log is a separate,
        // admin-only surface (not shown to the requester) so it's fine -
        // useful, even - to record every attempt regardless of outcome.
        auditService.passwordResetRequested(normalizedEmail, ClientKey.ipOf(request));

        model.addAttribute("infoMessage",
                "If an account exists for " + normalizedEmail + ", a password reset link has been sent.");

        userRepository.findByEmail(normalizedEmail).ifPresent(user -> {
            Instant now = Instant.now();
            boolean onCooldown = tokenRepository
                    .findFirstByUserAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(user, now)
                    .map(existing -> existing.getCreatedAt().isAfter(now.minus(resendCooldownMinutes, ChronoUnit.MINUTES)))
                    .orElse(false);
            if (onCooldown) {
                // A live token was already issued moments ago - don't spam a
                // fresh email/token on every page refresh. The earlier link
                // is still valid and usable.
                return;
            }

            String token = UUID.randomUUID().toString().replace("-", "");
            tokenRepository.save(new PasswordResetToken(token, user, now.plus(tokenTtlMinutes, ChronoUnit.MINUTES)));
            mailService.sendPasswordResetEmail(user.getEmail(), user.getFullName(), token);
        });

        return "auth/forgot-password";
    }

    @GetMapping("/reset-password")
    public String resetForm(@RequestParam String token, Model model) {
        PasswordResetToken tokenEntity = tokenRepository.findByToken(token).orElse(null);
        if (tokenEntity == null || !tokenEntity.isValid()) {
            model.addAttribute("errorMessage", "This password reset link is invalid or has expired. Request a new one below.");
            return "auth/forgot-password";
        }

        model.addAttribute("token", token);
        return "auth/reset-password";
    }

    @PostMapping("/reset-password")
    public String submitReset(@RequestParam String token,
                               @RequestParam String password,
                               @RequestParam String confirmPassword,
                               HttpServletRequest request,
                               Model model) {
        PasswordResetToken tokenEntity = tokenRepository.findByToken(token).orElse(null);
        if (tokenEntity == null || !tokenEntity.isValid()) {
            model.addAttribute("errorMessage", "This password reset link is invalid or has expired. Request a new one below.");
            return "auth/forgot-password";
        }

        model.addAttribute("token", token);
        if (password.length() < 8) {
            model.addAttribute("errorMessage", "Password must be at least 8 characters.");
            return "auth/reset-password";
        }
        if (!password.equals(confirmPassword)) {
            model.addAttribute("errorMessage", "Passwords do not match.");
            return "auth/reset-password";
        }

        AppUser user = tokenEntity.getUser();
        user.setPasswordHash(passwordEncoder.encode(password));
        userRepository.save(user);

        tokenEntity.setUsed(true);
        tokenRepository.save(tokenEntity);

        auditService.passwordResetCompleted(user, ClientKey.ipOf(request));

        model.addAttribute("infoMessage", "Password updated. You can now sign in with your new password.");
        return "auth/login";
    }
}
