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
import ph.edu.eac.filedirectory.audit.AuditEventRepository;
import ph.edu.eac.filedirectory.file.FileEntity;
import ph.edu.eac.filedirectory.file.FileRepository;
import ph.edu.eac.filedirectory.file.FileStatus;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers Phase 8's /files/{id}/preview-content endpoint: the actual
 * backend-authorization piece the roadmap calls out explicitly ("do not
 * rely on JavaScript deterrents... actual file security must come from
 * backend authorization"). Uses the real /upload endpoint (same pattern as
 * DuplicateUploadTest) to get a genuine file on disk with a genuine
 * FileEntity, rather than fabricating one with a fake path.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("junit")
class FilePreviewTest {

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

    @MockitoBean
    private JavaMailSender mailSender;

    private AppUser uploader;
    private AppUser otherUser;
    private AppUser moderator;
    private Department department;
    private Category category;

    private static final byte[] PDF_CONTENT = "%PDF-1.7\nA real preview test file.".getBytes();

    @BeforeEach
    void setUp() {
        rateLimiter.reset();
        auditEventRepository.deleteAll();
        notificationRepository.deleteAll();
        fileRepository.deleteAll();
        emailVerificationTokenRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
        userRepository.deleteAll();

        uploader = userRepository.save(verifiedUser("uploader@eac.edu.ph", "Uploader One", Role.USER));
        otherUser = userRepository.save(verifiedUser("other@eac.edu.ph", "Other User", Role.USER));
        moderator = userRepository.save(verifiedUser("mod@eac.edu.ph", "Moderator One", Role.MODERATOR));

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

    private FileEntity uploadRealPdf(String title) throws Exception {
        MockMultipartFile multipartFile = new MockMultipartFile("file", "thesis.pdf", "application/pdf", PDF_CONTENT);
        mockMvc.perform(multipart("/upload")
                .file(multipartFile)
                .param("title", title)
                .param("description", "A test file")
                .param("departmentId", String.valueOf(department.getId()))
                .param("categoryId", String.valueOf(category.getId()))
                .with(SecurityMockMvcRequestPostProcessors.user(new EacUserDetails(uploader)))
                .with(csrf()));
        return fileRepository.findAll().stream()
                .filter(f -> f.getTitle().equals(title))
                .findFirst().orElseThrow();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder asUser(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder, AppUser actingAs) {
        return builder.with(SecurityMockMvcRequestPostProcessors.user(new EacUserDetails(actingAs)));
    }

    @Test
    void approvedFile_previewIsReachableByAnySignedInUser() throws Exception {
        FileEntity file = uploadRealPdf("Approved Thesis");
        file.setStatus(FileStatus.APPROVED);
        fileRepository.save(file);

        mockMvc.perform(asUser(get("/files/" + file.getId() + "/preview-content"), otherUser))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().doesNotExist("Content-Disposition"));
    }

    @Test
    void pendingFile_previewIsBlockedForAnUnrelatedUser() throws Exception {
        // uploadRealPdf leaves the file PENDING by default (no auto-approve
        // configured on the test department) - see requireViewable.
        FileEntity file = uploadRealPdf("Pending Thesis");

        mockMvc.perform(asUser(get("/files/" + file.getId() + "/preview-content"), otherUser))
                .andExpect(status().isForbidden());
    }

    @Test
    void pendingFile_previewIsReachableByItsOwnUploader() throws Exception {
        FileEntity file = uploadRealPdf("My Own Pending Thesis");

        mockMvc.perform(asUser(get("/files/" + file.getId() + "/preview-content"), uploader))
                .andExpect(status().isOk());
    }

    @Test
    void pendingFile_previewIsReachableByAModerator() throws Exception {
        FileEntity file = uploadRealPdf("Pending Thesis For Review");

        mockMvc.perform(asUser(get("/files/" + file.getId() + "/preview-content"), moderator))
                .andExpect(status().isOk());
    }

    @Test
    void previewContent_doesNotIncrementDownloadCountOrWriteADownloadAuditEvent() throws Exception {
        FileEntity file = uploadRealPdf("Not A Real Download");
        file.setStatus(FileStatus.APPROVED);
        fileRepository.save(file);
        long auditCountBefore = auditEventRepository.count();

        mockMvc.perform(asUser(get("/files/" + file.getId() + "/preview-content"), otherUser));

        FileEntity reloaded = fileRepository.findById(file.getId()).orElseThrow();
        assertThat(reloaded.getDownloadCount()).isZero();
        assertThat(auditEventRepository.count()).isEqualTo(auditCountBefore);
    }

    @Test
    void anUnauthenticatedRequest_isRejected() throws Exception {
        FileEntity file = uploadRealPdf("Anonymous Access Attempt");
        file.setStatus(FileStatus.APPROVED);
        fileRepository.save(file);

        mockMvc.perform(get("/files/" + file.getId() + "/preview-content"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void aNonExistentFileId_returns404() throws Exception {
        mockMvc.perform(asUser(get("/files/999999/preview-content"), otherUser))
                .andExpect(status().isNotFound());
    }
}
