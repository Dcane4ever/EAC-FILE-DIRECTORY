package ph.edu.eac.filedirectory.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ph.edu.eac.filedirectory.audit.AuditEventRepository;
import ph.edu.eac.filedirectory.file.FileRepository;
import ph.edu.eac.filedirectory.user.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Covers PasswordResetController's "forgot password" flow end to end against
 * a real (in-memory H2, see application-junit.properties) database - not a
 * unit test with mocked repositories, since the anti-enumeration behavior
 * (same response whether or not the account exists) is exactly the kind of
 * thing that's easy to accidentally break with a mock that hides the real
 * branch being taken.
 *
 * Every POST goes through .with(csrf()) - CSRF protection is Spring
 * Security's default and is genuinely active here (see SecurityConfig, no
 * .csrf(...) override exists). The real browser forms work without an
 * explicit token because thymeleaf-extras-springsecurity6 auto-injects a
 * hidden CSRF field into any <form th:action="..."> at render time; MockMvc
 * skips real template rendering, so tests have to attach a valid token
 * themselves via this post-processor instead.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("junit")
class PasswordResetControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private PasswordResetTokenRepository tokenRepository;

    @Autowired
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ph.edu.eac.filedirectory.security.ratelimit.RateLimiter rateLimiter;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private FileRepository fileRepository;

    // Real Gmail SMTP is never reachable in tests - JavaMailSender is mocked
    // so sends resolve instantly instead of timing out or requiring real
    // credentials. We assert on the resulting DB/token state, not on
    // whether an email was "sent".
    @MockitoBean
    private JavaMailSender mailSender;

    private AppUser existingUser;

    @BeforeEach
    void setUp() {
        // RateLimiter is a singleton shared across the whole (cached)
        // Spring test context - without resetting it, /forgot-password's
        // per-IP rate limit could bleed state in from other test classes
        // that also POST there, since MockMvc requests all share one "IP".
        rateLimiter.reset();
        // The H2 database is shared (same JVM, DB_CLOSE_DELAY=-1) across
        // every test class using the "junit" profile - clear every table
        // that references app_users, not just this class's own, or
        // deleteAll() below can trip a leftover foreign-key row from a
        // different test class's run.
        tokenRepository.deleteAll();
        emailVerificationTokenRepository.deleteAll();
        auditEventRepository.deleteAll();
        fileRepository.deleteAll();
        userRepository.deleteAll();
        existingUser = userRepository.save(AppUser.registerManually(
                "juan.delacruz@eac.edu.ph", "Juan Dela Cruz", passwordEncoder.encode("OldPassword1")));
        existingUser.setEmailVerified(true);
        userRepository.save(existingUser);
    }

    @Test
    void requestReset_forExistingAccount_issuesToken() throws Exception {
        mockMvc.perform(post("/forgot-password").with(csrf()).param("email", existingUser.getEmail()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("infoMessage",
                        "If an account exists for " + existingUser.getEmail() + ", a password reset link has been sent."));

        assertThat(tokenRepository.findAll()).hasSize(1);
        PasswordResetToken token = tokenRepository.findAll().get(0);
        assertThat(token.getUser().getId()).isEqualTo(existingUser.getId());
        assertThat(token.isValid()).isTrue();
    }

    @Test
    void requestReset_forUnknownEmail_givesSameMessageAndIssuesNoToken() throws Exception {
        mockMvc.perform(post("/forgot-password").with(csrf()).param("email", "nobody@eac.edu.ph"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("infoMessage",
                        "If an account exists for nobody@eac.edu.ph, a password reset link has been sent."));

        // Same response text as the existing-account case above - an
        // attacker probing addresses can't distinguish the two - and,
        // critically, no token/DB row was created for an email with no
        // matching account.
        assertThat(tokenRepository.findAll()).isEmpty();
    }

    @Test
    void requestReset_twiceQuickly_doesNotIssueASecondToken() throws Exception {
        mockMvc.perform(post("/forgot-password").with(csrf()).param("email", existingUser.getEmail()));
        mockMvc.perform(post("/forgot-password").with(csrf()).param("email", existingUser.getEmail()));

        // Cooldown (eac.mail.resend-cooldown-minutes) should prevent a
        // second live token from being minted moments after the first.
        assertThat(tokenRepository.findAll()).hasSize(1);
    }

    @Test
    void resetForm_withValidToken_showsForm() throws Exception {
        PasswordResetToken token = tokenRepository.save(new PasswordResetToken(
                UUID.randomUUID().toString(), existingUser, Instant.now().plus(1, ChronoUnit.HOURS)));

        mockMvc.perform(get("/reset-password").param("token", token.getToken()))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/reset-password"))
                .andExpect(model().attribute("token", token.getToken()));
    }

    @Test
    void resetForm_withExpiredToken_bouncesToForgotPasswordWithError() throws Exception {
        PasswordResetToken expired = tokenRepository.save(new PasswordResetToken(
                UUID.randomUUID().toString(), existingUser, Instant.now().minus(1, ChronoUnit.MINUTES)));

        mockMvc.perform(get("/reset-password").param("token", expired.getToken()))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/forgot-password"))
                .andExpect(model().attributeExists("errorMessage"));
    }

    @Test
    void resetForm_withUnknownToken_bouncesToForgotPasswordWithError() throws Exception {
        mockMvc.perform(get("/reset-password").param("token", "does-not-exist"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/forgot-password"))
                .andExpect(model().attributeExists("errorMessage"));
    }

    @Test
    void submitReset_withValidTokenAndMatchingPasswords_updatesPasswordAndConsumesToken() throws Exception {
        PasswordResetToken token = tokenRepository.save(new PasswordResetToken(
                UUID.randomUUID().toString(), existingUser, Instant.now().plus(1, ChronoUnit.HOURS)));

        mockMvc.perform(post("/reset-password")
                        .with(csrf())
                        .param("token", token.getToken())
                        .param("password", "BrandNewPassword1")
                        .param("confirmPassword", "BrandNewPassword1"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/login"))
                .andExpect(model().attributeExists("infoMessage"));

        AppUser reloaded = userRepository.findById(existingUser.getId()).orElseThrow();
        assertThat(passwordEncoder.matches("BrandNewPassword1", reloaded.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches("OldPassword1", reloaded.getPasswordHash())).isFalse();

        PasswordResetToken reloadedToken = tokenRepository.findByToken(token.getToken()).orElseThrow();
        assertThat(reloadedToken.isUsed()).isTrue();
        assertThat(reloadedToken.isValid()).isFalse();
    }

    @Test
    void submitReset_tokenCannotBeReusedAfterFirstReset() throws Exception {
        PasswordResetToken token = tokenRepository.save(new PasswordResetToken(
                UUID.randomUUID().toString(), existingUser, Instant.now().plus(1, ChronoUnit.HOURS)));

        mockMvc.perform(post("/reset-password")
                .with(csrf())
                .param("token", token.getToken())
                .param("password", "FirstNewPassword1")
                .param("confirmPassword", "FirstNewPassword1"));

        // Second attempt with the same (now-used) token must be rejected,
        // even though it hasn't hit its time-based expiry yet.
        mockMvc.perform(post("/reset-password")
                        .with(csrf())
                        .param("token", token.getToken())
                        .param("password", "SecondNewPassword1")
                        .param("confirmPassword", "SecondNewPassword1"))
                .andExpect(view().name("auth/forgot-password"))
                .andExpect(model().attributeExists("errorMessage"));

        AppUser reloaded = userRepository.findById(existingUser.getId()).orElseThrow();
        assertThat(passwordEncoder.matches("FirstNewPassword1", reloaded.getPasswordHash())).isTrue();
    }

    @Test
    void submitReset_withMismatchedConfirmation_rejectsWithoutChangingPassword() throws Exception {
        PasswordResetToken token = tokenRepository.save(new PasswordResetToken(
                UUID.randomUUID().toString(), existingUser, Instant.now().plus(1, ChronoUnit.HOURS)));

        mockMvc.perform(post("/reset-password")
                        .with(csrf())
                        .param("token", token.getToken())
                        .param("password", "BrandNewPassword1")
                        .param("confirmPassword", "DoesNotMatch1"))
                .andExpect(view().name("auth/reset-password"))
                .andExpect(model().attribute("errorMessage", "Passwords do not match."));

        AppUser reloaded = userRepository.findById(existingUser.getId()).orElseThrow();
        assertThat(passwordEncoder.matches("OldPassword1", reloaded.getPasswordHash())).isTrue();
    }

    @Test
    void submitReset_withShortPassword_rejectsWithoutChangingPassword() throws Exception {
        PasswordResetToken token = tokenRepository.save(new PasswordResetToken(
                UUID.randomUUID().toString(), existingUser, Instant.now().plus(1, ChronoUnit.HOURS)));

        mockMvc.perform(post("/reset-password")
                        .with(csrf())
                        .param("token", token.getToken())
                        .param("password", "short")
                        .param("confirmPassword", "short"))
                .andExpect(view().name("auth/reset-password"))
                .andExpect(model().attribute("errorMessage", "Password must be at least 8 characters."));

        AppUser reloaded = userRepository.findById(existingUser.getId()).orElseThrow();
        assertThat(passwordEncoder.matches("OldPassword1", reloaded.getPasswordHash())).isTrue();
    }
}
