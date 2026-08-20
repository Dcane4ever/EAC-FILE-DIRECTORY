package ph.edu.eac.filedirectory.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ph.edu.eac.filedirectory.file.FileEntity;
import ph.edu.eac.filedirectory.file.FileRepository;
import ph.edu.eac.filedirectory.notification.NotificationRepository;
import ph.edu.eac.filedirectory.security.EacUserDetails;
import ph.edu.eac.filedirectory.security.ratelimit.RateLimiter;
import ph.edu.eac.filedirectory.taxonomy.Category;
import ph.edu.eac.filedirectory.taxonomy.CategoryRepository;
import ph.edu.eac.filedirectory.taxonomy.Department;
import ph.edu.eac.filedirectory.taxonomy.DepartmentRepository;
import ph.edu.eac.filedirectory.user.AppUser;
import ph.edu.eac.filedirectory.user.AppUserRepository;
import ph.edu.eac.filedirectory.user.EmailVerificationTokenRepository;
import ph.edu.eac.filedirectory.user.PasswordResetTokenRepository;
import ph.edu.eac.filedirectory.user.Role;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Covers the audit trail end to end: real trigger points (login, upload,
 * approve/reject, role change) actually write an AuditEvent with the right
 * shape, and AuditEventRepository.search's filters (actor, action, target
 * type, date range) behave correctly - the query backing the admin-facing
 * audit log page.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("junit")
class AuditTrailTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

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
    private RateLimiter rateLimiter;

    @Autowired
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private JavaMailSender mailSender;

    private AppUser uploader;
    private AppUser moderator;
    private AppUser admin;
    private Department department;
    private Category category;

    private static final String PASSWORD = "SomePassword1";

    @BeforeEach
    void setUp() {
        rateLimiter.reset();
        auditEventRepository.deleteAll();
        notificationRepository.deleteAll();
        fileRepository.deleteAll();
        emailVerificationTokenRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
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
        AppUser user = AppUser.registerManually(email, name, passwordEncoder.encode(PASSWORD));
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
    void successfulLogin_writesALoginSuccessEvent() throws Exception {
        mockMvc.perform(post("/login").with(csrf())
                .param("email", uploader.getEmail())
                .param("password", PASSWORD));

        var events = auditEventRepository.findAll();
        assertThat(events).anySatisfy(e -> {
            assertThat(e.getAction()).isEqualTo(AuditAction.LOGIN_SUCCESS);
            assertThat(e.getActorEmail()).isEqualTo(uploader.getEmail());
        });
    }

    @Test
    void failedLogin_writesALoginFailureEventWithAReason() throws Exception {
        mockMvc.perform(post("/login").with(csrf())
                .param("email", uploader.getEmail())
                .param("password", "wrong-password"));

        var events = auditEventRepository.findAll();
        assertThat(events).anySatisfy(e -> {
            assertThat(e.getAction()).isEqualTo(AuditAction.LOGIN_FAILURE);
            assertThat(e.getActorEmail()).isEqualTo(uploader.getEmail());
            assertThat(e.getReason()).isEqualTo("Wrong password");
        });
    }

    @Test
    void failedLoginAgainstUnknownEmail_stillWritesAnEvent() throws Exception {
        mockMvc.perform(post("/login").with(csrf())
                .param("email", "nobody@eac.edu.ph")
                .param("password", "whatever"));

        var events = auditEventRepository.findAll();
        assertThat(events).anySatisfy(e -> {
            assertThat(e.getAction()).isEqualTo(AuditAction.LOGIN_FAILURE);
            assertThat(e.getActorEmail()).isEqualTo("nobody@eac.edu.ph");
            assertThat(e.getReason()).isEqualTo("Unknown account");
            // No real account exists for this email - actor (the FK) must be null,
            // only actorEmail is populated.
            assertThat(e.getActor()).isNull();
        });
    }

    @Test
    void approvingAFile_writesAFileApprovedEventWithModeratorAsActor() throws Exception {
        FileEntity file = pendingFile("A Great Thesis");

        mockMvc.perform(asUser(post("/admin/files/" + file.getId() + "/approve"), moderator));

        var events = auditEventRepository.findAll();
        assertThat(events).anySatisfy(e -> {
            assertThat(e.getAction()).isEqualTo(AuditAction.FILE_APPROVED);
            assertThat(e.getActor().getId()).isEqualTo(moderator.getId());
            assertThat(e.getTargetType()).isEqualTo(AuditTargetType.FILE);
            assertThat(e.getTargetId()).isEqualTo(file.getId());
            assertThat(e.getMetadata()).isEqualTo("A Great Thesis");
        });
    }

    @Test
    void rejectingAFile_writesAFileRejectedEventWithTheReason() throws Exception {
        FileEntity file = pendingFile("A Sketchy Paper");

        mockMvc.perform(asUser(post("/admin/files/" + file.getId() + "/reject"), moderator)
                .param("reason", "Missing abstract"));

        var events = auditEventRepository.findAll();
        assertThat(events).anySatisfy(e -> {
            assertThat(e.getAction()).isEqualTo(AuditAction.FILE_REJECTED);
            assertThat(e.getReason()).isEqualTo("Missing abstract");
        });
    }

    @Test
    void changingRole_writesARoleChangedEventWithPreviousAndNewValues() throws Exception {
        mockMvc.perform(asUser(post("/admin/users/" + uploader.getId() + "/role"), admin)
                .param("role", "MODERATOR"));

        var events = auditEventRepository.findAll();
        assertThat(events).anySatisfy(e -> {
            assertThat(e.getAction()).isEqualTo(AuditAction.ROLE_CHANGED);
            assertThat(e.getActor().getId()).isEqualTo(admin.getId());
            assertThat(e.getTargetType()).isEqualTo(AuditTargetType.USER);
            assertThat(e.getTargetId()).isEqualTo(uploader.getId());
            assertThat(e.getPreviousValue()).isEqualTo("USER");
            assertThat(e.getNewValue()).isEqualTo("MODERATOR");
        });
    }

    @Test
    void search_filtersByActorEmailCaseInsensitively() {
        auditEventRepository.save(AuditEvent.builder(AuditAction.LOGIN_SUCCESS).actor(uploader).build());
        auditEventRepository.save(AuditEvent.builder(AuditAction.LOGIN_SUCCESS).actor(moderator).build());

        Page<AuditEvent> results = auditEventRepository.search(
                "STUDENT@eac.edu.ph", null, null, null, null, PageRequest.of(0, 10));

        assertThat(results.getContent()).hasSize(1);
        assertThat(results.getContent().get(0).getActorEmail()).isEqualTo(uploader.getEmail());
    }

    @Test
    void search_filtersByAction() {
        auditEventRepository.save(AuditEvent.builder(AuditAction.LOGIN_SUCCESS).actor(uploader).build());
        auditEventRepository.save(AuditEvent.builder(AuditAction.LOGOUT).actor(uploader).build());

        Page<AuditEvent> results = auditEventRepository.search(
                null, AuditAction.LOGOUT, null, null, null, PageRequest.of(0, 10));

        assertThat(results.getContent()).hasSize(1);
        assertThat(results.getContent().get(0).getAction()).isEqualTo(AuditAction.LOGOUT);
    }

    @Test
    void search_filtersByTargetType() {
        auditEventRepository.save(AuditEvent.builder(AuditAction.ROLE_CHANGED).actor(admin)
                .target(AuditTargetType.USER, uploader.getId()).build());
        auditEventRepository.save(AuditEvent.builder(AuditAction.DEPARTMENT_AUTO_APPROVE_TOGGLED).actor(admin)
                .target(AuditTargetType.DEPARTMENT, department.getId()).build());

        Page<AuditEvent> results = auditEventRepository.search(
                null, null, AuditTargetType.DEPARTMENT, null, null, PageRequest.of(0, 10));

        assertThat(results.getContent()).hasSize(1);
        assertThat(results.getContent().get(0).getTargetType()).isEqualTo(AuditTargetType.DEPARTMENT);
    }

    @Test
    void search_filtersByDateRange() {
        Instant now = Instant.now();
        AuditEvent old = auditEventRepository.save(AuditEvent.builder(AuditAction.LOGIN_SUCCESS).actor(uploader).build());
        // AuditEvent.createdAt is set at construction time (Instant.now())
        // and has no setter - directly overwrite it via the repository for
        // this test only, to simulate an event from outside the query window.
        setCreatedAt(old.getId(), now.minus(10, ChronoUnit.DAYS));

        auditEventRepository.save(AuditEvent.builder(AuditAction.LOGIN_SUCCESS).actor(uploader).build());

        Page<AuditEvent> results = auditEventRepository.search(
                null, null, null, now.minus(1, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS), PageRequest.of(0, 10));

        assertThat(results.getContent()).hasSize(1);
    }

    @Test
    void search_withNoFilters_returnsEverythingNewestFirst() {
        auditEventRepository.save(AuditEvent.builder(AuditAction.LOGIN_SUCCESS).actor(uploader).build());
        auditEventRepository.save(AuditEvent.builder(AuditAction.LOGOUT).actor(uploader).build());
        auditEventRepository.save(AuditEvent.builder(AuditAction.ROLE_CHANGED).actor(admin)
                .target(AuditTargetType.USER, uploader.getId()).build());

        Page<AuditEvent> results = auditEventRepository.search(null, null, null, null, null, PageRequest.of(0, 10));

        assertThat(results.getTotalElements()).isEqualTo(3);
    }

    /**
     * Test-only helper - AuditEvent has no setter for createdAt by design
     * (append-only, see its class comment) and the column is
     * updatable=false, so Hibernate silently excludes it from any
     * save()-driven UPDATE regardless of what the in-memory field holds.
     * Going straight to JDBC is the only way to simulate an old event for
     * the date-range filter test.
     */
    private void setCreatedAt(Long eventId, Instant createdAt) {
        jdbcTemplate.update("update audit_events set created_at = ? where id = ?",
                java.sql.Timestamp.from(createdAt), eventId);
    }
}
