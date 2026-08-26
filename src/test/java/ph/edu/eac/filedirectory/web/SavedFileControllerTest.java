package ph.edu.eac.filedirectory.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
import ph.edu.eac.filedirectory.saved.SavedFileRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("junit")
class SavedFileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private FileRepository fileRepository;

    @Autowired
    private SavedFileRepository savedFileRepository;

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
    private AppUser reader;
    private Department department;
    private Category category;

    @BeforeEach
    void setUp() {
        rateLimiter.reset();
        savedFileRepository.deleteAll();
        auditEventRepository.deleteAll();
        notificationRepository.deleteAll();
        fileRepository.deleteAll();
        emailVerificationTokenRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
        userRepository.deleteAll();

        uploader = userRepository.save(verifiedUser("uploader@eac.edu.ph", "Uploader One", Role.USER));
        reader = userRepository.save(verifiedUser("reader@eac.edu.ph", "Reader One", Role.USER));

        department = departmentRepository.findAll().stream().findFirst()
                .orElseGet(() -> departmentRepository.save(new Department(1, "ENGR", "School of Engineering")));
        category = categoryRepository.findAll().stream().findFirst()
                .orElseGet(() -> categoryRepository.save(new Category("Thesis", "fa-graduation-cap")));
    }

    @AfterEach
    void tearDown() {
        savedFileRepository.deleteAll();
    }

    @Test
    void approvedFile_canBeSavedAndRemovedBySignedInUser() throws Exception {
        FileEntity file = uploadRealPdf("Saved Thesis");
        file.setStatus(FileStatus.APPROVED);
        fileRepository.save(file);

        mockMvc.perform(asUser(post("/files/" + file.getId() + "/save"), reader).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/files/" + file.getId()));

        assertThat(savedFileRepository.existsByUserAndFile(reader, file)).isTrue();

        mockMvc.perform(asUser(post("/files/" + file.getId() + "/unsave"), reader).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/files/" + file.getId()));

        assertThat(savedFileRepository.existsByUserAndFile(reader, file)).isFalse();
    }

    @Test
    void pendingFile_cannotBeSavedByUnrelatedUser() throws Exception {
        FileEntity file = uploadRealPdf("Pending Thesis");

        mockMvc.perform(asUser(post("/files/" + file.getId() + "/save"), reader).with(csrf()))
                .andExpect(status().isForbidden());

        assertThat(savedFileRepository.existsByUserAndFile(reader, file)).isFalse();
    }

    @Test
    void savedFilesPage_listsCurrentUsersSavedFiles() throws Exception {
        FileEntity file = uploadRealPdf("Library Thesis");
        file.setStatus(FileStatus.APPROVED);
        fileRepository.save(file);

        mockMvc.perform(asUser(post("/files/" + file.getId() + "/save"), reader).with(csrf()));

        mockMvc.perform(asUser(get("/saved-files"), reader))
                .andExpect(status().isOk())
                .andExpect(view().name("saved-files"))
                .andExpect(model().attribute("savedCount", 1L));
    }

    private AppUser verifiedUser(String email, String name, Role role) {
        AppUser user = AppUser.registerManually(email, name, passwordEncoder.encode("SomePassword1"));
        user.setEmailVerified(true);
        user.setRole(role);
        return user;
    }

    private FileEntity uploadRealPdf(String title) throws Exception {
        MockMultipartFile multipartFile = new MockMultipartFile(
                "file", "thesis.pdf", "application/pdf", "%PDF-1.7\nSaved file test.".getBytes());
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
}
