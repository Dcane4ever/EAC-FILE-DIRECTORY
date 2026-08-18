package ph.edu.eac.filedirectory.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;
import ph.edu.eac.filedirectory.user.AppUser;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Wraps the raw Google OAuth2User together with our own AppUser record
 * (id, role, etc.) so controllers/templates can access both via the
 * authenticated principal.
 */
public class EacOAuth2User implements OAuth2User {

    private final OAuth2User delegate;
    private final AppUser appUser;

    public EacOAuth2User(OAuth2User delegate, AppUser appUser) {
        this.delegate = delegate;
        this.appUser = appUser;
    }

    public AppUser getAppUser() {
        return appUser;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return delegate.getAttributes();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + appUser.getRole().name()));
    }

    @Override
    public String getName() {
        return appUser.getEmail();
    }
}
