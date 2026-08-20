package ph.edu.eac.filedirectory.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ph.edu.eac.filedirectory.audit.AuditAction;
import ph.edu.eac.filedirectory.audit.AuditEventRepository;
import ph.edu.eac.filedirectory.file.FileRepository;
import ph.edu.eac.filedirectory.notification.NotificationRepository;
import ph.edu.eac.filedirectory.security.EacUserDetails;
import ph.edu.eac.filedirectory.security.ratelimit.RateLimiter;
import ph.edu.eac.filedirectory.settings.SystemSettingRepository;
import ph.edu.eac.filedirectory.settings.SystemSettingService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Covers Phase 6's duplicate-file detection end to end: a checksum match is
 * flagged and blocked by default, confirmDuplicate=true (the "upload
 * anyway" checkbox) lets it through, and SystemSetting.allowDuplicateUploads
 * disables the check entirely when an admin turns it on. Uses the real
 * /upload endpoint via MockMvc/multipart rather than unit-testing
 * FileRepository.findFirstByChecksum in isolation, since the interesting
 * behavior here is the controller's decision of what to do with a match,
 * not just whether the query itself works.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("junit")
class DuplicateUploadTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository userRepository;

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
    private AuditEventRepository auditEventRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private SystemSettingRepository systemSettingRepository;

    @Autowired
    private SystemSettingService systemSettingService;

    @MockitoBean
    private JavaMailSender mailSender;

    private AppUser uploader;
    private AppUser admin;
    private Department department;
    private Category category;

    private static final byte[] FILE_CONTENT = "%PDF-1.7\nSame content every time.".getBytes();
    // Different from FILE_CONTENT but still passes FileTypeValidator's PDF
    // magic-byte check (see Phase 3) - the point of this fixture is content
    // that's genuinely different, not content that fails an unrelated check.
    private static final byte[] DIFFERENT_FILE_CONTENT = "%PDF-1.7\nCompletely different content.".getBytes();

    @BeforeEach
    void setUp() {
        rateLimiter.reset();
        auditEventRepository.deleteAll();
        notificationRepository.deleteAll();
        fileRepository.deleteAll();
        emailVerificationTokenRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
        systemSettingRepository.deleteAll();
        userRepository.deleteAll();

        uploader = userRepository.save(verifiedUser("uploader@eac.edu.ph", "Uploader One", Role.USER));
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

    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder uploadRequest(
            String title, byte[] content) {
        MockMultipartFile file = new MockMultipartFile("file", "thesis.pdf", "application/pdf", content);
        return (org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder)
                multipart("/upload")
                        .file(file)
                        .param("title", title)
                        .param("description", "A test file")
                        .param("departmentId", String.valueOf(department.getId()))
                        .param("categoryId", String.valueOf(category.getId()))
                        .with(SecurityMockMvcRequestPostProcessors.user(new EacUserDetails(uploader)))
                        .with(csrf());
    }

    @Test
    void firstUploadOfItsContent_goesThroughNormally() throws Exception {
        mockMvc.perform(uploadRequest("Original Upload", FILE_CONTENT))
                .andExpect(status().is3xxRedirection());

        assertThat(fileRepository.count()).isEqualTo(1);
    }

    @Test
    void uploadingIdenticalContentAgain_isFlaggedInsteadOfStored() throws Exception {
        mockMvc.perform(uploadRequest("Original Upload", FILE_CONTENT));
        assertThat(fileRepository.count()).isEqualTo(1);

        mockMvc.perform(uploadRequest("A Different Title, Same Bytes", FILE_CONTENT))
                .andExpect(status().isOk())
                .andExpect(view().name("upload"))
                .andExpect(model().attributeExists("duplicateOf"));

        // Blocked, not stored - still exactly one file in the system.
        assertThat(fileRepository.count()).isEqualTo(1);
    }

    @Test
    void confirmDuplicateTrue_letsTheSecondUploadThrough() throws Exception {
        mockMvc.perform(uploadRequest("Original Upload", FILE_CONTENT));
        assertThat(fileRepository.count()).isEqualTo(1);

        mockMvc.perform(uploadRequest("Resubmission With Confirmation", FILE_CONTENT)
                        .param("confirmDuplicate", "true"))
                .andExpect(status().is3xxRedirection());

        assertThat(fileRepository.count()).isEqualTo(2);
    }

    @Test
    void differentContent_neverFlaggedRegardlessOfTitle() throws Exception {
        mockMvc.perform(uploadRequest("Original Upload", FILE_CONTENT))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(uploadRequest("Original Upload", DIFFERENT_FILE_CONTENT))
                .andExpect(status().is3xxRedirection());

        assertThat(fileRepository.count()).isEqualTo(2);
    }

    @Test
    void adminTogglingAllowDuplicates_skipsTheCheckEntirely() throws Exception {
        systemSettingService.setAllowDuplicateUploads(true);

        mockMvc.perform(uploadRequest("Original Upload", FILE_CONTENT));
        mockMvc.perform(uploadRequest("Duplicate, But Allowed Now", FILE_CONTENT))
                .andExpect(status().is3xxRedirection());

        assertThat(fileRepository.count()).isEqualTo(2);
    }

    @Test
    void togglingTheSetting_isPersistedAndAudited() throws Exception {
        assertThat(systemSettingService.isAllowDuplicateUploads()).isFalse();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/admin/users/settings/duplicate-uploads")
                        .param("allowed", "true")
                        .with(SecurityMockMvcRequestPostProcessors.user(new EacUserDetails(admin)))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(systemSettingService.isAllowDuplicateUploads()).isTrue();

        assertThat(auditEventRepository.findAll()).anySatisfy(e -> {
            assertThat(e.getAction()).isEqualTo(AuditAction.SYSTEM_SETTING_CHANGED);
            assertThat(e.getActor().getId()).isEqualTo(admin.getId());
            assertThat(e.getPreviousValue()).isEqualTo("false");
            assertThat(e.getNewValue()).isEqualTo("true");
        });
    }

    @Test
    void togglingToTheSameValue_doesNotWriteARedundantAuditEvent() throws Exception {
        // Already false by default - "changing" it to false again is a no-op.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/admin/users/settings/duplicate-uploads")
                .param("allowed", "false")
                .with(SecurityMockMvcRequestPostProcessors.user(new EacUserDetails(admin)))
                .with(csrf()));

        assertThat(auditEventRepository.findAll()).noneMatch(e -> e.getAction() == AuditAction.SYSTEM_SETTING_CHANGED);
    }
}
