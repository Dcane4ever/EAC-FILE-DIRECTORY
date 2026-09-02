package ph.edu.eac.filedirectory.share;

import jakarta.persistence.*;
import ph.edu.eac.filedirectory.file.FileEntity;
import ph.edu.eac.filedirectory.user.AppUser;

import java.time.Instant;

/** A direct, revocable download permission for one verified EAC recipient. */
@Entity
@Table(name = "file_shares", uniqueConstraints = @UniqueConstraint(columnNames = {"file_id", "recipient_id"}))
public class FileShare {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "file_id", nullable = false)
    private FileEntity file;

    @ManyToOne(optional = false)
    @JoinColumn(name = "recipient_id", nullable = false)
    private AppUser recipient;

    @ManyToOne(optional = false)
    @JoinColumn(name = "shared_by_id", nullable = false)
    private AppUser sharedBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private Instant revokedAt;

    @ManyToOne
    @JoinColumn(name = "revoked_by_id")
    private AppUser revokedBy;

    protected FileShare() {
    }

    public FileShare(FileEntity file, AppUser recipient, AppUser sharedBy) {
        this.file = file;
        this.recipient = recipient;
        this.sharedBy = sharedBy;
    }

    public Long getId() { return id; }
    public FileEntity getFile() { return file; }
    public AppUser getRecipient() { return recipient; }
    public AppUser getSharedBy() { return sharedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public boolean isActive() { return revokedAt == null; }

    public void restore(AppUser actor) {
        sharedBy = actor;
        revokedAt = null;
        revokedBy = null;
    }

    public void revoke(AppUser actor) {
        revokedAt = Instant.now();
        revokedBy = actor;
    }
}
