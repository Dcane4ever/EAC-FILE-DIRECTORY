package ph.edu.eac.filedirectory.follow;

import org.springframework.data.jpa.repository.JpaRepository;
import ph.edu.eac.filedirectory.user.AppUser;

import java.util.List;

public interface FollowRepository extends JpaRepository<Follow, Long> {
    boolean existsByFollowerAndTypeAndTargetId(AppUser follower, FollowType type, Long targetId);
    List<Follow> findByTypeAndTargetId(FollowType type, Long targetId);
    List<Follow> findByFollowerOrderByCreatedAtDesc(AppUser follower);
    void deleteByFollowerAndTypeAndTargetId(AppUser follower, FollowType type, Long targetId);
}
