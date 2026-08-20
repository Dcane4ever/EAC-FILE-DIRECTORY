package ph.edu.eac.filedirectory.access;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A time-limited, single-use download permission created the moment an
 * AccessRequest is approved - see AccessRequestService.approve() and
 * FileAccessController's download endpoint. Same random-token/expiry/
 * used-flag shape as EmailVerificationToken/PasswordResetToken
 * (deliberately - see those classes), one-to-one with the AccessRequest it
 * came from. "Single-use" here means used flips true the moment the file is
 * actually streamed, not that the URL is invalidated the instant it's
 * issued - see FileAccessController's javadoc for the practical
 * implications of that (a genuinely low-stakes race, not a design gap).
 * Tied to a specific person: the download endpoint checks both this token
 * AND that the currently signed-in user is the original requester, not
 * "anyone holding the URL".
 */
@Entity
@Table(name = "access_grant_tokens")
public class AccessGrantToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @OneToOne(optional = false)
    @JoinColumn(name = "access_request_id", nullable = false, unique = true)
    private AccessRequest accessRequest;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean used = false;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected AccessGrantToken() {
        // JPA
    }

    public AccessGrantToken(String token, AccessRequest accessRequest, Instant expiresAt) {
        this.token = token;
        this.accessRequest = accessRequest;
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

    public AccessRequest getAccessRequest() {
        return accessRequest;
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
