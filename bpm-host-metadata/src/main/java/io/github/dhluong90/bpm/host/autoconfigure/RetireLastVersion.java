package io.github.dhluong90.bpm.host.autoconfigure;

import java.util.Locale;
import org.springframework.core.env.Environment;

/**
 * The shared host setting {@code horus.bpm.lifecycle.retire-last-version} (PD-101): what retiring the last version in
 * effect of a rule set or a process definition key needs. Read by every engine starter through
 * {@link #from(Environment)} and published as {@value #META_SETTING} in each engine module's {@code /api/bpm/meta}
 * settings.
 */
public enum RetireLastVersion {

    /** Allowed with an explicit {@code confirmNoVersionInEffect: true}; otherwise {@code 409 LAST_VERSION_IN_EFFECT}. The default. */
    CONFIRM,
    /** A second approver is needed. */
    FOUR_EYES,
    /** Never; a replacement must be approved first. */
    REFUSE;

    /** The host property. */
    public static final String PROPERTY = "horus.bpm.lifecycle.retire-last-version";
    /** The key in each engine module's {@code /api/bpm/meta} settings. */
    public static final String META_SETTING = "lifecycle.retire-last-version";

    /** @return the property value, {@code confirm}, {@code four-eyes} or {@code refuse} */
    public String value() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * @param environment the host environment
     * @return the setting; absent means {@link #CONFIRM}
     * @throws IllegalStateException for any other value, naming the property and the allowed values
     */
    public static RetireLastVersion from(Environment environment) {
        String raw = environment.getProperty(PROPERTY);
        if (raw == null || raw.isBlank()) {
            return CONFIRM;
        }
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "confirm" -> CONFIRM;
            case "four-eyes" -> FOUR_EYES;
            case "refuse" -> REFUSE;
            default -> throw new IllegalStateException(PROPERTY + " must be confirm (default), four-eyes or refuse, was '"
                    + raw + "'");
        };
    }
}
