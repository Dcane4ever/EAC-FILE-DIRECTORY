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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers RegistrationController's /resend-verification endpoint - the
 * companion to PasswordResetControllerTest, same anti-enumeration, cooldown,
 * and CSRF-in-tests concerns apply here (see that class's header comment).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("junit")
class ResendVerificationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private EmailVerificationTokenRepository tokenRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ph.edu.eac.filedirectory.security.ratelimit.RateLimiter rateLimiter;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private FileRepository fileRepository;

    @MockitoBean
    private JavaMailSender mailSender;

    private AppUser unverifiedUser;

    @BeforeEach
    void setUp() {
        // RateLimiter is a singleton shared across the whole (cached)
        // Spring test context - without resetting it, /resend-verification's
        // per-IP rate limit could bleed state in from other test classes
        // that also POST there, since MockMvc requests all share one "IP".
        rateLimiter.reset();
        // The H2 database is shared (same JVM, DB_CLOSE_DELAY=-1) across
        // every test class using the "junit" profile - clear every table
        // that references app_users, not just this class's own, or
        // deleteAll() below can trip a leftover foreign-key row from a
        // different test class's run.
        tokenRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
        auditEventRepository.deleteAll();
        fileRepository.deleteAll();
        userRepository.deleteAll();
        unverifiedUser = userRepository.save(AppUser.registerManually(
                "maria.santos@eac.edu.ph", "Maria Santos", passwordEncoder.encode("SomePassword1")));
        // registerManually already leaves emailVerified=false, kept explicit here for clarity.
        unverifiedUser.setEmailVerified(false);
        userRepository.save(unverifiedUser);
    }

    @Test
    void resend_forUnverifiedAccount_issuesNewToken() throws Exception {
        mockMvc.perform(post("/resend-verification").with(csrf()).param("email", unverifiedUser.getEmail()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("infoMessage",
                        "If an unverified account exists for " + unverifiedUser.getEmail() + ", a new verification link has been sent."));

        assertThat(tokenRepository.findAll()).hasSize(1);
        assertThat(tokenRepository.findAll().get(0).getUser().getId()).isEqualTo(unverifiedUser.getId());
    }

    @Test
    void resend_forAlreadyVerifiedAccount_givesSameMessageButIssuesNoToken() throws Exception {
        unverifiedUser.setEmailVerified(true);
        userRepository.save(unverifiedUser);

        mockMvc.perform(post("/resend-verification").with(csrf()).param("email", unverifiedUser.getEmail()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("infoMessage",
                        "If an unverified account exists for " + unverifiedUser.getEmail() + ", a new verification link has been sent."));

        // Already-verified accounts don't need a new link - and the response
        // text must not reveal that distinction to the caller.
        assertThat(tokenRepository.findAll()).isEmpty();
    }

    @Test
    void resend_forUnknownEmail_givesSameMessageAndIssuesNoToken() throws Exception {
        mockMvc.perform(post("/resend-verification").with(csrf()).param("email", "nobody@eac.edu.ph"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("infoMessage",
                        "If an unverified account exists for nobody@eac.edu.ph, a new verification link has been sent."));

        assertThat(tokenRepository.findAll()).isEmpty();
    }

    @Test
    void resend_whileALiveTokenAlreadyExists_doesNotIssueASecondOne() throws Exception {
        tokenRepository.save(new EmailVerificationToken(
                UUID.randomUUID().toString(), unverifiedUser, Instant.now().plus(24, ChronoUnit.HOURS)));

        mockMvc.perform(post("/resend-verification").with(csrf()).param("email", unverifiedUser.getEmail()));

        // The cooldown should block issuing a second token moments after the first.
        assertThat(tokenRepository.findAll()).hasSize(1);
    }

    @Test
    void resend_afterPriorTokenExpired_issuesAFreshOne() throws Exception {
        EmailVerificationToken expired = tokenRepository.save(new EmailVerificationToken(
                UUID.randomUUID().toString(), unverifiedUser, Instant.now().minus(1, ChronoUnit.MINUTES)));

        mockMvc.perform(post("/resend-verification").with(csrf()).param("email", unverifiedUser.getEmail()));

        assertThat(tokenRepository.findAll()).hasSize(2);
        assertThat(tokenRepository.findByToken(expired.getToken()).orElseThrow().isValid()).isFalse();
    }
}
