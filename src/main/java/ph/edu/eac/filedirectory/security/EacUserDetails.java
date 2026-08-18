package ph.edu.eac.filedirectory.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import ph.edu.eac.filedirectory.user.AppUser;

import java.util.Collection;
import java.util.List;

/**
 * Wraps our own AppUser record (id, role, etc.) as the authenticated
 * principal, so controllers/templates can access it via
 * @AuthenticationPrincipal EacUserDetails. Login is email+password only,
 * restricted to @eac.edu.ph - see PasswordAuthenticationProvider.
 */
public class EacUserDetails implements UserDetails {

    private final AppUser appUser;

    public EacUserDetails(AppUser appUser) {
        this.appUser = appUser;
    }

    public AppUser getAppUser() {
        return appUser;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + appUser.getRole().name()));
    }

    @Override
    public String getPassword() {
        return appUser.getPasswordHash();
    }

    @Override
    public String getUsername() {
        return appUser.getEmail();
    }

    @Override
    public boolean isEnabled() {
        return appUser.isEmailVerified();
    }
}
