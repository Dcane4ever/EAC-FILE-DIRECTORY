package ph.edu.eac.filedirectory.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ph.edu.eac.filedirectory.access.AccessGrantTokenRepository;
import ph.edu.eac.filedirectory.access.AccessRequestRepository;
import ph.edu.eac.filedirectory.audit.AuditAction;
import ph.edu.eac.filedirectory.audit.AuditEventRepository;
import ph.edu.eac.filedirectory.file.FileEntity;
import ph.edu.eac.filedirectory.file.FileRepository;
import ph.edu.eac.filedirectory.file.FileStatus;
import ph.edu.eac.filedirectory.file.FileVersionRepository;
import ph.edu.eac.filedirectory.file.FileStorageService;
import ph.edu.eac.filedirectory.notification.NotificationRepository;
import ph.edu.eac.filedirectory.maintenance.OrphanCleanupEntryRepository;
import ph.edu.eac.filedirectory.maintenance.OrphanCleanupStatus;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("junit")
class StorageMaintenanceActionTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private FileRepository fileRepository;
    @Autowired private FileVersionRepository fileVersionRepository;
    @Autowired private AppUserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private AccessRequestRepository accessRequestRepository;
    @Autowired private AccessGrantTokenRepository accessGrantTokenRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private AuditEventRepository auditEventRepository;
    @Autowired private EmailVerificationTokenRepository emailVerificationTokenRepository;
    @Autowired private PasswordResetTokenRepository passwordResetTokenRepository;
    @Autowired private OrphanCleanupEntryRepository orphanCleanupEntryRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private FileStorageService storageService;

    @MockitoBean private JavaMailSender mailSender;

    private AppUser admin;
    private AppUser moderator;
    private AppUser uploader;
    private Department department;
    private Category category;

    @BeforeEach
    void setUp() {
        accessGrantTokenRepository.deleteAll();
        accessRequestRepository.deleteAll();
        notificationRepository.deleteAll();
        orphanCleanupEntryRepository.deleteAll();
        fileVersionRepository.deleteAll();
        fileRepository.deleteAll();
        emailVerificationTokenRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
        auditEventRepository.deleteAll();
        userRepository.deleteAll();

        admin = userRepository.save(verifiedUser("admin@eac.edu.ph", "Admin", Role.ADMIN));
        moderator = userRepository.save(verifiedUser("moderator@eac.edu.ph", "Moderator", Role.MODERATOR));
        uploader = userRepository.save(verifiedUser("uploader@eac.edu.ph", "Uploader", Role.USER));
        department = departmentRepository.findAll().stream().findFirst()
                .orElseGet(() -> departmentRepository.save(new Department(1, "ENGR", "School of Engineering")));
        category = categoryRepository.findAll().stream().findFirst()
                .orElseGet(() -> categoryRepository.save(new Category("Thesis", "file")));
    }

    @Test
    void adminCanArchiveAMissingCurrentFileAndTheActionIsAudited() throws Exception {
        FileEntity file = missingFile("Missing thesis");

        mockMvc.perform(post("/admin/maintenance/files/{id}/archive-missing", file.getId())
                        .with(user(new EacUserDetails(admin))).with(csrf())
                        .param("reason", "Storage scan confirmed the disk file is absent."))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/maintenance"));

        FileEntity archived = fileRepository.findById(file.getId()).orElseThrow();
        assertThat(archived.getStatus()).isEqualTo(FileStatus.ARCHIVED);
        assertThat(archived.getArchiveReason()).isEqualTo("Storage maintenance: Storage scan confirmed the disk file is absent.");
        assertThat(auditEventRepository.findAll()).anySatisfy(event -> {
            assertThat(event.getAction()).isEqualTo(AuditAction.FILE_ARCHIVED);
            assertThat(event.getTargetId()).isEqualTo(file.getId());
        });
    }

    @Test
    void moderatorCannotUseTheAdminOnlyMaintenanceArchiveAction() throws Exception {
        FileEntity file = missingFile("Missing report");

        mockMvc.perform(post("/admin/maintenance/files/{id}/archive-missing", file.getId())
                        .with(user(new EacUserDetails(moderator))).with(csrf())
                        .param("reason", "Should not be allowed."))
                .andExpect(status().isForbidden());

        assertThat(fileRepository.findById(file.getId()).orElseThrow().getStatus()).isNotEqualTo(FileStatus.ARCHIVED);
    }

    @Test
    void archivedRecordWithMissingDiskFileCannotBeRestored() throws Exception {
        FileEntity file = missingFile("Archived missing report");
        file.setStatus(FileStatus.ARCHIVED);
        file.setStatusBeforeArchive(FileStatus.APPROVED);
        fileRepository.save(file);

        mockMvc.perform(post("/admin/files/{id}/restore", file.getId())
                        .with(user(new EacUserDetails(moderator))).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/files/" + file.getId()));

        assertThat(fileRepository.findById(file.getId()).orElseThrow().getStatus()).isEqualTo(FileStatus.ARCHIVED);
        assertThat(auditEventRepository.findAll()).noneMatch(event -> event.getAction() == AuditAction.FILE_RESTORED);
    }

    @Test
    void adminCanScheduleAnOrphanedPathOnlyWithARecordedBackupReference() throws Exception {
        java.nio.file.Path orphan = java.nio.file.Files.createTempFile("storage-maintenance-orphan-", ".txt");
        String storedPath = "TEST/" + orphan.getFileName();
        java.nio.file.Path storedFile = storageService.storageRoot().resolve(storedPath);
        try {
            java.nio.file.Path rootPath = storageService.storageRoot();
            java.nio.file.Files.createDirectories(rootPath.resolve("TEST"));
            java.nio.file.Files.move(orphan, storedFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            mockMvc.perform(post("/admin/maintenance/orphans/schedule")
                            .with(user(new EacUserDetails(admin))).with(csrf())
                            .param("storedPath", storedPath)
                            .param("sizeBytes", "1")
                            .param("reason", "Confirmed against current database references.")
                            .param("backupReference", "backup-2026-09-01"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/admin/maintenance"));

            var entry = orphanCleanupEntryRepository.findByStoredPath(storedPath).orElseThrow();
            assertThat(entry.getStatus()).isEqualTo(OrphanCleanupStatus.SCHEDULED);
            assertThat(entry.getBackupReference()).isEqualTo("backup-2026-09-01");
            assertThat(auditEventRepository.findAll()).anySatisfy(event ->
                    assertThat(event.getAction()).isEqualTo(AuditAction.STORAGE_ORPHAN_SCHEDULED));
        } finally {
            java.nio.file.Files.deleteIfExists(storedFile);
        }
    }

    @Test
    void maintenancePageRendersForAnAdmin() throws Exception {
        mockMvc.perform(get("/admin/maintenance").with(user(new EacUserDetails(admin))))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/maintenance"));
    }

    @Test
    void adminDashboardRendersItsLiveStorageOverview() throws Exception {
        mockMvc.perform(get("/admin/home").with(user(new EacUserDetails(admin))))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/home"));
    }

    private AppUser verifiedUser(String email, String name, Role role) {
        AppUser user = AppUser.registerManually(email, name, passwordEncoder.encode("SomePassword1"));
        user.setEmailVerified(true);
        user.setRole(role);
        return user;
    }

    private FileEntity missingFile(String title) {
        return fileRepository.save(new FileEntity(title, "Test file", uploader, department, null, null, category,
                "MISSING/never-present.pdf", "never-present.pdf", 1024L, "application/pdf", "checksum-" + title.hashCode()));
    }
}
