package io.github.dhluong90.bpm.host.autoconfigure;

import java.util.Locale;
import org.springframework.core.env.Environment;

/**
 * The shared host setting {@code horus.bpm.lifecycle.retire-last-version} (PD-101): what a retirement by hand may do to
 * the last version in effect of a rule set or a definition key. One property for the whole host, read by every engine
 * starter through {@link #from(Environment)}, and published by each engine as {@value #META_SETTING} in its module
 * settings in {@code /api/bpm/meta}.
 */
public enum RetireLastVersion {

    /** Allowed when the request confirms that no version will remain in effect. The default. */
    CONFIRM,
    /** Never by hand: a replacement must be approved first. */
    REFUSE;

    /** The host property. */
    public static final String PROPERTY = "horus.bpm.lifecycle.retire-last-version";
    /** The key in each engine module's {@code /api/bpm/meta} settings. */
    public static final String META_SETTING = "lifecycle.retire-last-version";

    /** @return the property value, {@code confirm} or {@code refuse} */
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Reads the setting; absent means {@link #CONFIRM}.
     *
     * @param environment the host environment
     * @return the setting
     * @throws IllegalStateException for {@code four-eyes}, which needs four-eyes retirement (M3), and for any other
     *     value, naming the property, so start-up stops instead of a control that would do nothing
     */
    public static RetireLastVersion from(Environment environment) {
        String raw = environment.getProperty(PROPERTY);
        if (raw == null || raw.isBlank()) {
            return CONFIRM;
        }
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "confirm" -> CONFIRM;
            case "refuse" -> REFUSE;
            case "four-eyes" -> throw new IllegalStateException(PROPERTY + ": four-eyes is not available yet (four-eyes"
                    + " retirement arrives in M3); use confirm or refuse");
            default -> throw new IllegalStateException(PROPERTY + " must be confirm (default) or refuse, was '" + raw
                    + "'");
        };
    }
}
