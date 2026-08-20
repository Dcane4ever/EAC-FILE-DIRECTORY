package ph.edu.eac.filedirectory.user;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * A registered account - email + password, restricted to @eac.edu.ph (see
 * eac.allowed-email-domain / RegistrationController). New accounts start
 * unverified; emailVerified flips true once the emailed verification link
 * is clicked - see EmailVerificationToken / PasswordAuthenticationProvider.
 */
@Entity
@Table(name = "app_users")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    private String fullName;

    /** BCrypt hash. */
    @Column(nullable = false, length = 100)
    private String passwordHash;

    /** Starts false until the emailed verification link is clicked. */
    @Column(nullable = false)
    private boolean emailVerified = false;

    // length=32 so this maps as VARCHAR, not a native MySQL ENUM - see
    // AuditEvent.action's comment for why a native ENUM column is a trap
    // for any enum that gains new constants after the table already exists
    // in a real MySQL schema.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Role role = Role.USER;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private Instant lastLoginAt;

    protected AppUser() {
        // JPA
    }

    /** Registration - starts unverified until the emailed link is clicked. */
    public static AppUser registerManually(String email, String fullName, String passwordHash) {
        AppUser user = new AppUser();
        user.email = email;
        user.fullName = fullName;
        user.passwordHash = passwordHash;
        user.emailVerified = false;
        return user;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public void setEmailVerified(boolean emailVerified) {
        this.emailVerified = emailVerified;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(Instant lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }
}
