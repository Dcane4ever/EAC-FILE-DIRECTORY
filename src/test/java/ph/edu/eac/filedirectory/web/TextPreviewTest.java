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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers /files/{id}/text-content, the plain-text preview path for .txt
 * files (see FileEntity.isTextPreviewable, FileDetailController.textContent).
 * Same authorization-and-fixture pattern as FilePreviewTest (the PDF
 * equivalent) - a real upload through /upload, not a fabricated FileEntity.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("junit")
class TextPreviewTest {

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

    private FileEntity uploadTextFile(String title, byte[] content) throws Exception {
        MockMultipartFile multipartFile = new MockMultipartFile("file", "notes.txt", "text/plain", content);
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
    void approvedFile_textPreviewIsReachableByAnySignedInUser_andReturnsTheActualContent() throws Exception {
        FileEntity file = uploadTextFile("Approved Notes", "Line one\nLine two\n".getBytes());
        file.setStatus(FileStatus.APPROVED);
        fileRepository.save(file);

        mockMvc.perform(asUser(get("/files/" + file.getId() + "/text-content"), otherUser))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(content().string("Line one\nLine two\n"));
    }

    @Test
    void pendingFile_textPreviewIsBlockedForAnUnrelatedUser() throws Exception {
        FileEntity file = uploadTextFile("Pending Notes", "secret draft".getBytes());

        mockMvc.perform(asUser(get("/files/" + file.getId() + "/text-content"), otherUser))
                .andExpect(status().isForbidden());
    }

    @Test
    void pendingFile_textPreviewIsReachableByItsOwnUploader() throws Exception {
        FileEntity file = uploadTextFile("My Own Pending Notes", "draft".getBytes());

        mockMvc.perform(asUser(get("/files/" + file.getId() + "/text-content"), uploader))
                .andExpect(status().isOk());
    }

    @Test
    void pendingFile_textPreviewIsReachableByAModerator() throws Exception {
        FileEntity file = uploadTextFile("Pending Notes For Review", "draft".getBytes());

        mockMvc.perform(asUser(get("/files/" + file.getId() + "/text-content"), moderator))
                .andExpect(status().isOk());
    }

    @Test
    void textPreview_doesNotIncrementDownloadCountOrWriteADownloadAuditEvent() throws Exception {
        FileEntity file = uploadTextFile("Not A Real Download", "content".getBytes());
        file.setStatus(FileStatus.APPROVED);
        fileRepository.save(file);
        long auditCountBefore = auditEventRepository.count();

        mockMvc.perform(asUser(get("/files/" + file.getId() + "/text-content"), otherUser));

        FileEntity reloaded = fileRepository.findById(file.getId()).orElseThrow();
        assertThat(reloaded.getDownloadCount()).isZero();
        assertThat(auditEventRepository.count()).isEqualTo(auditCountBefore);
    }

    @Test
    void anUnauthenticatedRequest_isRejected() throws Exception {
        FileEntity file = uploadTextFile("Anonymous Access Attempt", "content".getBytes());
        file.setStatus(FileStatus.APPROVED);
        fileRepository.save(file);

        mockMvc.perform(get("/files/" + file.getId() + "/text-content"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void aNonExistentFileId_returns404() throws Exception {
        mockMvc.perform(asUser(get("/files/999999/text-content"), otherUser))
                .andExpect(status().isNotFound());
    }

    @Test
    void aFileLargerThanTheCap_isTruncatedWithAnExplicitNotice() throws Exception {
        // 1 MiB cap (see FileDetailController.TEXT_PREVIEW_MAX_BYTES) - two
        // bytes over it is enough to prove truncation without actually
        // building/uploading a multi-megabyte fixture.
        byte[] oversized = new byte[1_048_578];
        java.util.Arrays.fill(oversized, (byte) 'a');
        FileEntity file = uploadTextFile("Oversized Notes", oversized);
        file.setStatus(FileStatus.APPROVED);
        fileRepository.save(file);

        var result = mockMvc.perform(asUser(get("/files/" + file.getId() + "/text-content"), otherUser))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("[Preview truncated");
        // Genuinely truncated, not the notice tacked onto the full file:
        // the served body's non-notice portion is capped at 1 MiB, well
        // short of the 1,048,578-byte fixture.
        int noticeStart = body.indexOf("\n\n[Preview truncated");
        assertThat(noticeStart).isEqualTo(1_048_576);
    }
}
