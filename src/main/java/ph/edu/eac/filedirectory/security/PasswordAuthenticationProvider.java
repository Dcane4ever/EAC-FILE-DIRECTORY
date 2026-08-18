package ph.edu.eac.filedirectory.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import ph.edu.eac.filedirectory.user.AppUser;
import ph.edu.eac.filedirectory.user.AppUserRepository;

import java.time.Instant;

/**
 * Authenticates the /login (form) path against AppUser.passwordHash,
 * restricted to @eac.edu.ph addresses.
 */
@Component
public class PasswordAuthenticationProvider implements AuthenticationProvider {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String allowedDomain;

    public PasswordAuthenticationProvider(AppUserRepository userRepository,
                                           PasswordEncoder passwordEncoder,
                                           @Value("${eac.allowed-email-domain}") String allowedDomain) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.allowedDomain = allowedDomain.toLowerCase();
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String email = authentication.getName().trim().toLowerCase();
        String rawPassword = String.valueOf(authentication.getCredentials());

        if (!email.endsWith("@" + allowedDomain)) {
            throw new BadCredentialsException("Only @" + allowedDomain + " accounts may sign in.");
        }

        AppUser user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password."));

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password.");
        }
        if (!user.isEmailVerified()) {
            throw new DisabledException("Please verify your email before signing in - check your inbox for the verification link.");
        }

        user.setLastLoginAt(Instant.now());
        userRepository.save(user);

        EacUserDetails principal = new EacUserDetails(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
