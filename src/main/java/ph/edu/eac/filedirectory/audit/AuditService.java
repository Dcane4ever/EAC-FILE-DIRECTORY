package ph.edu.eac.filedirectory.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ph.edu.eac.filedirectory.user.AppUser;

/**
 * Single place that writes AuditEvent rows, so every trigger point
 * (authentication, moderation, admin actions) goes through one reusable
 * call instead of each controller building an AuditEvent by hand - same
 * shape as NotificationService for the same reason.
 *
 * "Append-only" in the sense this codebase actually provides: nothing here
 * (or anywhere else - see AuditEventRepository) ever calls save() on an
 * existing row to alter it, and no delete method exists for ordinary users
 * to reach, including admins - the audit log page (AuditLogController) is
 * read-only. This is NOT cryptographic tamper-evidence (no hash chaining,
 * no external WORM storage) - a person with direct database access could
 * still edit rows. That level of guarantee would need infrastructure this
 * single-instance, local/LAN-deployed app doesn't have a justified need for
 * (see roadmap's "avoid speculative complexity" rule) - what this provides
 * is "the application itself never offers a way to alter history", which is
 * the meaningful bar for an internal institutional tool.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditEventRepository auditEventRepository;

    public AuditService(AuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    public void record(AuditEvent.Builder builder) {
        AuditEvent event = builder.build();
        try {
            auditEventRepository.save(event);
        } catch (RuntimeException e) {
            // An audit-write failure must never take down the real action it
            // was recording (e.g. a file approval should still succeed even
            // if, somehow, the audit insert fails) - log loudly instead so
            // the gap is at least visible in application logs.
            log.error("Failed to write audit event: action={}, actorEmail={}, targetType={}, targetId={}",
                    event.getAction(), event.getActorEmail(), event.getTargetType(), event.getTargetId(), e);
        }
    }

    // --- Authentication ---

    public void loginSuccess(AppUser actor, String ipAddress) {
        record(AuditEvent.builder(AuditAction.LOGIN_SUCCESS).actor(actor).ipAddress(ipAddress));
    }

    public void loginFailure(String attemptedEmail, String reason, String ipAddress) {
        record(AuditEvent.builder(AuditAction.LOGIN_FAILURE)
                .actorEmail(attemptedEmail).reason(reason).ipAddress(ipAddress));
    }

    public void logout(AppUser actor, String ipAddress) {
        record(AuditEvent.builder(AuditAction.LOGOUT).actor(actor).ipAddress(ipAddress));
    }

    public void passwordResetRequested(String email, String ipAddress) {
        record(AuditEvent.builder(AuditAction.PASSWORD_RESET_REQUESTED).actorEmail(email).ipAddress(ipAddress));
    }

    public void passwordResetCompleted(AppUser actor, String ipAddress) {
        record(AuditEvent.builder(AuditAction.PASSWORD_RESET_COMPLETED).actor(actor).ipAddress(ipAddress));
    }

    public void accountVerified(AppUser actor) {
        record(AuditEvent.builder(AuditAction.ACCOUNT_VERIFIED).actor(actor)
                .target(AuditTargetType.USER, actor.getId()));
    }

    // --- Files ---

    public void fileUploaded(AppUser actor, Long fileId, String title) {
        record(AuditEvent.builder(AuditAction.FILE_UPLOADED).actor(actor)
                .target(AuditTargetType.FILE, fileId).metadata(title));
    }

    public void fileApproved(AppUser actor, Long fileId, String title) {
        record(AuditEvent.builder(AuditAction.FILE_APPROVED).actor(actor)
                .target(AuditTargetType.FILE, fileId).metadata(title));
    }

    public void fileRejected(AppUser actor, Long fileId, String title, String reason) {
        record(AuditEvent.builder(AuditAction.FILE_REJECTED).actor(actor)
                .target(AuditTargetType.FILE, fileId).metadata(title).reason(reason));
    }

    public void fileArchived(AppUser actor, Long fileId, String title, String previousStatus, String reason) {
        record(AuditEvent.builder(AuditAction.FILE_ARCHIVED).actor(actor)
                .target(AuditTargetType.FILE, fileId).metadata(title)
                .previousValue(previousStatus).newValue("ARCHIVED").reason(reason));
    }

    public void fileRestored(AppUser actor, Long fileId, String title, String restoredStatus) {
        record(AuditEvent.builder(AuditAction.FILE_RESTORED).actor(actor)
                .target(AuditTargetType.FILE, fileId).metadata(title)
                .previousValue("ARCHIVED").newValue(restoredStatus));
    }

    public void fileDownloaded(AppUser actor, Long fileId, String title) {
        record(AuditEvent.builder(AuditAction.FILE_DOWNLOADED).actor(actor)
                .target(AuditTargetType.FILE, fileId).metadata(title));
    }

    public void fileAccessPolicyChanged(AppUser actor, Long fileId, String title, String previousPolicy, String newPolicy) {
        record(AuditEvent.builder(AuditAction.FILE_ACCESS_POLICY_CHANGED).actor(actor)
                .target(AuditTargetType.FILE, fileId).metadata(title)
                .previousValue(previousPolicy).newValue(newPolicy));
    }

    public void fileVersionUploaded(AppUser actor, Long fileId, String title, int versionNumber, String note) {
        record(AuditEvent.builder(AuditAction.FILE_VERSION_UPLOADED).actor(actor)
                .target(AuditTargetType.FILE, fileId).metadata(title)
                .newValue("v" + versionNumber).reason(note));
    }

    public void fileShared(AppUser actor, Long fileId, String title, String recipientEmail) {
        record(AuditEvent.builder(AuditAction.FILE_SHARED).actor(actor)
                .target(AuditTargetType.FILE, fileId).metadata(title).newValue(recipientEmail));
    }

    public void fileShareRevoked(AppUser actor, Long fileId, String title, String recipientEmail) {
        record(AuditEvent.builder(AuditAction.FILE_SHARE_REVOKED).actor(actor)
                .target(AuditTargetType.FILE, fileId).metadata(title).previousValue(recipientEmail));
    }

    public void storageOrphanScheduled(AppUser actor, String storedPath, String reason, String eligibleAt) {
        record(AuditEvent.builder(AuditAction.STORAGE_ORPHAN_SCHEDULED).actor(actor)
                .metadata(storedPath).reason(reason).newValue(eligibleAt));
    }

    public void storageOrphanCancelled(AppUser actor, String storedPath, String reason) {
        record(AuditEvent.builder(AuditAction.STORAGE_ORPHAN_CANCELLED).actor(actor)
                .metadata(storedPath).reason(reason));
    }

    public void storageOrphanDeleted(AppUser actor, String storedPath, String reason) {
        record(AuditEvent.builder(AuditAction.STORAGE_ORPHAN_DELETED).actor(actor)
                .metadata(storedPath).reason(reason));
    }

    // --- Access requests ---

    public void accessRequested(AppUser actor, Long fileId, String title) {
        record(AuditEvent.builder(AuditAction.ACCESS_REQUESTED).actor(actor)
                .target(AuditTargetType.FILE, fileId).metadata(title));
    }

    /** decidedByModerator distinguishes the uploader deciding (normal path) from the 7-day admin-gated moderator fallback (see AccessRequestService.canModeratorDecide) - both write this same action, the distinction lives in the actor's role, visible in the audit log itself. */
    public void accessApproved(AppUser actor, Long fileId, String title) {
        record(AuditEvent.builder(AuditAction.ACCESS_APPROVED).actor(actor)
                .target(AuditTargetType.FILE, fileId).metadata(title));
    }

    public void accessDenied(AppUser actor, Long fileId, String title, String reason) {
        record(AuditEvent.builder(AuditAction.ACCESS_DENIED).actor(actor)
                .target(AuditTargetType.FILE, fileId).metadata(title).reason(reason));
    }

    public void accessGrantDownloaded(AppUser actor, Long fileId, String title) {
        record(AuditEvent.builder(AuditAction.ACCESS_GRANT_DOWNLOADED).actor(actor)
                .target(AuditTargetType.FILE, fileId).metadata(title));
    }

    // --- Administration ---

    public void roleChanged(AppUser actor, AppUser target, String previousRole, String newRole) {
        record(AuditEvent.builder(AuditAction.ROLE_CHANGED).actor(actor)
                .target(AuditTargetType.USER, target.getId())
                .previousValue(previousRole).newValue(newRole).metadata(target.getEmail()));
    }

    public void accountCreatedByAdmin(AppUser actor, AppUser created, String role) {
        record(AuditEvent.builder(AuditAction.ACCOUNT_CREATED_BY_ADMIN).actor(actor)
                .target(AuditTargetType.USER, created.getId())
                .newValue(role).metadata(created.getEmail()));
    }

    public void departmentAutoApproveToggled(AppUser actor, Long departmentId, String departmentName, boolean enabled) {
        record(AuditEvent.builder(AuditAction.DEPARTMENT_AUTO_APPROVE_TOGGLED).actor(actor)
                .target(AuditTargetType.DEPARTMENT, departmentId)
                .newValue(String.valueOf(enabled)).metadata(departmentName));
    }

    public void systemSettingChanged(AppUser actor, String settingName, String previousValue, String newValue) {
        record(AuditEvent.builder(AuditAction.SYSTEM_SETTING_CHANGED).actor(actor)
                .previousValue(previousValue).newValue(newValue).metadata(settingName));
    }
}
