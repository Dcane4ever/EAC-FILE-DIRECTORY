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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import ph.edu.eac.filedirectory.access.*;
import ph.edu.eac.filedirectory.audit.AuditEventRepository;
import ph.edu.eac.filedirectory.file.FileEntity;
import ph.edu.eac.filedirectory.file.FileRepository;
import ph.edu.eac.filedirectory.file.FileStatus;
import ph.edu.eac.filedirectory.notification.NotificationRepository;
import ph.edu.eac.filedirectory.security.EacUserDetails;
import ph.edu.eac.filedirectory.security.ratelimit.RateLimiter;
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

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers Phase 9's access-request workflow end to end: requesting access
 * (self-request/duplicate-pending rejection), the uploader approve/deny
 * path (grant token creation, notification, email, audit), the moderator
 * fallback's two independent gates (elapsed time AND the system setting),
 * the grant-token download endpoint's three checks (see
 * FileAccessController), and the sweep job (AccessRequestService.
 * expireStaleGrants). Uses the real /upload endpoint for a genuine
 * FileEntity on disk, same pattern as FilePreviewTest/DuplicateUploadTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("junit")
class AccessRequestTest {

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
    private AccessRequestRepository accessRequestRepository;

    @Autowired
    private AccessGrantTokenRepository accessGrantTokenRepository;

    @Autowired
    private AccessRequestService accessRequestService;

    @Autowired
    private SystemSettingService systemSettingService;

    @MockitoBean
    private JavaMailSender mailSender;

    private AppUser uploader;
    private AppUser requester;
    private AppUser otherRequester;
    private AppUser moderator;
    private Department department;
    private Category category;

    @BeforeEach
    void setUp() {
        rateLimiter.reset();
        systemSettingService.setAllowModeratorAccessRequestFallback(false);
        accessGrantTokenRepository.deleteAll();
        accessRequestRepository.deleteAll();
        auditEventRepository.deleteAll();
        notificationRepository.deleteAll();
        fileRepository.deleteAll();
        emailVerificationTokenRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
        userRepository.deleteAll();

        uploader = userRepository.save(verifiedUser("uploader@eac.edu.ph", "Uploader One", Role.USER));
        requester = userRepository.save(verifiedUser("requester@eac.edu.ph", "Requester One", Role.USER));
        otherRequester = userRepository.save(verifiedUser("other-requester@eac.edu.ph", "Other Requester", Role.USER));
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

    private FileEntity uploadApprovedPdf(String title) throws Exception {
        return uploadApprovedPdf(title, null);
    }

    private FileEntity uploadApprovedPdf(String title, AccessDecisionPolicy policy) throws Exception {
        // Content varies by title (not a shared constant) so two different
        // fixture files in the same test never collide with Phase 6's
        // duplicate-checksum detection, which would silently short-circuit
        // the second "upload" instead of creating a distinct FileEntity.
        byte[] content = ("%PDF-1.7\nAn access-request test file: " + title).getBytes();
        MockMultipartFile multipartFile = new MockMultipartFile("file", "thesis.pdf", "application/pdf", content);
        MockMultipartHttpServletRequestBuilder request = multipart("/upload")
                .file(multipartFile)
                .param("title", title)
                .param("description", "A test file")
                .param("departmentId", String.valueOf(department.getId()))
                .param("categoryId", String.valueOf(category.getId()));
        if (policy != null) {
            request = request.param("accessDecisionPolicy", policy.name());
        }
        mockMvc.perform(request
                .with(SecurityMockMvcRequestPostProcessors.user(new EacUserDetails(uploader)))
                .with(csrf()));
        FileEntity file = fileRepository.findAll().stream()
                .filter(f -> f.getTitle().equals(title))
                .findFirst().orElseThrow();
        file.setStatus(FileStatus.APPROVED);
        return fileRepository.save(file);
    }

    private MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder builder, AppUser actingAs) {
        return builder.with(SecurityMockMvcRequestPostProcessors.user(new EacUserDetails(actingAs)));
    }

    // --- Requesting access ---

    @Test
    void aSignedInUser_canRequestAccessToAnApprovedFileTheyDontOwn() throws Exception {
        FileEntity file = uploadApprovedPdf("Requestable Thesis");

        mockMvc.perform(asUser(post("/files/" + file.getId() + "/request-access"), requester).with(csrf()))
                .andExpect(status().is3xxRedirection());

        AccessRequest saved = accessRequestRepository
                .findFirstByFileAndRequesterAndStatus(file, requester, AccessRequestStatus.PENDING)
                .orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(AccessRequestStatus.PENDING);
        assertThat(notificationRepository.count()).isEqualTo(1);
    }

    @Test
    void anUploader_cannotRequestAccessToTheirOwnUpload() throws Exception {
        FileEntity file = uploadApprovedPdf("Own Upload");

        AccessRequestService.RequestResult result = accessRequestService.request(file, uploader);

        assertThat(result.created()).isFalse();
        assertThat(accessRequestRepository.count()).isZero();
    }

    @Test
    void aDuplicatePendingRequest_isRejected() throws Exception {
        FileEntity file = uploadApprovedPdf("Double Requested Thesis");

        AccessRequestService.RequestResult first = accessRequestService.request(file, requester);
        AccessRequestService.RequestResult second = accessRequestService.request(file, requester);

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(accessRequestRepository.count()).isEqualTo(1);
    }

    // --- Per-file auto-decision policy ---

    @Test
    void aFileWithAutoApprovePolicy_resolvesTheRequestImmediatelyWithAValidGrantToken() throws Exception {
        FileEntity file = uploadApprovedPdf("Auto Approve Policy File", AccessDecisionPolicy.AUTO_APPROVE);
        assertThat(file.getAccessDecisionPolicy()).isEqualTo(AccessDecisionPolicy.AUTO_APPROVE);

        AccessRequestService.RequestResult result = accessRequestService.request(file, requester);

        assertThat(result.created()).isTrue();
        AccessRequest accessRequest = accessRequestRepository
                .findFirstByFileAndRequesterAndStatus(file, requester, AccessRequestStatus.PENDING).orElse(null);
        assertThat(accessRequest).isNull(); // never sat PENDING at all

        AccessRequest resolved = accessRequestRepository.findByFile_UploaderOrderByCreatedAtDesc(uploader, org.springframework.data.domain.PageRequest.of(0, 10))
                .getContent().stream().filter(r -> r.getFile().getId().equals(file.getId())).findFirst().orElseThrow();
        assertThat(resolved.getStatus()).isEqualTo(AccessRequestStatus.APPROVED);
        assertThat(resolved.getDecidedBy().getId()).isEqualTo(uploader.getId());
        assertThat(accessGrantTokenRepository.findByAccessRequest(resolved)).isPresent();
        // Notified twice: once for the request itself, once for the auto-approval.
        assertThat(notificationRepository.count()).isEqualTo(2);
    }

    @Test
    void aFileWithAutoRejectPolicy_resolvesTheRequestImmediatelyAsDeniedWithNoGrantToken() throws Exception {
        FileEntity file = uploadApprovedPdf("Auto Reject Policy File", AccessDecisionPolicy.AUTO_REJECT);
        assertThat(file.getAccessDecisionPolicy()).isEqualTo(AccessDecisionPolicy.AUTO_REJECT);

        AccessRequestService.RequestResult result = accessRequestService.request(file, requester);

        assertThat(result.created()).isTrue();
        AccessRequest resolved = accessRequestRepository.findByFile_UploaderOrderByCreatedAtDesc(uploader, org.springframework.data.domain.PageRequest.of(0, 10))
                .getContent().stream().filter(r -> r.getFile().getId().equals(file.getId())).findFirst().orElseThrow();
        assertThat(resolved.getStatus()).isEqualTo(AccessRequestStatus.DENIED);
        assertThat(resolved.getDecisionNote()).isNotBlank();
        assertThat(accessGrantTokenRepository.findByAccessRequest(resolved)).isEmpty();
        assertThat(notificationRepository.count()).isEqualTo(2);
    }

    @Test
    void aFileWithManualPolicy_staysPendingAsBefore() throws Exception {
        FileEntity file = uploadApprovedPdf("Manual Policy File", AccessDecisionPolicy.MANUAL);
        assertThat(file.getAccessDecisionPolicy()).isEqualTo(AccessDecisionPolicy.MANUAL);

        accessRequestService.request(file, requester);

        AccessRequest accessRequest = accessRequestRepository
                .findFirstByFileAndRequesterAndStatus(file, requester, AccessRequestStatus.PENDING).orElseThrow();
        assertThat(accessRequest.getStatus()).isEqualTo(AccessRequestStatus.PENDING);
    }

    @Test
    void uploadWithNoPolicySpecified_defaultsToManual() throws Exception {
        FileEntity file = uploadApprovedPdf("No Policy Specified File");

        assertThat(file.getAccessDecisionPolicy()).isEqualTo(AccessDecisionPolicy.MANUAL);
    }

    // --- Editing the policy after upload (/my-uploads/{id}/access-policy) ---

    @Test
    void theUploader_canChangeTheAccessPolicyAfterUpload() throws Exception {
        FileEntity file = uploadApprovedPdf("Policy Changed After Upload");
        assertThat(file.getAccessDecisionPolicy()).isEqualTo(AccessDecisionPolicy.MANUAL);

        mockMvc.perform(asUser(post("/my-uploads/" + file.getId() + "/access-policy"), uploader)
                        .param("accessDecisionPolicy", "AUTO_APPROVE")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        FileEntity reloaded = fileRepository.findById(file.getId()).orElseThrow();
        assertThat(reloaded.getAccessDecisionPolicy()).isEqualTo(AccessDecisionPolicy.AUTO_APPROVE);
    }

    @Test
    void changingTheAccessPolicy_onlyAffectsRequestsMadeAfterTheChange_notAnAlreadyPendingOne() throws Exception {
        FileEntity file = uploadApprovedPdf("Policy Change Does Not Retroactively Resolve", AccessDecisionPolicy.MANUAL);
        accessRequestService.request(file, requester);
        AccessRequest existingRequest = accessRequestRepository
                .findFirstByFileAndRequesterAndStatus(file, requester, AccessRequestStatus.PENDING).orElseThrow();

        mockMvc.perform(asUser(post("/my-uploads/" + file.getId() + "/access-policy"), uploader)
                        .param("accessDecisionPolicy", "AUTO_APPROVE")
                        .with(csrf()));

        AccessRequest reloaded = accessRequestRepository.findById(existingRequest.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AccessRequestStatus.PENDING); // untouched by the policy change

        // A NEW request from a different requester, made after the change, does follow the new policy.
        accessRequestService.request(fileRepository.findById(file.getId()).orElseThrow(), otherRequester);
        AccessRequest newRequestResolved = accessRequestRepository.findByFile_UploaderOrderByCreatedAtDesc(uploader, org.springframework.data.domain.PageRequest.of(0, 10))
                .getContent().stream().filter(r -> r.getRequester().getId().equals(otherRequester.getId())).findFirst().orElseThrow();
        assertThat(newRequestResolved.getStatus()).isEqualTo(AccessRequestStatus.APPROVED);
    }

    @Test
    void aNonOwner_cannotChangeAnotherUsersFilesAccessPolicy() throws Exception {
        FileEntity file = uploadApprovedPdf("Not Your File To Configure");

        mockMvc.perform(asUser(post("/my-uploads/" + file.getId() + "/access-policy"), requester)
                        .param("accessDecisionPolicy", "AUTO_APPROVE")
                        .with(csrf()))
                .andExpect(status().isForbidden());

        FileEntity reloaded = fileRepository.findById(file.getId()).orElseThrow();
        assertThat(reloaded.getAccessDecisionPolicy()).isEqualTo(AccessDecisionPolicy.MANUAL);
    }

    // --- Uploader approve/deny ---

    @Test
    void theUploader_canApproveARequest_creatingAValidGrantTokenAndNotifications() throws Exception {
        FileEntity file = uploadApprovedPdf("Thesis To Approve");
        accessRequestService.request(file, requester);
        AccessRequest accessRequest = accessRequestRepository
                .findFirstByFileAndRequesterAndStatus(file, requester, AccessRequestStatus.PENDING).orElseThrow();

        AccessRequestService.RequestResult result = accessRequestService.approve(accessRequest, uploader);

        assertThat(result.created()).isTrue();
        AccessRequest reloaded = accessRequestRepository.findById(accessRequest.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AccessRequestStatus.APPROVED);
        assertThat(reloaded.getDecidedBy().getId()).isEqualTo(uploader.getId());

        AccessGrantToken token = accessGrantTokenRepository.findByAccessRequest(reloaded).orElseThrow();
        assertThat(token.isValid()).isTrue();
        assertThat(notificationRepository.count()).isEqualTo(2); // requested + approved
    }

    @Test
    void theUploader_canDenyARequestWithAReason() throws Exception {
        FileEntity file = uploadApprovedPdf("Thesis To Deny");
        accessRequestService.request(file, requester);
        AccessRequest accessRequest = accessRequestRepository
                .findFirstByFileAndRequesterAndStatus(file, requester, AccessRequestStatus.PENDING).orElseThrow();

        AccessRequestService.RequestResult result = accessRequestService.deny(accessRequest, uploader, "Not relevant to your program.");

        assertThat(result.created()).isTrue();
        AccessRequest reloaded = accessRequestRepository.findById(accessRequest.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AccessRequestStatus.DENIED);
        assertThat(reloaded.getDecisionNote()).isEqualTo("Not relevant to your program.");
        assertThat(accessGrantTokenRepository.findByAccessRequest(reloaded)).isEmpty();
    }

    @Test
    void anUnrelatedUser_cannotApproveSomeoneElsesUploadsRequest() throws Exception {
        FileEntity file = uploadApprovedPdf("Thesis Approved By Wrong Person");
        accessRequestService.request(file, requester);
        AccessRequest accessRequest = accessRequestRepository
                .findFirstByFileAndRequesterAndStatus(file, requester, AccessRequestStatus.PENDING).orElseThrow();

        AccessRequestService.RequestResult result = accessRequestService.approve(accessRequest, otherRequester);

        assertThat(result.created()).isFalse();
        assertThat(accessRequestRepository.findById(accessRequest.getId()).orElseThrow().getStatus())
                .isEqualTo(AccessRequestStatus.PENDING);
    }

    // --- Moderator fallback: both gates required ---

    @Test
    void aModerator_cannotDecideBeforeTheFallbackWindowElapses_evenWithSettingOn() throws Exception {
        systemSettingService.setAllowModeratorAccessRequestFallback(true);
        FileEntity file = uploadApprovedPdf("Fresh Request For Fallback Test");
        accessRequestService.request(file, requester);
        AccessRequest accessRequest = accessRequestRepository
                .findFirstByFileAndRequesterAndStatus(file, requester, AccessRequestStatus.PENDING).orElseThrow();
        // Freshly created - still well within the fallback window.

        assertThat(accessRequestService.canDecide(accessRequest, moderator)).isFalse();
    }

    @Test
    void aModerator_cannotDecideAfterTheWindowElapses_ifTheSettingIsOff() throws Exception {
        systemSettingService.setAllowModeratorAccessRequestFallback(false);
        FileEntity file = uploadApprovedPdf("Stale Request Setting Off");
        accessRequestService.request(file, requester);
        AccessRequest accessRequest = accessRequestRepository
                .findFirstByFileAndRequesterAndStatus(file, requester, AccessRequestStatus.PENDING).orElseThrow();
        backdateCreatedAt(accessRequest, Duration.ofDays(30));

        assertThat(accessRequestService.canDecide(accessRequest, moderator)).isFalse();
    }

    @Test
    void aModerator_canDecideOnceBothTheWindowHasElapsedAndTheSettingIsOn() throws Exception {
        systemSettingService.setAllowModeratorAccessRequestFallback(true);
        FileEntity file = uploadApprovedPdf("Stale Request Both Gates");
        accessRequestService.request(file, requester);
        AccessRequest accessRequest = accessRequestRepository
                .findFirstByFileAndRequesterAndStatus(file, requester, AccessRequestStatus.PENDING).orElseThrow();
        backdateCreatedAt(accessRequest, Duration.ofDays(30));
        // Re-fetch: the JDBC backdate above bypassed the persistence context,
        // so the in-memory accessRequest above still holds the old createdAt.
        accessRequest = accessRequestRepository.findById(accessRequest.getId()).orElseThrow();

        assertThat(accessRequestService.canDecide(accessRequest, moderator)).isTrue();

        AccessRequestService.RequestResult result = accessRequestService.approve(accessRequest, moderator);
        assertThat(result.created()).isTrue();
        assertThat(accessRequestRepository.findById(accessRequest.getId()).orElseThrow().getDecidedBy().getId())
                .isEqualTo(moderator.getId());
    }

    /** Backdates createdAt directly via SQL since the entity's createdAt column has no setter (deliberately immutable via the API). */
    private void backdateCreatedAt(AccessRequest accessRequest, Duration ago) {
        Instant backdated = Instant.now().minus(ago);
        jdbcTemplate.update("UPDATE access_requests SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(backdated), accessRequest.getId());
    }

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    // --- Grant-token download endpoint ---

    @Test
    void theGrantTokenDownload_worksForTheOriginalRequesterWithAValidToken() throws Exception {
        FileEntity file = uploadApprovedPdf("Grant Download Happy Path");
        accessRequestService.request(file, requester);
        AccessRequest accessRequest = accessRequestRepository
                .findFirstByFileAndRequesterAndStatus(file, requester, AccessRequestStatus.PENDING).orElseThrow();
        accessRequestService.approve(accessRequest, uploader);
        AccessGrantToken token = accessGrantTokenRepository.findByAccessRequest(accessRequest).orElseThrow();

        mockMvc.perform(asUser(get("/files/access-requests/" + accessRequest.getId() + "/download")
                        .param("token", token.getToken()), requester))
                .andExpect(status().isOk());

        AccessGrantToken reloaded = accessGrantTokenRepository.findById(token.getId()).orElseThrow();
        assertThat(reloaded.isUsed()).isTrue();
    }

    @Test
    void theGrantTokenDownload_rejectsAUserWhoIsNotTheOriginalRequester() throws Exception {
        FileEntity file = uploadApprovedPdf("Grant Download Wrong Person");
        accessRequestService.request(file, requester);
        AccessRequest accessRequest = accessRequestRepository
                .findFirstByFileAndRequesterAndStatus(file, requester, AccessRequestStatus.PENDING).orElseThrow();
        accessRequestService.approve(accessRequest, uploader);
        AccessGrantToken token = accessGrantTokenRepository.findByAccessRequest(accessRequest).orElseThrow();

        mockMvc.perform(asUser(get("/files/access-requests/" + accessRequest.getId() + "/download")
                        .param("token", token.getToken()), otherRequester))
                .andExpect(status().isForbidden());
    }

    @Test
    void theGrantTokenDownload_rejectsATokenRequestIdMismatch() throws Exception {
        FileEntity fileA = uploadApprovedPdf("Grant Download Mismatch A");
        FileEntity fileB = uploadApprovedPdf("Grant Download Mismatch B");
        accessRequestService.request(fileA, requester);
        accessRequestService.request(fileB, otherRequester);
        AccessRequest requestA = accessRequestRepository
                .findFirstByFileAndRequesterAndStatus(fileA, requester, AccessRequestStatus.PENDING).orElseThrow();
        AccessRequest requestB = accessRequestRepository
                .findFirstByFileAndRequesterAndStatus(fileB, otherRequester, AccessRequestStatus.PENDING).orElseThrow();
        accessRequestService.approve(requestA, uploader);
        accessRequestService.approve(requestB, uploader);
        AccessGrantToken tokenA = accessGrantTokenRepository.findByAccessRequest(
                accessRequestRepository.findById(requestA.getId()).orElseThrow()).orElseThrow();

        // tokenA belongs to requestA, but the URL names requestB's id.
        mockMvc.perform(asUser(get("/files/access-requests/" + requestB.getId() + "/download")
                        .param("token", tokenA.getToken()), requester))
                .andExpect(status().isNotFound());
    }

    @Test
    void theGrantTokenDownload_rejectsAnAlreadyUsedToken() throws Exception {
        FileEntity file = uploadApprovedPdf("Grant Download Reused Token");
        accessRequestService.request(file, requester);
        AccessRequest accessRequest = accessRequestRepository
                .findFirstByFileAndRequesterAndStatus(file, requester, AccessRequestStatus.PENDING).orElseThrow();
        accessRequestService.approve(accessRequest, uploader);
        AccessGrantToken token = accessGrantTokenRepository.findByAccessRequest(accessRequest).orElseThrow();

        mockMvc.perform(asUser(get("/files/access-requests/" + accessRequest.getId() + "/download")
                        .param("token", token.getToken()), requester))
                .andExpect(status().isOk());
        mockMvc.perform(asUser(get("/files/access-requests/" + accessRequest.getId() + "/download")
                        .param("token", token.getToken()), requester))
                .andExpect(status().isGone());
    }

    @Test
    void theGrantTokenDownload_rejectsAnUnknownToken() throws Exception {
        FileEntity file = uploadApprovedPdf("Grant Download Unknown Token");
        accessRequestService.request(file, requester);
        AccessRequest accessRequest = accessRequestRepository
                .findFirstByFileAndRequesterAndStatus(file, requester, AccessRequestStatus.PENDING).orElseThrow();
        accessRequestService.approve(accessRequest, uploader);

        mockMvc.perform(asUser(get("/files/access-requests/" + accessRequest.getId() + "/download")
                        .param("token", UUID.randomUUID().toString()), requester))
                .andExpect(status().isNotFound());
    }

    // --- Direct download narrowing (the "narrow it now" decision) ---

    @Test
    void aNonOwnerNonStaffUser_cannotDirectlyDownloadAnApprovedFile() throws Exception {
        FileEntity file = uploadApprovedPdf("Direct Download Narrowed");

        mockMvc.perform(asUser(get("/files/" + file.getId() + "/download"), requester))
                .andExpect(status().isForbidden());
    }

    @Test
    void theUploader_canStillDirectlyDownloadTheirOwnFile() throws Exception {
        FileEntity file = uploadApprovedPdf("Direct Download Owner Still Works");

        mockMvc.perform(asUser(get("/files/" + file.getId() + "/download"), uploader))
                .andExpect(status().isOk());
    }

    @Test
    void aModerator_canStillDirectlyDownloadAnyApprovedFile() throws Exception {
        FileEntity file = uploadApprovedPdf("Direct Download Moderator Still Works");

        mockMvc.perform(asUser(get("/files/" + file.getId() + "/download"), moderator))
                .andExpect(status().isOk());
    }

    // --- Sweep job ---

    @Test
    void expireStaleGrants_flipsApprovedRequestsWithExpiredUnusedTokensToExpired() throws Exception {
        FileEntity file = uploadApprovedPdf("Sweep Job Target");
        accessRequestService.request(file, requester);
        AccessRequest accessRequest = accessRequestRepository
                .findFirstByFileAndRequesterAndStatus(file, requester, AccessRequestStatus.PENDING).orElseThrow();
        accessRequestService.approve(accessRequest, uploader);
        backdateCreatedAt(accessRequest, Duration.ofDays(30));
        AccessGrantToken token = accessGrantTokenRepository.findByAccessRequest(
                accessRequestRepository.findById(accessRequest.getId()).orElseThrow()).orElseThrow();
        jdbcTemplate.update("UPDATE access_grant_tokens SET expires_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofHours(1))), token.getId());

        int expired = accessRequestService.expireStaleGrants();

        assertThat(expired).isEqualTo(1);
        assertThat(accessRequestRepository.findById(accessRequest.getId()).orElseThrow().getStatus())
                .isEqualTo(AccessRequestStatus.EXPIRED);
    }

    @Test
    void expireStaleGrants_leavesAStillValidApprovedGrantAlone() throws Exception {
        FileEntity file = uploadApprovedPdf("Sweep Job Should Skip This");
        accessRequestService.request(file, requester);
        AccessRequest accessRequest = accessRequestRepository
                .findFirstByFileAndRequesterAndStatus(file, requester, AccessRequestStatus.PENDING).orElseThrow();
        accessRequestService.approve(accessRequest, uploader);
        // Not backdated - grant token is still fresh and valid.

        int expired = accessRequestService.expireStaleGrants();

        assertThat(expired).isZero();
        assertThat(accessRequestRepository.findById(accessRequest.getId()).orElseThrow().getStatus())
                .isEqualTo(AccessRequestStatus.APPROVED);
    }
}
