package ph.edu.eac.filedirectory.devauth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * TEST-ONLY security chain, active only under the "test" profile. Replaces
 * SecurityConfig's Google OAuth2 login with the simple @eac.edu.ph email
 * form at /dev-login (see DevLoginController) so the app can be exercised
 * locally without real Google Cloud OAuth credentials. Every other rule
 * (admin gating, everything-else-authenticated) mirrors SecurityConfig
 * exactly, so what you click through here matches production behavior.
 */
@Configuration
@EnableWebSecurity
@Profile("test")
public class DevSecurityConfig {

    @Bean
    public SecurityFilterChain devFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/dev-login", "/dev-login/**", "/access-denied", "/css/**", "/js/**", "/images/**", "/favicon.ico").permitAll()
                .requestMatchers("/admin/**").hasAnyRole("ADMIN", "MODERATOR")
                .anyRequest().authenticated()
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/dev-login?logout")
                .permitAll()
            );

        return http.build();
    }
}
