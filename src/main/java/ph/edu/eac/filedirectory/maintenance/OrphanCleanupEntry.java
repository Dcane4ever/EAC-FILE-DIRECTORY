package ph.edu.eac.filedirectory.maintenance;

import jakarta.persistence.*;
import ph.edu.eac.filedirectory.user.AppUser;

import java.time.Instant;

/**
 * Retains the cleanup decision and its outcome without storing or changing
 * document metadata. Every deletion still needs a fresh orphan check.
 */
@Entity
@Table(name = "orphan_cleanup_entries", uniqueConstraints = @UniqueConstraint(columnNames = "stored_path"))
public class OrphanCleanupEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stored_path", nullable = false, length = 512)
    private String storedPath;

    @Column(nullable = false)
    private long sizeBytes;

    @ManyToOne(optional = false)
    @JoinColumn(name = "scheduled_by_id", nullable = false)
    private AppUser scheduledBy;

    @Column(nullable = false, updatable = false)
    private Instant scheduledAt = Instant.now();

    @Column(nullable = false)
    private Instant eligibleAt;

    @Column(nullable = false, length = 400)
    private String reason;

    @Column(nullable = false, length = 255)
    private String backupReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrphanCleanupStatus status = OrphanCleanupStatus.SCHEDULED;

    private Instant completedAt;

    @ManyToOne
    @JoinColumn(name = "completed_by_id")
    private AppUser completedBy;

    @Column(length = 400)
    private String completionNote;

    protected OrphanCleanupEntry() {
    }

    public OrphanCleanupEntry(String storedPath, long sizeBytes, AppUser scheduledBy, Instant eligibleAt,
                              String reason, String backupReference) {
        this.storedPath = storedPath;
        this.sizeBytes = sizeBytes;
        this.scheduledBy = scheduledBy;
        this.eligibleAt = eligibleAt;
        this.reason = reason;
        this.backupReference = backupReference;
    }

    public Long getId() { return id; }
    public String getStoredPath() { return storedPath; }
    public long getSizeBytes() { return sizeBytes; }
    public AppUser getScheduledBy() { return scheduledBy; }
    public Instant getScheduledAt() { return scheduledAt; }
    public Instant getEligibleAt() { return eligibleAt; }
    public String getReason() { return reason; }
    public String getBackupReference() { return backupReference; }
    public OrphanCleanupStatus getStatus() { return status; }
    public Instant getCompletedAt() { return completedAt; }
    public String getCompletionNote() { return completionNote; }

    public void cancel(AppUser actor, String note) {
        status = OrphanCleanupStatus.CANCELLED;
        completedBy = actor;
        completedAt = Instant.now();
        completionNote = note;
    }

    public void complete(AppUser actor, String note) {
        status = OrphanCleanupStatus.COMPLETED;
        completedBy = actor;
        completedAt = Instant.now();
        completionNote = note;
    }
}
