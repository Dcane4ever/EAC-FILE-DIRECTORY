package ph.edu.eac.filedirectory.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import ph.edu.eac.filedirectory.audit.AuditService;
import ph.edu.eac.filedirectory.security.AuditingLogoutSuccessHandler;
import ph.edu.eac.filedirectory.security.PasswordAuthenticationProvider;
import ph.edu.eac.filedirectory.security.RoleBasedLoginSuccessHandler;

/**
 * Real, permanent security chain: email+password login/registration,
 * restricted to @eac.edu.ph and gated on email verification - see
 * RegistrationController / PasswordAuthenticationProvider. Disabled under
 * the "test" profile, where DevSecurityConfig takes over with a one-click
 * dev login instead - see ph.edu.eac.filedirectory.devauth.
 *
 * Two login pages share one authentication path: /login (student-facing)
 * and /admin/login (see AdminAuthController) both POST to the same
 * /login processing URL and the same PasswordAuthenticationProvider - only
 * the page shown before submitting differs. RoleBasedLoginSuccessHandler
 * then sends MODERATOR/ADMIN accounts to /admin/home and everyone else to
 * /home. An unauthenticated visit to an /admin/** page is bounced to
 * /admin/login instead of the student /login (see loginEntryPoint below).
 */
@Configuration
@EnableWebSecurity
@Profile("!test")
public class SecurityConfig {

    private final PasswordAuthenticationProvider passwordAuthenticationProvider;
    private final AuditService auditService;

    public SecurityConfig(PasswordAuthenticationProvider passwordAuthenticationProvider, AuditService auditService) {
        this.passwordAuthenticationProvider = passwordAuthenticationProvider;
        this.auditService = auditService;
    }

    @Bean
    public AuthenticationManager authenticationManager() {
        return new ProviderManager(passwordAuthenticationProvider);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/login", "/login/**", "/register", "/verify", "/resend-verification", "/forgot-password", "/reset-password", "/access-denied", "/css/**", "/js/**", "/images/**", "/favicon.ico").permitAll()
                .requestMatchers("/admin/login").permitAll()
                // ONLYOFFICE Document Server fetches a file's bytes as a
                // plain server-to-server HTTP GET - it does not carry the
                // viewing user's browser session, so this can't sit behind
                // the normal authenticated() gate below. Its own security
                // is a short-lived, single-file, cryptographically signed
                // token (see OnlyOfficeController/OnlyOfficeService) minted
                // only after /onlyoffice-config already ran the exact same
                // requireViewable check every other file-serving endpoint
                // uses - permitAll() here does not mean "anyone can fetch
                // any file", it means "this endpoint enforces its own
                // token-based authorization instead of a session-based one".
                .requestMatchers("/files/*/onlyoffice-content").permitAll()
                // User/role management and the auto-approve toggle are ADMIN-only
                // - a MODERATOR can approve uploads but should not be able to
                // promote/demote accounts or turn off moderation for a department.
                .requestMatchers("/admin/users/**", "/admin/departments/**").hasRole("ADMIN")
                .requestMatchers("/admin/**").hasAnyRole("ADMIN", "MODERATOR")
                .anyRequest().authenticated()
            )
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(loginEntryPoint())
            )
            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .usernameParameter("email")
                .passwordParameter("password")
                .successHandler(new RoleBasedLoginSuccessHandler())
                .failureUrl("/login?error")
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessHandler(auditingLogoutSuccessHandler("/login?logout"))
                .permitAll()
            );

        return http.build();
    }

    /**
     * An unauthenticated visit to any /admin/** URL redirects to /admin/login;
     * everything else redirects to the student /login - both pages post to
     * the same /login processing URL above.
     */
    private org.springframework.security.web.AuthenticationEntryPoint loginEntryPoint() {
        return DelegatingAuthenticationEntryPoint.builder()
                .addEntryPointFor(new LoginUrlAuthenticationEntryPoint("/admin/login"), PathPatternRequestMatcher.pathPattern("/admin/**"))
                .defaultEntryPoint(new LoginUrlAuthenticationEntryPoint("/login"))
                .build();
    }

    private AuditingLogoutSuccessHandler auditingLogoutSuccessHandler(String targetUrl) {
        AuditingLogoutSuccessHandler handler = new AuditingLogoutSuccessHandler(auditService);
        handler.setDefaultTargetUrl(targetUrl);
        return handler;
    }
}
