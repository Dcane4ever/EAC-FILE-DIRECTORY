package ph.edu.eac.filedirectory.onlyoffice;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed config for the ONLYOFFICE integration - see application.properties'
 * "ONLYOFFICE preview" section for what each value means and where its
 * env var comes from. Two URLs are deliberately kept separate rather than
 * reusing one "onlyoffice URL" for both purposes:
 *
 *   - {@link #url()}: what the user's BROWSER loads the viewer API script
 *     from (DocsAPI.DocEditor's JS). Must be reachable from wherever people
 *     actually sit - a public/LAN address, not a Docker-internal one.
 *   - {@link #appBaseUrl()}: what the DOCUMENT SERVER (a separate backend
 *     service, not the browser) uses to fetch the file bytes and deliver
 *     its callback. Must be reachable from wherever ONLYOFFICE itself runs,
 *     which is not always the same network path as the browser's - see
 *     OnlyOfficeService for how this is used.
 *
 * In the common case (both running on one host, ONLYOFFICE just on a
 * different port) these end up being the same value, which is exactly why
 * application.properties defaults appBaseUrl to eac.app.base-url rather
 * than requiring it to be configured twice.
 */
@ConfigurationProperties(prefix = "onlyoffice")
public record OnlyOfficeProperties(
        String url,
        Jwt jwt,
        String appBaseUrl,
        long documentTokenTtlMinutes
) {
    public record Jwt(boolean enabled, String secret) {
    }
}
