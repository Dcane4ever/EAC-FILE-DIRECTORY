package ph.edu.eac.filedirectory.security;

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
import ph.edu.eac.filedirectory.security.ratelimit.RateLimiter;
import ph.edu.eac.filedirectory.user.AppUser;
import ph.edu.eac.filedirectory.user.AppUserRepository;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

/**
 * Covers PasswordAuthenticationProvider's per-account login rate limit -
 * the actual brute-force protection this phase adds. Every attempt (right
 * or wrong password) consumes a slot, so a correct password submitted after
 * the account is already rate-limited must still be rejected - that's the
 * whole point (see PasswordAuthenticationProvider's class comment).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("junit")
class LoginRateLimitTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private FileRepository fileRepository;

    @MockitoBean
    private JavaMailSender mailSender;

    private static final String CORRECT_PASSWORD = "CorrectPassword1";

    private AppUser user;

    @BeforeEach
    void setUp() {
        rateLimiter.reset();
        // Every table that FKs to app_users must be cleared before
        // userRepository.deleteAll() below, or that delete trips a FK
        // constraint against rows this or another test class (sharing the
        // same H2 instance) wrote - audit_events (actor) and files
        // (uploader) both qualify.
        auditEventRepository.deleteAll();
        fileRepository.deleteAll();
        userRepository.deleteAll();
        user = userRepository.save(AppUser.registerManually(
                "brute.force.target@eac.edu.ph", "Target Account", passwordEncoder.encode(CORRECT_PASSWORD)));
        user.setEmailVerified(true);
        userRepository.save(user);
    }

    private void attemptLogin(String password) throws Exception {
        mockMvc.perform(post("/login").with(csrf())
                .param("email", user.getEmail())
                .param("password", password));
    }

    @Test
    void repeatedWrongPasswords_eventuallyGetRateLimitedNotJustRejected() throws Exception {
        // The provider allows 10 attempts per 15-minute window - exhaust it
        // with wrong passwords, matching a real brute-force attempt.
        for (int i = 0; i < 10; i++) {
            attemptLogin("wrong-password-" + i);
        }

        // The 11th attempt - even with the CORRECT password - must still be
        // rejected, because the account itself is rate-limited, not just
        // each individual guess.
        mockMvc.perform(post("/login").with(csrf())
                        .param("email", user.getEmail())
                        .param("password", CORRECT_PASSWORD))
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void aFewWrongAttempts_doesNotBlockASubsequentCorrectLogin() throws Exception {
        // Well under the limit - normal "typo'd my password once" behavior
        // must not be punished.
        attemptLogin("wrong-once");
        attemptLogin("wrong-twice");

        mockMvc.perform(post("/login").with(csrf())
                        .param("email", user.getEmail())
                        .param("password", CORRECT_PASSWORD))
                .andExpect(redirectedUrl("/home"));
    }

    @Test
    void rateLimitIsScopedPerAccount_notGlobal() throws Exception {
        AppUser otherUser = userRepository.save(AppUser.registerManually(
                "unrelated.account@eac.edu.ph", "Unrelated Account", passwordEncoder.encode(CORRECT_PASSWORD)));
        otherUser.setEmailVerified(true);
        userRepository.save(otherUser);

        for (int i = 0; i < 10; i++) {
            attemptLogin("wrong-password-" + i);
        }
        // user's login is now rate-limited; otherUser's must be unaffected.
        mockMvc.perform(post("/login").with(csrf())
                        .param("email", otherUser.getEmail())
                        .param("password", CORRECT_PASSWORD))
                .andExpect(redirectedUrl("/home"));
    }
}
