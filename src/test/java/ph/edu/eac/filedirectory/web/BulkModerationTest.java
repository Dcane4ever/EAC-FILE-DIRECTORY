package ph.edu.eac.filedirectory.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ph.edu.eac.filedirectory.audit.AuditAction;
import ph.edu.eac.filedirectory.audit.AuditEventRepository;
import ph.edu.eac.filedirectory.file.FileEntity;
import ph.edu.eac.filedirectory.file.FileRepository;
import ph.edu.eac.filedirectory.file.FileStatus;
import ph.edu.eac.filedirectory.notification.NotificationRepository;
import ph.edu.eac.filedirectory.security.EacUserDetails;
import ph.edu.eac.filedirectory.taxonomy.Category;
import ph.edu.eac.filedirectory.taxonomy.CategoryRepository;
import ph.edu.eac.filedirectory.taxonomy.Department;
import ph.edu.eac.filedirectory.taxonomy.DepartmentRepository;
import ph.edu.eac.filedirectory.user.AppUser;
import ph.edu.eac.filedirectory.user.AppUserRepository;
import ph.edu.eac.filedirectory.user.EmailVerificationTokenRepository;
import ph.edu.eac.filedirectory.user.PasswordResetTokenRepository;
import ph.edu.eac.filedirectory.user.Role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers Phase 7's bulk approve/reject endpoints: every file in a batch
 * still gets its own full moderation record, its own audit event, and its
 * own notification to its uploader (not one combined "batch" event) - the
 * roadmap's explicit requirement that bulk actions don't bypass individual
 * moderation records. Also covers the two "don't blow up the whole batch"
 * cases: a stale/already-decided file ID mixed into the batch is silently
 * skipped, and an empty selection is rejected outright.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("junit")
class BulkModerationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private FileRepository fileRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @MockitoBean
    private JavaMailSender mailSender;

    private AppUser uploaderA;
    private AppUser uploaderB;
    private AppUser moderator;
    private AppUser regularUser;
    private Department department;
    private Category category;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        fileRepository.deleteAll();
        emailVerificationTokenRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
        auditEventRepository.deleteAll();
        userRepository.deleteAll();

        uploaderA = userRepository.save(verifiedUser("uploader.a@eac.edu.ph", "Uploader A", Role.USER));
        uploaderB = userRepository.save(verifiedUser("uploader.b@eac.edu.ph", "Uploader B", Role.USER));
        moderator = userRepository.save(verifiedUser("mod@eac.edu.ph", "Moderator One", Role.MODERATOR));
        regularUser = userRepository.save(verifiedUser("student@eac.edu.ph", "Regular Student", Role.USER));

        department = departmentRepository.findAll().stream().findFirst()
                .orElseGet(() -> departmentRepository.save(new Department(1, "ENGR", "School of Engineering")));
        category = categoryRepository.findAll().stream().findFirst()
                .orElseGet(() -> categoryRepository.save(new Category("Thesis", "fa-graduation-cap")));
    }

    private AppUser verifiedUser(String email, String name, Role role) {
        AppUser user = AppUser.registerManually(email, name, passwordEncoder.encode("SomePassword1"));
        user.setEmailVerified(true);
        user.setRole(role);
        return user;
    }

    private FileEntity pendingFile(String title, AppUser uploader) {
        FileEntity file = new FileEntity(title, "A test file", uploader, department, null, null, category,
                "some/fake/path.pdf", "path.pdf", 1024L, "application/pdf", "checksum-" + title.hashCode());
        return fileRepository.save(file);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder asUser(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder, AppUser actingAs) {
        return builder.with(SecurityMockMvcRequestPostProcessors.user(new EacUserDetails(actingAs))).with(csrf());
    }

    @Test
    void bulkApprove_approvesEveryFileWithItsOwnRecord() throws Exception {
        FileEntity fileA = pendingFile("Paper A", uploaderA);
        FileEntity fileB = pendingFile("Paper B", uploaderB);

        mockMvc.perform(asUser(post("/admin/files/bulk-approve"), moderator)
                        .param("fileIds", fileA.getId().toString(), fileB.getId().toString()))
                .andExpect(status().is3xxRedirection());

        FileEntity reloadedA = fileRepository.findById(fileA.getId()).orElseThrow();
        FileEntity reloadedB = fileRepository.findById(fileB.getId()).orElseThrow();
        assertThat(reloadedA.getStatus()).isEqualTo(FileStatus.APPROVED);
        assertThat(reloadedB.getStatus()).isEqualTo(FileStatus.APPROVED);
        // Each file gets its own approvedBy/approvedAt - not a shared batch record.
        assertThat(reloadedA.getApprovedBy().getId()).isEqualTo(moderator.getId());
        assertThat(reloadedB.getApprovedBy().getId()).isEqualTo(moderator.getId());
        assertThat(reloadedA.getApprovedAt()).isNotNull();
        assertThat(reloadedB.getApprovedAt()).isNotNull();
    }

    @Test
    void bulkApprove_notifiesEachUploaderSeparately() throws Exception {
        FileEntity fileA = pendingFile("Paper A", uploaderA);
        FileEntity fileB = pendingFile("Paper B", uploaderB);

        mockMvc.perform(asUser(post("/admin/files/bulk-approve"), moderator)
                .param("fileIds", fileA.getId().toString(), fileB.getId().toString()));

        assertThat(notificationRepository.count()).isEqualTo(2);
        assertThat(notificationRepository.findAll())
                .extracting(n -> n.getRecipient().getId())
                .containsExactlyInAnyOrder(uploaderA.getId(), uploaderB.getId());
    }

    @Test
    void bulkApprove_writesOneAuditEventPerFile() throws Exception {
        FileEntity fileA = pendingFile("Paper A", uploaderA);
        FileEntity fileB = pendingFile("Paper B", uploaderB);

        mockMvc.perform(asUser(post("/admin/files/bulk-approve"), moderator)
                .param("fileIds", fileA.getId().toString(), fileB.getId().toString()));

        var approvalEvents = auditEventRepository.findAll().stream()
                .filter(e -> e.getAction() == AuditAction.FILE_APPROVED)
                .toList();
        assertThat(approvalEvents).hasSize(2);
        assertThat(approvalEvents).extracting(e -> e.getTargetId())
                .containsExactlyInAnyOrder(fileA.getId(), fileB.getId());
    }

    @Test
    void bulkReject_appliesTheSameReasonToEveryFile() throws Exception {
        FileEntity fileA = pendingFile("Paper A", uploaderA);
        FileEntity fileB = pendingFile("Paper B", uploaderB);

        mockMvc.perform(asUser(post("/admin/files/bulk-reject"), moderator)
                        .param("fileIds", fileA.getId().toString(), fileB.getId().toString())
                        .param("reason", "Missing required signature page"))
                .andExpect(status().is3xxRedirection());

        FileEntity reloadedA = fileRepository.findById(fileA.getId()).orElseThrow();
        FileEntity reloadedB = fileRepository.findById(fileB.getId()).orElseThrow();
        assertThat(reloadedA.getStatus()).isEqualTo(FileStatus.REJECTED);
        assertThat(reloadedB.getStatus()).isEqualTo(FileStatus.REJECTED);
        assertThat(reloadedA.getRejectionReason()).isEqualTo("Missing required signature page");
        assertThat(reloadedB.getRejectionReason()).isEqualTo("Missing required signature page");
    }

    @Test
    void bulkReject_blankReason_fallsBackToDefaultForEveryFile() throws Exception {
        FileEntity fileA = pendingFile("Paper A", uploaderA);

        mockMvc.perform(asUser(post("/admin/files/bulk-reject"), moderator)
                .param("fileIds", fileA.getId().toString()));

        FileEntity reloaded = fileRepository.findById(fileA.getId()).orElseThrow();
        assertThat(reloaded.getRejectionReason()).isEqualTo("No reason given");
    }

    @Test
    void aStaleOrAlreadyDecidedFileId_isSkippedNotTreatedAsAnError() throws Exception {
        FileEntity pending = pendingFile("Still Pending", uploaderA);
        FileEntity alreadyApproved = pendingFile("Already Approved", uploaderB);
        alreadyApproved.setStatus(FileStatus.APPROVED);
        fileRepository.save(alreadyApproved);

        mockMvc.perform(asUser(post("/admin/files/bulk-approve"), moderator)
                        .param("fileIds", pending.getId().toString(), alreadyApproved.getId().toString(), "999999"))
                .andExpect(status().is3xxRedirection());

        // The still-pending file is approved; the already-approved and
        // nonexistent IDs are silently skipped rather than aborting the batch.
        assertThat(fileRepository.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo(FileStatus.APPROVED);
        // Only one new approval event (for `pending`) - the already-approved
        // file must not get a second, redundant approval record.
        long approvalEventsForAlreadyApproved = auditEventRepository.findAll().stream()
                .filter(e -> e.getAction() == AuditAction.FILE_APPROVED && alreadyApproved.getId().equals(e.getTargetId()))
                .count();
        assertThat(approvalEventsForAlreadyApproved).isZero();
    }

    @Test
    void emptySelection_isRejectedWithoutTouchingAnything() throws Exception {
        mockMvc.perform(asUser(post("/admin/files/bulk-approve"), moderator))
                .andExpect(status().is3xxRedirection());

        assertThat(auditEventRepository.findAll()).noneMatch(e -> e.getAction() == AuditAction.FILE_APPROVED);
    }

    @Test
    void aNonModeratorCannotBulkApprove() throws Exception {
        FileEntity file = pendingFile("Paper A", uploaderA);

        mockMvc.perform(asUser(post("/admin/files/bulk-approve"), regularUser)
                        .param("fileIds", file.getId().toString()))
                .andExpect(status().isForbidden());

        assertThat(fileRepository.findById(file.getId()).orElseThrow().getStatus()).isEqualTo(FileStatus.PENDING);
    }

    @Test
    void aNonModeratorCannotBulkReject() throws Exception {
        FileEntity file = pendingFile("Paper A", uploaderA);

        mockMvc.perform(asUser(post("/admin/files/bulk-reject"), regularUser)
                        .param("fileIds", file.getId().toString()))
                .andExpect(status().isForbidden());

        assertThat(fileRepository.findById(file.getId()).orElseThrow().getStatus()).isEqualTo(FileStatus.PENDING);
    }
}
