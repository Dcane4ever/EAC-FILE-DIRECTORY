package ph.edu.eac.filedirectory.follow;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ph.edu.eac.filedirectory.file.FileEntity;
import ph.edu.eac.filedirectory.notification.NotificationService;
import ph.edu.eac.filedirectory.user.AppUser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class FollowService {

    private final FollowRepository followRepository;
    private final NotificationService notificationService;

    public FollowService(FollowRepository followRepository, NotificationService notificationService) {
        this.followRepository = followRepository;
        this.notificationService = notificationService;
    }

    public boolean isFollowing(AppUser follower, FollowType type, Long targetId) {
        return follower != null && followRepository.existsByFollowerAndTypeAndTargetId(follower, type, targetId);
    }

    public List<Follow> followsFor(AppUser follower) {
        return followRepository.findByFollowerOrderByCreatedAtDesc(follower);
    }

    @Transactional
    public boolean follow(AppUser follower, FollowType type, Long targetId) {
        if (isFollowing(follower, type, targetId)) {
            return false;
        }
        followRepository.save(new Follow(follower, type, targetId));
        return true;
    }

    @Transactional
    public void unfollow(AppUser follower, FollowType type, Long targetId) {
        followRepository.deleteByFollowerAndTypeAndTargetId(follower, type, targetId);
    }

    /** Notifies each matching user once, even where multiple followed signals match. */
    public void filePublished(FileEntity file) {
        Map<Long, AppUser> recipients = new LinkedHashMap<>();
        followers(FollowType.DEPARTMENT, file.getDepartment().getId()).forEach(user -> recipients.putIfAbsent(user.getId(), user));
        followers(FollowType.UPLOADER, file.getUploader().getId()).forEach(user -> recipients.putIfAbsent(user.getId(), user));
        file.getTags().forEach(tag -> followers(FollowType.TAG, tag.getId())
                .forEach(user -> recipients.putIfAbsent(user.getId(), user)));
        recipients.remove(file.getUploader().getId());
        recipients.values().forEach(user -> notificationService.followedFilePublished(user, file.getTitle(), file.getId()));
    }

    private List<AppUser> followers(FollowType type, Long targetId) {
        return followRepository.findByTypeAndTargetId(type, targetId).stream()
                .map(Follow::getFollower)
                .toList();
    }
}
