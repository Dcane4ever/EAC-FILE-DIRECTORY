package ph.edu.eac.filedirectory.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;
import ph.edu.eac.filedirectory.audit.AuditService;
import ph.edu.eac.filedirectory.security.ratelimit.RateLimiter;
import ph.edu.eac.filedirectory.user.AppUser;
import ph.edu.eac.filedirectory.user.AppUserRepository;

import java.time.Duration;
import java.time.Instant;

/**
 * Authenticates the /login (form) path against AppUser.passwordHash,
 * restricted to @eac.edu.ph addresses. Rate-limited per email (not per IP -
 * the account being targeted is what matters for brute-force protection
 * here, and this runs inside the AuthenticationProvider itself so the limit
 * applies regardless of which login page/URL the attempt came through).
 * Every outcome (success or failure, and why) is written to the audit log
 * via AuditService - see AuditAction.LOGIN_SUCCESS/LOGIN_FAILURE.
 */
@Component
public class PasswordAuthenticationProvider implements AuthenticationProvider {

    private static final int MAX_ATTEMPTS = 10;
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RateLimiter rateLimiter;
    private final AuditService auditService;
    private final String allowedDomain;

    public PasswordAuthenticationProvider(AppUserRepository userRepository,
                                           PasswordEncoder passwordEncoder,
                                           RateLimiter rateLimiter,
                                           AuditService auditService,
                                           @Value("${eac.allowed-email-domain}") String allowedDomain) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
        this.auditService = auditService;
        this.allowedDomain = allowedDomain.toLowerCase();
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String email = authentication.getName().trim().toLowerCase();
        String rawPassword = String.valueOf(authentication.getCredentials());
        String ipAddress = ipAddressOf(authentication);

        if (!email.endsWith("@" + allowedDomain)) {
            auditService.loginFailure(email, "Wrong email domain", ipAddress);
            throw new BadCredentialsException("Only @" + allowedDomain + " accounts may sign in.");
        }

        String rateLimitKey = "login:" + email;
        if (!rateLimiter.allow(rateLimitKey, MAX_ATTEMPTS, WINDOW)) {
            long retryAfterSeconds = rateLimiter.secondsUntilAllowed(rateLimitKey, MAX_ATTEMPTS, WINDOW);
            auditService.loginFailure(email, "Rate limited", ipAddress);
            throw new LockedException("Too many sign-in attempts for this account. Try again in "
                    + Math.max(1, retryAfterSeconds / 60 + 1) + " minute(s).");
        }

        AppUser user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            auditService.loginFailure(email, "Unknown account", ipAddress);
            throw new BadCredentialsException("Invalid email or password.");
        }

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            auditService.loginFailure(email, "Wrong password", ipAddress);
            throw new BadCredentialsException("Invalid email or password.");
        }
        if (!user.isEmailVerified()) {
            auditService.loginFailure(email, "Email not verified", ipAddress);
            throw new DisabledException("Please verify your email before signing in - check your inbox for the verification link.");
        }

        user.setLastLoginAt(Instant.now());
        userRepository.save(user);
        auditService.loginSuccess(user, ipAddress);

        EacUserDetails principal = new EacUserDetails(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private String ipAddressOf(Authentication authentication) {
        if (authentication.getDetails() instanceof WebAuthenticationDetails details) {
            return details.getRemoteAddress();
        }
        return null;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
