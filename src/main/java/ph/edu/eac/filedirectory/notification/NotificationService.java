package ph.edu.eac.filedirectory.notification;

import org.springframework.stereotype.Service;
import ph.edu.eac.filedirectory.user.AppUser;

/**
 * Single place that creates notifications, so trigger points (AdminController's
 * approve/reject, UserManagementController's role changes, and whatever gets
 * added later - e.g. access-request updates once that feature exists) all go
 * through one reusable call instead of each controller building a
 * Notification by hand. See NotificationController for how these are read.
 */
@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;

    public NotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    public void notify(AppUser recipient, NotificationType type, String message, String targetUrl) {
        notificationRepository.save(new Notification(recipient, type, message, targetUrl));
    }

    public void uploadApproved(AppUser uploader, String fileTitle, Long fileId) {
        notify(uploader, NotificationType.UPLOAD_APPROVED,
                "Your upload \"" + fileTitle + "\" was approved.",
                "/files/" + fileId);
    }

    public void uploadRejected(AppUser uploader, String fileTitle, Long fileId, String reason) {
        notify(uploader, NotificationType.UPLOAD_REJECTED,
                "Your upload \"" + fileTitle + "\" was rejected. Reason: " + reason,
                "/files/" + fileId);
    }

    public void roleChanged(AppUser target, String newRole) {
        notify(target, NotificationType.ROLE_CHANGED,
                "Your account role was changed to " + newRole + ".",
                null);
    }

    public void accessRequested(AppUser uploader, AppUser requester, String fileTitle, Long fileId) {
        notify(uploader, NotificationType.ACCESS_REQUESTED,
                requester.getFullName() + " requested access to \"" + fileTitle + "\".",
                "/my-uploads/access-requests");
    }

    public void accessApproved(AppUser requester, String fileTitle, Long fileId) {
        notify(requester, NotificationType.ACCESS_APPROVED,
                "Your request for \"" + fileTitle + "\" was approved. The download link is ready.",
                "/my-requests");
    }

    public void accessDenied(AppUser requester, String fileTitle, String reason) {
        String suffix = reason == null || reason.isBlank() ? "" : " Reason: " + reason;
        notify(requester, NotificationType.ACCESS_DENIED,
                "Your request for \"" + fileTitle + "\" was denied." + suffix,
                "/my-requests");
    }

    public void followedFilePublished(AppUser recipient, String fileTitle, Long fileId) {
        notify(recipient, NotificationType.FOLLOWED_FILE_PUBLISHED,
                "A new approved file matches something you follow: \"" + fileTitle + "\".",
                "/files/" + fileId);
    }
}
