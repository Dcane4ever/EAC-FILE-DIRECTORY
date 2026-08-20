package ph.edu.eac.filedirectory.notification;

import jakarta.persistence.*;
import ph.edu.eac.filedirectory.user.AppUser;

import java.time.Instant;

/**
 * An in-app notification for one recipient - see NotificationService for how
 * these get created (upload approved/rejected, role changed today; more
 * trigger points can be added later, e.g. access-request updates once that
 * feature exists, without changing this shape). Deliberately a single flat
 * entity rather than one subclass per event type - message is a plain,
 * already-formatted string decided at creation time, and targetUrl (if set)
 * is where clicking the notification should take the user.
 */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "recipient_id", nullable = false)
    private AppUser recipient;

    // length=32 so this maps as VARCHAR, not a native MySQL ENUM - see
    // AuditEvent.action's comment. This one matters most: any future
    // NotificationType constant would otherwise write cleanly today (the
    // ENUM is freshly created with today's values) but silently truncate
    // the moment a new constant is added later, exactly like AuditAction
    // just did.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private NotificationType type;

    @Column(nullable = false, length = 500)
    private String message;

    /** Where clicking the notification should navigate to, e.g. /files/{id}. Null if there's nowhere useful to send them. */
    @Column(length = 300)
    private String targetUrl;

    // Explicit column name: "read" is a reserved word in MySQL 8 (used in
    // its locking-read clause syntax), so the unquoted default column name
    // broke CREATE TABLE against real MySQL - see the "you have an error in
    // your SQL syntax ... near 'read bit not null'" failure this caused.
    // H2 (used in tests) tolerated it, which is why this went unnoticed
    // until running against a real MySQL schema.
    @Column(name = "is_read", nullable = false)
    private boolean read = false;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Notification() {
        // JPA
    }

    public Notification(AppUser recipient, NotificationType type, String message, String targetUrl) {
        this.recipient = recipient;
        this.type = type;
        this.message = message;
        this.targetUrl = targetUrl;
    }

    public Long getId() {
        return id;
    }

    public AppUser getRecipient() {
        return recipient;
    }

    public NotificationType getType() {
        return type;
    }

    public String getMessage() {
        return message;
    }

    public String getTargetUrl() {
        return targetUrl;
    }

    public boolean isRead() {
        return read;
    }

    public void setRead(boolean read) {
        this.read = read;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
