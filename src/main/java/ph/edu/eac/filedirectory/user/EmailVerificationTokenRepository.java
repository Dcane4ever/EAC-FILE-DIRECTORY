package ph.edu.eac.filedirectory.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, Long> {
    Optional<EmailVerificationToken> findByToken(String token);

    /** Most recent still-live (unused, unexpired) token for a user, if any - used to throttle resend requests instead of issuing a fresh token every time. */
    Optional<EmailVerificationToken> findFirstByUserAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(AppUser user, Instant now);
}
