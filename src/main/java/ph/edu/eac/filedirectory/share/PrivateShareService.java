package ph.edu.eac.filedirectory.share;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ph.edu.eac.filedirectory.audit.AuditService;
import ph.edu.eac.filedirectory.file.FileEntity;
import ph.edu.eac.filedirectory.file.FileStatus;
import ph.edu.eac.filedirectory.notification.NotificationService;
import ph.edu.eac.filedirectory.user.AppUser;
import ph.edu.eac.filedirectory.user.AppUserRepository;

import java.util.List;
import java.util.Locale;

@Service
public class PrivateShareService {

    private final FileShareRepository fileShareRepository;
    private final AppUserRepository userRepository;
    private final NotificationService notificationService;
    private final AuditService auditService;

    public PrivateShareService(FileShareRepository fileShareRepository, AppUserRepository userRepository,
                               NotificationService notificationService, AuditService auditService) {
        this.fileShareRepository = fileShareRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.auditService = auditService;
    }

    public record Result(boolean changed, String message) {
        static Result ok(String message) { return new Result(true, message); }
        static Result rejected(String message) { return new Result(false, message); }
    }

    public boolean hasDirectDownload(FileEntity file, AppUser user) {
        return fileShareRepository.existsByFileAndRecipientAndRevokedAtIsNull(file, user);
    }

    public List<FileShare> activeShares(FileEntity file) {
        return fileShareRepository.findByFileAndRevokedAtIsNullOrderByCreatedAtDesc(file);
    }

    @Transactional
    public Result share(FileEntity file, AppUser actor, String recipientEmail) {
        if (!isUploader(file, actor)) {
            return Result.rejected("Only the uploader can grant private access to this file.");
        }
        if (file.getStatus() != FileStatus.APPROVED) {
            return Result.rejected("Private sharing is available after the file is approved.");
        }
        String email = recipientEmail == null ? "" : recipientEmail.trim().toLowerCase(Locale.ROOT);
        if (!email.endsWith("@eac.edu.ph")) {
            return Result.rejected("Private sharing is limited to verified @eac.edu.ph users.");
        }
        AppUser recipient = userRepository.findByEmail(email).orElse(null);
        if (recipient == null || !recipient.isEmailVerified()) {
            return Result.rejected("That EAC account does not exist or has not completed verification.");
        }
        if (recipient.getId().equals(actor.getId())) {
            return Result.rejected("You already have access to your own upload.");
        }

        FileShare existing = fileShareRepository.findByFileAndRecipient(file, recipient).orElse(null);
        if (existing != null && existing.isActive()) {
            return Result.rejected("This user already has private access to the file.");
        }
        if (existing == null) {
            fileShareRepository.save(new FileShare(file, recipient, actor));
        } else {
            existing.restore(actor);
        }
        notificationService.fileShared(recipient, actor, file.getTitle(), file.getId());
        auditService.fileShared(actor, file.getId(), file.getTitle(), recipient.getEmail());
        return Result.ok("Private access granted to " + recipient.getEmail() + ".");
    }

    @Transactional
    public Result revoke(FileEntity file, Long shareId, AppUser actor) {
        if (!isUploader(file, actor)) {
            return Result.rejected("Only the uploader can revoke private access.");
        }
        FileShare share = fileShareRepository.findById(shareId).orElse(null);
        if (share == null || !share.getFile().getId().equals(file.getId()) || !share.isActive()) {
            return Result.rejected("That private share is no longer active.");
        }
        share.revoke(actor);
        auditService.fileShareRevoked(actor, file.getId(), file.getTitle(), share.getRecipient().getEmail());
        return Result.ok("Private access revoked for " + share.getRecipient().getEmail() + ".");
    }

    private boolean isUploader(FileEntity file, AppUser user) {
        return user != null && user.getId().equals(file.getUploader().getId());
    }
}
