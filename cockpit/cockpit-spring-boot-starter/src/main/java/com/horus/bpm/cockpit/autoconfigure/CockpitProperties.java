package com.horus.bpm.cockpit.autoconfigure;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration of the embedded cockpit under {@code horus.bpm.cockpit}.
 *
 * @param enabled whether the host serves the cockpit at all
 * @param path path under which the host serves the cockpit, without a trailing slash
 * @param authMode how the cockpit authenticates to this host; only {@code host-session} in this release
 * @param hostLabel name of this service shown in the cockpit header; the host id when unset
 * @param environment short environment label shown next to the service name, for example {@code UAT}
 * @param defaultLocale language the cockpit opens in until the user picks one: {@code en} or {@code vi}
 */
@ConfigurationProperties("horus.bpm.cockpit")
public record CockpitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("/bpm/cockpit") String path,
        @DefaultValue("host-session") AuthMode authMode,
        String hostLabel,
        String environment,
        @DefaultValue("en") String defaultLocale) {

    private static final Pattern PATH = Pattern.compile("^(/[A-Za-z0-9._~-]+)+$");

    /** How the cockpit authenticates to this host. Standalone sign-in through OIDC arrives with standalone mode. */
    public enum AuthMode {
        /** The host's own session cookie authenticates the cockpit, on the same origin. */
        HOST_SESSION;

        String registryValue() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    public CockpitProperties {
        if (!PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("horus.bpm.cockpit.path must start with '/' and have no trailing slash, was '"
                    + path + "'");
        }
        if (!"en".equals(defaultLocale) && !"vi".equals(defaultLocale)) {
            throw new IllegalArgumentException("horus.bpm.cockpit.default-locale must be en or vi, was '" + defaultLocale + "'");
        }
        if (hostLabel != null && (hostLabel.isBlank() || hostLabel.length() > 80)) {
            throw new IllegalArgumentException("horus.bpm.cockpit.host-label must have 1 to 80 characters");
        }
        if (environment != null && environment.length() > 40) {
            throw new IllegalArgumentException("horus.bpm.cockpit.environment must have at most 40 characters");
        }
    }
}
