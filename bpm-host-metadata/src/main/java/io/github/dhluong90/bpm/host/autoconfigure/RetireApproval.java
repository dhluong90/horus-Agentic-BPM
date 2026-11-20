package io.github.dhluong90.bpm.host.autoconfigure;

import java.util.Locale;
import org.springframework.core.env.Environment;

/**
 * The shared host setting {@code horus.bpm.lifecycle.retire-approval} (PD-080, PD-102): who may retire a version in
 * effect, for rule sets and process definitions alike. Read by every engine starter through {@link #from(Environment)}
 * and published as {@value #META_SETTING} in each engine module's {@code /api/bpm/meta} settings.
 */
public enum RetireApproval {

    /** One approver with a mandatory reason. The default. */
    SINGLE,
    /** A second approver confirms; the requester never confirms; the version serves until confirmed. */
    FOUR_EYES;

    /** The host property. */
    public static final String PROPERTY = "horus.bpm.lifecycle.retire-approval";
    /** The key in each engine module's {@code /api/bpm/meta} settings. */
    public static final String META_SETTING = "lifecycle.retire-approval";

    /** @return the property value, {@code single} or {@code four-eyes} */
    public String value() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * @param environment the host environment
     * @return the setting; absent means {@link #SINGLE}
     * @throws IllegalStateException for any other value, naming the property and the allowed values
     */
    public static RetireApproval from(Environment environment) {
        String raw = environment.getProperty(PROPERTY);
        if (raw == null || raw.isBlank()) {
            return SINGLE;
        }
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "single" -> SINGLE;
            case "four-eyes" -> FOUR_EYES;
            default -> throw new IllegalStateException(PROPERTY + " must be single (default) or four-eyes, was '" + raw
                    + "'");
        };
    }
}
