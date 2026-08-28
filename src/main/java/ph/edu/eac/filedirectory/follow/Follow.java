package ph.edu.eac.filedirectory.follow;

import jakarta.persistence.*;
import ph.edu.eac.filedirectory.user.AppUser;

import java.time.Instant;

/** A user's subscription to one department, tag, or uploader. */
@Entity
@Table(name = "follows", uniqueConstraints = @UniqueConstraint(
        name = "uk_follows_follower_type_target", columnNames = {"follower_id", "type", "target_id"}))
public class Follow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "follower_id", nullable = false)
    private AppUser follower;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private FollowType type;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Follow() {
        // JPA
    }

    public Follow(AppUser follower, FollowType type, Long targetId) {
        this.follower = follower;
        this.type = type;
        this.targetId = targetId;
    }

    public Long getId() { return id; }
    public AppUser getFollower() { return follower; }
    public FollowType getType() { return type; }
    public Long getTargetId() { return targetId; }
    public Instant getCreatedAt() { return createdAt; }
}
