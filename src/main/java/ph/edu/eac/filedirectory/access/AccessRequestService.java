package ph.edu.eac.filedirectory.access;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ph.edu.eac.filedirectory.audit.AuditService;
import ph.edu.eac.filedirectory.file.FileEntity;
import ph.edu.eac.filedirectory.notification.NotificationService;
import ph.edu.eac.filedirectory.settings.SystemSettingService;
import ph.edu.eac.filedirectory.user.AppUser;
import ph.edu.eac.filedirectory.user.MailService;
import ph.edu.eac.filedirectory.user.Role;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The access-request state machine: request -> uploader (or, after the
 * fallback window, a moderator/admin) approves or denies -> an approved
 * request gets a time-limited AccessGrantToken -> that token gates one
 * download of the original file. See AccessRequestController for the HTTP
 * surface, FileAccessController for the download endpoint the token is
 * checked against.
 *
 * Every outcome fires both an audit event and a notification+email, same
 * pattern as every other trigger point in this codebase (AdminController's
 * approve/reject, UserManagementController's role changes) - nothing here
 * invents a new pattern for those two concerns.
 */
@Service
public class AccessRequestService {

    private final AccessRequestRepository accessRequestRepository;
    private final AccessGrantTokenRepository accessGrantTokenRepository;
    private final SystemSettingService systemSettingService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final MailService mailService;
    private final long grantTokenTtlHours;
    private final long moderatorFallbackDays;

    public AccessRequestService(AccessRequestRepository accessRequestRepository,
                                 AccessGrantTokenRepository accessGrantTokenRepository,
                                 SystemSettingService systemSettingService,
                                 NotificationService notificationService,
                                 AuditService auditService,
                                 MailService mailService,
                                 @Value("${eac.access.grant-token-ttl-hours}") long grantTokenTtlHours,
                                 @Value("${eac.access.moderator-fallback-days}") long moderatorFallbackDays) {
        this.accessRequestRepository = accessRequestRepository;
        this.accessGrantTokenRepository = accessGrantTokenRepository;
        this.systemSettingService = systemSettingService;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.mailService = mailService;
        this.grantTokenTtlHours = grantTokenTtlHours;
        this.moderatorFallbackDays = moderatorFallbackDays;
    }

    public record RequestResult(boolean created, String errorMessage) {
        static RequestResult ok() {
            return new RequestResult(true, null);
        }

        static RequestResult rejected(String message) {
            return new RequestResult(false, message);
        }
    }

    @Transactional
    public RequestResult request(FileEntity file, AppUser requester) {
        return request(file, requester, null);
    }

    @Transactional
    public RequestResult request(FileEntity file, AppUser requester, Integer requestedVersionNumber) {
        if (file.getUploader().getId().equals(requester.getId())) {
            return RequestResult.rejected("You already have full access to your own upload.");
        }
        boolean alreadyPending = accessRequestRepository
                .findFirstByFileAndRequesterAndRequestedVersionNumberAndStatus(
                        file, requester, requestedVersionNumber, AccessRequestStatus.PENDING)
                .isPresent();
        if (alreadyPending) {
            return RequestResult.rejected(requestedVersionNumber == null
                    ? "You already have a pending request for this file."
                    : "You already have a pending request for version " + requestedVersionNumber + ".");
        }

        AccessRequest accessRequest = new AccessRequest(file, requester, requestedVersionNumber);
        accessRequestRepository.save(accessRequest);

        AppUser uploader = file.getUploader();
        notificationService.accessRequested(uploader, requester, file.getTitle(), file.getId());
        mailService.sendAccessRequestedEmail(uploader.getEmail(), uploader.getFullName(), requester.getFullName(), file.getTitle());
        auditService.accessRequested(requester, file.getId(), file.getTitle());

        // Per-file auto-decision policy (see AccessDecisionPolicy, set by
        // the uploader at upload time) - skips the PENDING wait entirely
        // when set, but still goes through the exact same approve()/deny()
        // path as a human decision (same grant token creation, same
        // notification/email/audit trail), just with the uploader as the
        // recorded decider since it's their standing policy being applied,
        // not an anonymous system action.
        switch (file.getAccessDecisionPolicy()) {
            case AUTO_APPROVE -> approve(accessRequest, uploader);
            case AUTO_REJECT -> deny(accessRequest, uploader, "Automatically declined based on the uploader's access policy for this file.");
            case MANUAL -> { /* stays PENDING for the uploader (or fallback) to decide */ }
        }

        return RequestResult.ok();
    }

    /**
     * Whether `actor` is allowed to decide this specific request - the
     * uploader always can; a moderator/admin can only once the request has
     * sat PENDING for at least moderatorFallbackDays AND an admin has
     * turned on SystemSetting.allowModeratorAccessRequestFallback. This is
     * checked fresh on every approve/deny call (not cached), so toggling
     * the setting off immediately revokes the fallback for any
     * still-pending request.
     */
    public boolean canDecide(AccessRequest accessRequest, AppUser actor) {
        if (accessRequest.getFile().getUploader().getId().equals(actor.getId())) {
            return true;
        }
        boolean isStaff = actor.getRole() == Role.MODERATOR || actor.getRole() == Role.ADMIN;
        if (!isStaff || !systemSettingService.isAllowModeratorAccessRequestFallback()) {
            return false;
        }
        Instant fallbackEligibleAt = accessRequest.getCreatedAt().plus(Duration.ofDays(moderatorFallbackDays));
        return !Instant.now().isBefore(fallbackEligibleAt);
    }

    @Transactional
    public RequestResult approve(AccessRequest accessRequest, AppUser actor) {
        if (accessRequest.getStatus() != AccessRequestStatus.PENDING) {
            return RequestResult.rejected("This request has already been decided.");
        }
        if (!canDecide(accessRequest, actor)) {
            return RequestResult.rejected("You're not able to decide this request yet.");
        }

        accessRequest.setStatus(AccessRequestStatus.APPROVED);
        accessRequest.setDecidedBy(actor);
        accessRequest.setDecidedAt(Instant.now());
        accessRequestRepository.save(accessRequest);

        String token = UUID.randomUUID().toString().replace("-", "");
        Instant expiresAt = Instant.now().plus(Duration.ofHours(grantTokenTtlHours));
        accessGrantTokenRepository.save(new AccessGrantToken(token, accessRequest, expiresAt));

        FileEntity file = accessRequest.getFile();
        AppUser requester = accessRequest.getRequester();
        notificationService.accessApproved(requester, file.getTitle(), file.getId());
        mailService.sendAccessApprovedEmail(requester.getEmail(), requester.getFullName(), file.getTitle());
        auditService.accessApproved(actor, file.getId(), file.getTitle());

        return RequestResult.ok();
    }

    @Transactional
    public RequestResult deny(AccessRequest accessRequest, AppUser actor, String reason) {
        if (accessRequest.getStatus() != AccessRequestStatus.PENDING) {
            return RequestResult.rejected("This request has already been decided.");
        }
        if (!canDecide(accessRequest, actor)) {
            return RequestResult.rejected("You're not able to decide this request yet.");
        }

        accessRequest.setStatus(AccessRequestStatus.DENIED);
        accessRequest.setDecidedBy(actor);
        accessRequest.setDecidedAt(Instant.now());
        String note = reason == null || reason.isBlank() ? null : reason.trim();
        accessRequest.setDecisionNote(note);
        accessRequestRepository.save(accessRequest);

        FileEntity file = accessRequest.getFile();
        AppUser requester = accessRequest.getRequester();
        notificationService.accessDenied(requester, file.getTitle(), note);
        mailService.sendAccessDeniedEmail(requester.getEmail(), requester.getFullName(), file.getTitle(), note);
        auditService.accessDenied(actor, file.getId(), file.getTitle(), note);

        return RequestResult.ok();
    }

    /**
     * Sweeps APPROVED requests whose grant token has expired unused and
     * flips them to EXPIRED, so "My Requests" reads "Expired, request
     * again" instead of showing a dead link forever - see
     * AccessRequestController's scheduled job wiring this in.
     */
    @Transactional
    public int expireStaleGrants() {
        List<AccessRequest> approved = accessRequestRepository
                .findByStatusAndCreatedAtBefore(AccessRequestStatus.APPROVED, Instant.now().minus(Duration.ofHours(grantTokenTtlHours)));
        int expired = 0;
        for (AccessRequest accessRequest : approved) {
            AccessGrantToken token = accessGrantTokenRepository.findByAccessRequest(accessRequest).orElse(null);
            if (token != null && token.isValid()) {
                // Still genuinely usable (e.g. createdAt-based cutoff caught
                // it, but the token itself was issued later / has its own
                // clock) - don't expire something still valid.
                continue;
            }
            accessRequest.setStatus(AccessRequestStatus.EXPIRED);
            accessRequestRepository.save(accessRequest);
            expired++;
        }
        return expired;
    }
}
