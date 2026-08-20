package ph.edu.eac.filedirectory.security.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Builds the key a RateLimiter check is scoped to. Prefers the
 * X-Forwarded-For header (set by a reverse proxy in front of the app) and
 * falls back to the raw remote address - this app has no reverse proxy in
 * its current local/LAN deployment, so in practice this is always
 * request.getRemoteAddr(), but the header check costs nothing and is
 * correct if that ever changes.
 */
public final class ClientKey {

    private ClientKey() {
    }

    public static String ipOf(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
