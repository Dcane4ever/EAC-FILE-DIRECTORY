package ph.edu.eac.filedirectory.access;

import jakarta.persistence.*;
import ph.edu.eac.filedirectory.file.FileEntity;
import ph.edu.eac.filedirectory.user.AppUser;

import java.time.Instant;

/**
 * One person's request to download a file they don't already have direct
 * access to - see AccessRequestService for the full state machine
 * (PENDING -> APPROVED/DENIED, or EXPIRED once an approved grant's window
 * lapses unused). The uploader decides by default; a moderator/admin may
 * decide instead only after MODERATOR_FALLBACK_DAYS have passed with no
 * uploader response AND SystemSetting.allowModeratorAccessRequestFallback
 * is on - see AccessRequestService.canModeratorDecide(). decidedBy records
 * whichever of those two actually made the call, for the audit trail.
 */
@Entity
@Table(name = "access_requests")
public class AccessRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "file_id", nullable = false)
    private FileEntity file;

    @ManyToOne(optional = false)
    @JoinColumn(name = "requester_id", nullable = false)
    private AppUser requester;

    // length=32 so this maps as VARCHAR, not a native MySQL ENUM - see
    // AuditEvent.action's comment.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AccessRequestStatus status = AccessRequestStatus.PENDING;

    @ManyToOne
    @JoinColumn(name = "decided_by")
    private AppUser decidedBy;

    private Instant decidedAt;

    /** Denial reason, or null - mirrors FileEntity.rejectionReason's shape/purpose. */
    @Column(length = 500)
    private String decisionNote;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected AccessRequest() {
        // JPA
    }

    public AccessRequest(FileEntity file, AppUser requester) {
        this.file = file;
        this.requester = requester;
    }

    public Long getId() {
        return id;
    }

    public FileEntity getFile() {
        return file;
    }

    public AppUser getRequester() {
        return requester;
    }

    public AccessRequestStatus getStatus() {
        return status;
    }

    public void setStatus(AccessRequestStatus status) {
        this.status = status;
    }

    public AppUser getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(AppUser decidedBy) {
        this.decidedBy = decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }

    public String getDecisionNote() {
        return decisionNote;
    }

    public void setDecisionNote(String decisionNote) {
        this.decisionNote = decisionNote;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
