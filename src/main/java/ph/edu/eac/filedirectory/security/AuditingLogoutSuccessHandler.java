package ph.edu.eac.filedirectory.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SimpleUrlLogoutSuccessHandler;
import ph.edu.eac.filedirectory.audit.AuditService;

import java.io.IOException;

/**
 * Records a LOGOUT audit event, then defers to the normal redirect behavior
 * (target URL is set per-profile in SecurityConfig/DevSecurityConfig via
 * setDefaultTargetUrl, same as before this existed). authentication may
 * already have a cleared/anonymous principal depending on filter order, so
 * this only logs when a real EacUserDetails principal is still present.
 */
public class AuditingLogoutSuccessHandler extends SimpleUrlLogoutSuccessHandler {

    private final AuditService auditService;

    public AuditingLogoutSuccessHandler(AuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response,
                                 Authentication authentication) throws IOException, ServletException {
        if (authentication != null && authentication.getPrincipal() instanceof EacUserDetails principal) {
            auditService.logout(principal.getAppUser(), request.getRemoteAddr());
        }
        super.onLogoutSuccess(request, response, authentication);
    }
}
