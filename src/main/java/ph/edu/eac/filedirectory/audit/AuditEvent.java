package ph.edu.eac.filedirectory.audit;

import jakarta.persistence.*;
import ph.edu.eac.filedirectory.user.AppUser;

import java.time.Instant;

/**
 * One row per audited event - see AuditService for how these get written
 * and AuditLogController for the admin-facing review page. Append-only by
 * convention: nothing in this codebase ever updates or deletes a row once
 * written (no setters beyond construction, no repository delete/update
 * methods), which is the whole point of an audit trail - see the class
 * comment on AuditService for what "append-only" actually means/doesn't
 * mean here.
 *
 * actor is nullable because some events have no authenticated actor yet
 * (a failed login attempt, for instance) - actorEmail is captured
 * separately and always populated when known, specifically so a failed
 * login against a real account is still traceable to that account even
 * though "actor" (the AppUser FK) can't be set for an attempt that never
 * authenticated.
 */
@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "actor_id")
    private AppUser actor;

    /** The email associated with the actor, captured even when actor (the FK) is null - e.g. a failed login attempt against an email that may or may not be a real account. */
    @Column(length = 255)
    private String actorEmail;

    // length=64 (not the default, unbounded) so Hibernate maps this as a
    // plain VARCHAR rather than a native MySQL ENUM(...) - a native ENUM's
    // allowed values get locked in at CREATE TABLE time from whatever
    // AuditAction constants existed then, and ddl-auto=update never widens
    // an existing column's ENUM value list when new constants are added
    // later. That silently broke every write of a Phase 9 access-request
    // audit event ("Data truncated for column 'action'") since those
    // constants didn't exist when this table was first created against
    // real MySQL. VARCHAR has no such fixed value list, so this can't
    // recur the same way.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private AuditTargetType targetType;

    private Long targetId;

    /** State before the change, where applicable (e.g. a role name) - null if not applicable to this action. */
    @Column(length = 255)
    private String previousValue;

    /** State after the change, where applicable - null if not applicable to this action. */
    @Column(length = 255)
    private String newValue;

    /** Why, where applicable - e.g. a rejection reason. Null if not applicable. */
    @Column(length = 500)
    private String reason;

    /** Free-form extra context (e.g. file title, department name) - kept short and human-readable, not a JSON blob, so the log page can just print it. */
    @Column(length = 500)
    private String metadata;

    @Column(length = 64)
    private String ipAddress;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected AuditEvent() {
        // JPA
    }

    private AuditEvent(Builder builder) {
        this.actor = builder.actor;
        this.actorEmail = builder.actorEmail;
        this.action = builder.action;
        this.targetType = builder.targetType == null ? AuditTargetType.NONE : builder.targetType;
        this.targetId = builder.targetId;
        this.previousValue = builder.previousValue;
        this.newValue = builder.newValue;
        this.reason = builder.reason;
        this.metadata = builder.metadata;
        this.ipAddress = builder.ipAddress;
    }

    public static Builder builder(AuditAction action) {
        return new Builder(action);
    }

    /** Fluent construction - AuditEvent has enough optional fields (previousValue, newValue, reason, metadata, ip...) that a long constructor would be unreadable at call sites. */
    public static class Builder {
        private final AuditAction action;
        private AppUser actor;
        private String actorEmail;
        private AuditTargetType targetType;
        private Long targetId;
        private String previousValue;
        private String newValue;
        private String reason;
        private String metadata;
        private String ipAddress;

        private Builder(AuditAction action) {
            this.action = action;
        }

        public Builder actor(AppUser actor) {
            this.actor = actor;
            this.actorEmail = actor == null ? this.actorEmail : actor.getEmail();
            return this;
        }

        public Builder actorEmail(String actorEmail) {
            this.actorEmail = actorEmail;
            return this;
        }

        public Builder target(AuditTargetType targetType, Long targetId) {
            this.targetType = targetType;
            this.targetId = targetId;
            return this;
        }

        public Builder previousValue(String previousValue) {
            this.previousValue = previousValue;
            return this;
        }

        public Builder newValue(String newValue) {
            this.newValue = newValue;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder metadata(String metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder ipAddress(String ipAddress) {
            this.ipAddress = ipAddress;
            return this;
        }

        public AuditEvent build() {
            return new AuditEvent(this);
        }
    }

    public Long getId() {
        return id;
    }

    public AppUser getActor() {
        return actor;
    }

    public String getActorEmail() {
        return actorEmail;
    }

    public AuditAction getAction() {
        return action;
    }

    public AuditTargetType getTargetType() {
        return targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public String getPreviousValue() {
        return previousValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public String getReason() {
        return reason;
    }

    public String getMetadata() {
        return metadata;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
