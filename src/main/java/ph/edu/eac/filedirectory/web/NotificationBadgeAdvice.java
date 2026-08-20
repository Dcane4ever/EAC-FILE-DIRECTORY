package ph.edu.eac.filedirectory.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import ph.edu.eac.filedirectory.notification.NotificationRepository;
import ph.edu.eac.filedirectory.security.EacUserDetails;

/**
 * Makes "unreadNotificationCount" available to every Thymeleaf template
 * without every controller having to fetch and pass it individually - see
 * the notification bell/badge used across the app's headers. Zero for
 * unauthenticated requests (login/register/etc. pages never show the bell
 * anyway, but this keeps the attribute safe to reference regardless).
 */
@ControllerAdvice
public class NotificationBadgeAdvice {

    private final NotificationRepository notificationRepository;

    public NotificationBadgeAdvice(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @ModelAttribute("unreadNotificationCount")
    public long unreadNotificationCount(@AuthenticationPrincipal EacUserDetails principal) {
        if (principal == null) {
            return 0;
        }
        return notificationRepository.countByRecipientAndReadFalse(principal.getAppUser());
    }
}
