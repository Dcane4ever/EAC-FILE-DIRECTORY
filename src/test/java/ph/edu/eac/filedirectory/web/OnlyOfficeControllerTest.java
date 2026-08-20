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
import ph.edu.eac.filedirectory.onlyoffice.OnlyOfficeService;
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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc-level authorization tests for the ONLYOFFICE HTTP surface (see
 * OnlyOfficeController) - the plain-unit-level config/key/JWT generation
 * logic itself is covered by OnlyOfficeServiceTest instead; this class only
 * cares about "does the endpoint enforce the right thing", not "is the
 * config shaped correctly".
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("junit")
class OnlyOfficeControllerTest {

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
    private OnlyOfficeService onlyOfficeService;

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

    /** ZIP-signature-prefixed bytes so Phase 3's FileTypeValidator magic-byte check accepts it as a genuine .pptx, same reasoning as FilePreviewTest's PDF-signature fixture. */
    private FileEntity uploadPptx(String title) throws Exception {
        byte[] content = concat(new byte[]{0x50, 0x4B, 0x03, 0x04}, ("A fake pptx body: " + title).getBytes());
        MockMultipartFile multipartFile = new MockMultipartFile("file", "slides.pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation", content);
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

    private byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder asUser(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder, AppUser actingAs) {
        return builder.with(SecurityMockMvcRequestPostProcessors.user(new EacUserDetails(actingAs)));
    }

    // --- /onlyoffice-config ---

    @Test
    void approvedFile_configIsReachableByAnySignedInUser() throws Exception {
        FileEntity file = uploadPptx("Approved Slides");
        file.setStatus(FileStatus.APPROVED);
        fileRepository.save(file);

        mockMvc.perform(asUser(get("/files/" + file.getId() + "/onlyoffice-config"), otherUser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serverUrl").exists())
                .andExpect(jsonPath("$.config.documentType").value("slide"))
                .andExpect(jsonPath("$.config.document.permissions.edit").value(false))
                .andExpect(jsonPath("$.config.document.permissions.download").value(false))
                .andExpect(jsonPath("$.config.token").exists());
    }

    @Test
    void pendingFile_configIsBlockedForAnUnrelatedUser() throws Exception {
        // uploadPptx leaves the file PENDING by default (no auto-approve
        // configured on the test department) - see requireViewable.
        FileEntity file = uploadPptx("Pending Slides");

        mockMvc.perform(asUser(get("/files/" + file.getId() + "/onlyoffice-config"), otherUser))
                .andExpect(status().isForbidden());
    }

    @Test
    void pendingFile_configIsReachableByItsOwnUploader() throws Exception {
        FileEntity file = uploadPptx("My Own Pending Slides");

        mockMvc.perform(asUser(get("/files/" + file.getId() + "/onlyoffice-config"), uploader))
                .andExpect(status().isOk());
    }

    @Test
    void pendingFile_configIsReachableByAModerator() throws Exception {
        FileEntity file = uploadPptx("Pending Slides For Review");

        mockMvc.perform(asUser(get("/files/" + file.getId() + "/onlyoffice-config"), moderator))
                .andExpect(status().isOk());
    }

    @Test
    void anUnauthenticatedRequest_toConfig_isRejected() throws Exception {
        FileEntity file = uploadPptx("Anonymous Config Attempt");
        file.setStatus(FileStatus.APPROVED);
        fileRepository.save(file);

        mockMvc.perform(get("/files/" + file.getId() + "/onlyoffice-config"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void aNonExistentFileId_configReturns404() throws Exception {
        mockMvc.perform(asUser(get("/files/999999/onlyoffice-config"), otherUser))
                .andExpect(status().isNotFound());
    }

    @Test
    void aPdfFile_configReturns404_becauseOnlyOfficeIsNotForPdf() throws Exception {
        // PDF.js already handles PDFs (see file-detail.html) - ONLYOFFICE
        // is only wired for Office formats, see FileEntity.isOfficePreviewable.
        byte[] content = "%PDF-1.7\nNot an office document.".getBytes();
        MockMultipartFile multipartFile = new MockMultipartFile("file", "thesis.pdf", "application/pdf", content);
        mockMvc.perform(multipart("/upload")
                .file(multipartFile)
                .param("title", "A Real PDF")
                .param("description", "desc")
                .param("departmentId", String.valueOf(department.getId()))
                .param("categoryId", String.valueOf(category.getId()))
                .with(SecurityMockMvcRequestPostProcessors.user(new EacUserDetails(uploader)))
                .with(csrf()));
        FileEntity file = fileRepository.findAll().stream()
                .filter(f -> f.getTitle().equals("A Real PDF")).findFirst().orElseThrow();
        file.setStatus(FileStatus.APPROVED);
        fileRepository.save(file);

        mockMvc.perform(asUser(get("/files/" + file.getId() + "/onlyoffice-config"), otherUser))
                .andExpect(status().isNotFound());
    }

    // --- /onlyoffice-content ---

    @Test
    void aFreshlyMintedToken_letsTheContentEndpointServeTheFile() throws Exception {
        FileEntity file = uploadPptx("Content Fetch Happy Path");
        file.setStatus(FileStatus.APPROVED);
        fileRepository.save(file);

        String url = onlyOfficeService.generateSecureDocumentUrl(file);
        String token = url.substring(url.indexOf("token=") + "token=".length());

        // No authentication at all - matches how the real Document Server calls this (see class comment).
        mockMvc.perform(get("/files/" + file.getId() + "/onlyoffice-content").param("token", token))
                .andExpect(status().isOk());
    }

    @Test
    void anInvalidToken_isRejectedAs404() throws Exception {
        FileEntity file = uploadPptx("Content Fetch Bad Token");
        file.setStatus(FileStatus.APPROVED);
        fileRepository.save(file);

        mockMvc.perform(get("/files/" + file.getId() + "/onlyoffice-content").param("token", "not-a-real-token"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aTokenIssuedForADifferentFile_isRejectedAs404() throws Exception {
        FileEntity fileA = uploadPptx("Content Fetch File A");
        FileEntity fileB = uploadPptx("Content Fetch File B");
        fileA.setStatus(FileStatus.APPROVED);
        fileB.setStatus(FileStatus.APPROVED);
        fileRepository.save(fileA);
        fileRepository.save(fileB);

        String urlForA = onlyOfficeService.generateSecureDocumentUrl(fileA);
        String tokenForA = urlForA.substring(urlForA.indexOf("token=") + "token=".length());

        // tokenForA belongs to fileA, but the URL here names fileB's id.
        mockMvc.perform(get("/files/" + fileB.getId() + "/onlyoffice-content").param("token", tokenForA))
                .andExpect(status().isNotFound());
    }

    @Test
    void aNonExistentFileId_contentReturns404RegardlessOfToken() throws Exception {
        mockMvc.perform(get("/files/999999/onlyoffice-content").param("token", "irrelevant"))
                .andExpect(status().isNotFound());
    }
}
