package ph.edu.eac.filedirectory.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import ph.edu.eac.filedirectory.user.AppUser;
import ph.edu.eac.filedirectory.user.AppUserRepository;

import java.time.Instant;

/**
 * Loads the Google profile after OAuth2 login, rejects any account whose
 * email is not on the allowed @eac.edu.ph domain, and auto-provisions an
 * AppUser record on first successful login.
 */
@Service
public class EacOAuth2UserService extends DefaultOAuth2UserService {

    private final AppUserRepository userRepository;
    private final String allowedDomain;

    public EacOAuth2UserService(AppUserRepository userRepository,
                                 @Value("${eac.allowed-email-domain}") String allowedDomain) {
        this.userRepository = userRepository;
        this.allowedDomain = allowedDomain.toLowerCase();
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);

        String email = oAuth2User.getAttribute("email");
        Boolean emailVerified = oAuth2User.getAttribute("email_verified");
        String sub = oAuth2User.getAttribute("sub");
        String name = oAuth2User.getAttribute("name");
        String picture = oAuth2User.getAttribute("picture");

        if (email == null || !isAllowedDomain(email) || Boolean.FALSE.equals(emailVerified)) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("domain_not_allowed"),
                    "Only @" + allowedDomain + " accounts may sign in.");
        }

        AppUser user = userRepository.findByGoogleSub(sub)
                .or(() -> userRepository.findByEmail(email))
                .orElseGet(() -> new AppUser(sub, email, name, picture));

        user.setFullName(name);
        user.setPictureUrl(picture);
        user.setLastLoginAt(Instant.now());
        userRepository.save(user);

        return new EacOAuth2User(oAuth2User, user);
    }

    private boolean isAllowedDomain(String email) {
        String lower = email.toLowerCase();
        return lower.endsWith("@" + allowedDomain);
    }
}
