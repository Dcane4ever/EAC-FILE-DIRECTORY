package ph.edu.eac.filedirectory.web;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import ph.edu.eac.filedirectory.notification.Notification;
import ph.edu.eac.filedirectory.notification.NotificationRepository;
import ph.edu.eac.filedirectory.security.EacUserDetails;
import ph.edu.eac.filedirectory.user.AppUser;

/**
 * In-app notification center - every signed-in user's own notifications
 * (upload approved/rejected, role changed - see NotificationService for
 * what creates these). Every endpoint here scopes strictly to the calling
 * user's own notifications; there is no way to read or act on someone
 * else's via this controller (see requireOwnNotification).
 */
@Controller
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationRepository notificationRepository;

    public NotificationController(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @GetMapping
    public String list(@AuthenticationPrincipal EacUserDetails principal,
                        @RequestParam(defaultValue = "0") int page,
                        Model model) {
        AppUser user = requireSelf(principal);
        Pageable pageable = PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Notification> notifications = notificationRepository.findByRecipientOrderByCreatedAtDesc(user, pageable);

        model.addAttribute("notifications", notifications);
        model.addAttribute("unreadCount", notificationRepository.countByRecipientAndReadFalse(user));
        return "notifications";
    }

    @PostMapping("/{id}/read")
    public String markRead(@PathVariable Long id,
                            @RequestParam(required = false) String redirectTo,
                            @AuthenticationPrincipal EacUserDetails principal) {
        AppUser user = requireSelf(principal);
        Notification notification = notificationRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        requireOwnNotification(user, notification);

        notification.setRead(true);
        notificationRepository.save(notification);

        // Support "mark read, then go straight to the file" from a single
        // click (see notifications.html) - falls back to the list itself
        // when there's nowhere more useful to send them.
        if (redirectTo != null && !redirectTo.isBlank()) {
            return "redirect:" + redirectTo;
        }
        return "redirect:/notifications";
    }

    @PostMapping("/mark-all-read")
    @Transactional
    public String markAllRead(@AuthenticationPrincipal EacUserDetails principal) {
        AppUser user = requireSelf(principal);
        notificationRepository.markAllAsRead(user);
        return "redirect:/notifications";
    }

    private void requireOwnNotification(AppUser user, Notification notification) {
        if (!notification.getRecipient().getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    private AppUser requireSelf(EacUserDetails principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return principal.getAppUser();
    }
}
