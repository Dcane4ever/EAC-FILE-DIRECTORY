package ph.edu.eac.filedirectory.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {
    Optional<PasswordResetToken> findByToken(String token);

    /** Most recent still-live (unused, unexpired) token for a user, if any - used to throttle repeated reset requests instead of issuing a fresh token every time. */
    Optional<PasswordResetToken> findFirstByUserAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(AppUser user, Instant now);
}
