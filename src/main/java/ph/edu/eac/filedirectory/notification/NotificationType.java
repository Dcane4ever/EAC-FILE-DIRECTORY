package ph.edu.eac.filedirectory.notification;

/**
 * What kind of event a Notification represents - drives the icon/styling in
 * the notification center template, not just a label. Add new values here
 * as new trigger points are wired up (see NotificationService); the enum
 * name itself is never shown to users, only Notification.message is.
 */
public enum NotificationType {
    UPLOAD_APPROVED,
    UPLOAD_REJECTED,
    ROLE_CHANGED,
    ACCESS_REQUESTED,
    ACCESS_APPROVED,
    ACCESS_DENIED,
    FOLLOWED_FILE_PUBLISHED
}
