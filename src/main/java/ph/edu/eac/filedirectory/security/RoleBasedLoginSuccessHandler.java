package ph.edu.eac.filedirectory.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import ph.edu.eac.filedirectory.user.Role;

import java.io.IOException;

/**
 * After a successful /login, sends MODERATOR/ADMIN accounts to the admin
 * dashboard (/admin/home) and everyone else to the regular student landing
 * page (/home). Used by both the student-facing /login form and the
 * separate /admin/login page (see AdminAuthController) - they share this
 * one authentication path, only the login page shown before submitting
 * differs.
 */
public class RoleBasedLoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                         Authentication authentication) throws IOException, ServletException {
        boolean isStaff = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_" + Role.ADMIN) || a.equals("ROLE_" + Role.MODERATOR));

        setDefaultTargetUrl(isStaff ? "/admin/home" : "/home");
        setAlwaysUseDefaultTargetUrl(true);
        super.onAuthenticationSuccess(request, response, authentication);
    }
}
