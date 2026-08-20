package ph.edu.eac.filedirectory.devauth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * TEST-ONLY security chain, active only under the "test" profile. Replaces
 * SecurityConfig's real email+password login with a one-click @eac.edu.ph
 * email form at /dev-login (see DevLoginController) so the app can be
 * clicked through locally without registering + verifying an email every
 * time. Every other rule (admin gating, everything-else-authenticated)
 * mirrors SecurityConfig exactly, so what you click through here matches
 * production behavior.
 */
@Configuration
@EnableWebSecurity
@Profile("test")
public class DevSecurityConfig {

    @Bean
    public SecurityFilterChain devFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/dev-login", "/dev-login/**", "/register", "/verify", "/resend-verification", "/forgot-password", "/reset-password", "/access-denied", "/css/**", "/js/**", "/images/**", "/favicon.ico").permitAll()
                // See SecurityConfig's matching rule for why this can't sit
                // behind the normal authenticated() gate - same reasoning
                // applies in this profile too.
                .requestMatchers("/files/*/onlyoffice-content").permitAll()
                .requestMatchers("/admin/users/**", "/admin/departments/**").hasRole("ADMIN")
                .requestMatchers("/admin/**").hasAnyRole("ADMIN", "MODERATOR")
                .anyRequest().authenticated()
            )
            // No formLogin() here since there's no password check in this
            // profile - but an explicit entry point still gets an
            // unauthenticated visitor redirected to /dev-login instead of a
            // bare 403, matching SecurityConfig's real loginPage() behavior.
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(new org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint("/dev-login"))
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/dev-login?logout")
                .permitAll()
            );

        return http.build();
    }
}
