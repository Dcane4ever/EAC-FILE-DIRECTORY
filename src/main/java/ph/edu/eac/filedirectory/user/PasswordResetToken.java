package ph.edu.eac.filedirectory.user;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A one-time link sent to an account's email address to let them set a new
 * password without knowing the old one. Mirrors EmailVerificationToken's
 * shape deliberately - same random-token/expiry/used-flag pattern, see
 * PasswordResetController. Visiting /reset-password?token=... with a
 * matching, unexpired, unused token lets the holder set a new password,
 * after which the token is marked used.
 */
@Entity
@Table(name = "password_reset_tokens")
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean used = false;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected PasswordResetToken() {
        // JPA
    }

    public PasswordResetToken(String token, AppUser user, Instant expiresAt) {
        this.token = token;
        this.user = user;
        this.expiresAt = expiresAt;
    }

    public boolean isValid() {
        return !used && Instant.now().isBefore(expiresAt);
    }

    public Long getId() {
        return id;
    }

    public String getToken() {
        return token;
    }

    public AppUser getUser() {
        return user;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isUsed() {
        return used;
    }

    public void setUsed(boolean used) {
        this.used = used;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
