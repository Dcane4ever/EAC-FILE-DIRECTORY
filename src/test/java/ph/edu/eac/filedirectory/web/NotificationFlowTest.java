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
import ph.edu.eac.filedirectory.audit.AuditEventRepository;
import ph.edu.eac.filedirectory.file.FileEntity;
import ph.edu.eac.filedirectory.file.FileRepository;
import ph.edu.eac.filedirectory.notification.Notification;
import ph.edu.eac.filedirectory.notification.NotificationRepository;
import ph.edu.eac.filedirectory.notification.NotificationType;
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
 * Covers the Phase 2 notification triggers end to end: upload
 * approved/rejected (AdminController) and role changed
 * (UserManagementController) each create exactly one Notification for the
 * right recipient with no duplicate on a no-op role "change", plus
 * NotificationController's ownership isolation (a user can only read/act on
 * their own notifications, never someone else's - see requireOwnNotification).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("junit")
class NotificationFlowTest {

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
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @MockitoBean
    private JavaMailSender mailSender;

    private AppUser uploader;
    private AppUser moderator;
    private AppUser admin;
    private Department department;
    private Category category;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        fileRepository.deleteAll();
        emailVerificationTokenRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
        // audit_events has a FK to app_users (actor) - every approve/reject/
        // role-change call in this test writes one via AuditService, so it
        // must be cleared before userRepository.deleteAll() below.
        auditEventRepository.deleteAll();
        userRepository.deleteAll();

        uploader = userRepository.save(verifiedUser("student@eac.edu.ph", "Student One", Role.USER));
        moderator = userRepository.save(verifiedUser("mod@eac.edu.ph", "Moderator One", Role.MODERATOR));
        admin = userRepository.save(verifiedUser("admin@eac.edu.ph", "Admin One", Role.ADMIN));

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

    private FileEntity pendingFile(String title) {
        FileEntity file = new FileEntity(title, "A test file", uploader, department, null, null, category,
                "some/fake/path.pdf", "path.pdf", 1024L, "application/pdf", "deadbeef");
        return fileRepository.save(file);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder asUser(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder, AppUser actingAs) {
        return builder.with(SecurityMockMvcRequestPostProcessors.user(new EacUserDetails(actingAs))).with(csrf());
    }

    @Test
    void approvingAnUpload_notifiesTheUploader() throws Exception {
        FileEntity file = pendingFile("A Great Thesis");

        mockMvc.perform(asUser(post("/admin/files/" + file.getId() + "/approve"), moderator))
                .andExpect(status().is3xxRedirection());

        assertThat(notificationRepository.count()).isEqualTo(1);
        Notification notification = notificationRepository.findAll().get(0);
        assertThat(notification.getRecipient().getId()).isEqualTo(uploader.getId());
        assertThat(notification.getType()).isEqualTo(NotificationType.UPLOAD_APPROVED);
        assertThat(notification.getMessage()).contains("A Great Thesis").contains("approved");
        assertThat(notification.getTargetUrl()).isEqualTo("/files/" + file.getId());
        assertThat(notification.isRead()).isFalse();
    }

    @Test
    void rejectingAnUpload_notifiesTheUploaderWithReason() throws Exception {
        FileEntity file = pendingFile("A Sketchy Paper");

        mockMvc.perform(asUser(post("/admin/files/" + file.getId() + "/reject"), moderator)
                        .param("reason", "Missing abstract"))
                .andExpect(status().is3xxRedirection());

        assertThat(notificationRepository.count()).isEqualTo(1);
        Notification notification = notificationRepository.findAll().get(0);
        assertThat(notification.getRecipient().getId()).isEqualTo(uploader.getId());
        assertThat(notification.getType()).isEqualTo(NotificationType.UPLOAD_REJECTED);
        assertThat(notification.getMessage()).contains("A Sketchy Paper").contains("Missing abstract");
    }

    @Test
    void changingRole_notifiesTheAffectedUser() throws Exception {
        mockMvc.perform(asUser(post("/admin/users/" + uploader.getId() + "/role"), admin)
                        .param("role", "MODERATOR"))
                .andExpect(status().is3xxRedirection());

        assertThat(notificationRepository.count()).isEqualTo(1);
        Notification notification = notificationRepository.findAll().get(0);
        assertThat(notification.getRecipient().getId()).isEqualTo(uploader.getId());
        assertThat(notification.getType()).isEqualTo(NotificationType.ROLE_CHANGED);
        assertThat(notification.getMessage()).contains("MODERATOR");
    }

    @Test
    void resubmittingTheSameRole_doesNotCreateADuplicateNotification() throws Exception {
        // uploader is already USER - "changing" to USER again is a no-op
        // and should not spam a notification.
        mockMvc.perform(asUser(post("/admin/users/" + uploader.getId() + "/role"), admin)
                .param("role", "USER"));

        assertThat(notificationRepository.count()).isZero();
    }

    @Test
    void aUserCannotMarkSomeoneElsesNotificationAsRead() throws Exception {
        FileEntity file = pendingFile("Someone Else's File");
        mockMvc.perform(asUser(post("/admin/files/" + file.getId() + "/approve"), moderator));

        Notification uploaderNotification = notificationRepository.findAll().get(0);
        assertThat(uploaderNotification.getRecipient().getId()).isEqualTo(uploader.getId());

        // moderator (not the recipient) tries to mark the uploader's
        // notification as read - must be rejected, not silently succeed.
        mockMvc.perform(asUser(post("/notifications/" + uploaderNotification.getId() + "/read"), moderator))
                .andExpect(status().isForbidden());

        Notification reloaded = notificationRepository.findById(uploaderNotification.getId()).orElseThrow();
        assertThat(reloaded.isRead()).isFalse();
    }

    @Test
    void ownerCanMarkTheirOwnNotificationAsRead() throws Exception {
        FileEntity file = pendingFile("My Own File");
        mockMvc.perform(asUser(post("/admin/files/" + file.getId() + "/approve"), moderator));

        Notification notification = notificationRepository.findAll().get(0);

        mockMvc.perform(asUser(post("/notifications/" + notification.getId() + "/read"), uploader))
                .andExpect(status().is3xxRedirection());

        Notification reloaded = notificationRepository.findById(notification.getId()).orElseThrow();
        assertThat(reloaded.isRead()).isTrue();
    }

    @Test
    void markAllRead_onlyAffectsTheCallersOwnNotifications() throws Exception {
        FileEntity file1 = pendingFile("File One");
        FileEntity file2 = pendingFile("File Two");
        mockMvc.perform(asUser(post("/admin/files/" + file1.getId() + "/approve"), moderator));
        mockMvc.perform(asUser(post("/admin/files/" + file2.getId() + "/reject"), moderator).param("reason", "no"));
        // Give the moderator their own unrelated notification via a role change.
        mockMvc.perform(asUser(post("/admin/users/" + moderator.getId() + "/role"), admin).param("role", "ADMIN")
                .param("confirmPassword", "SomePassword1"));

        long uploaderUnreadBefore = notificationRepository.countByRecipientAndReadFalse(uploader);
        assertThat(uploaderUnreadBefore).isEqualTo(2);

        mockMvc.perform(asUser(post("/notifications/mark-all-read"), uploader))
                .andExpect(status().is3xxRedirection());

        assertThat(notificationRepository.countByRecipientAndReadFalse(uploader)).isZero();
    }
}
