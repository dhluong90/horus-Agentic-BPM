package io.github.dhluong90.bpm.host.autoconfigure;

import java.util.Locale;
import org.springframework.core.env.Environment;

/**
 * The shared host setting {@code horus.bpm.lifecycle.retire-approval} (PD-080, PD-102): how many approvers a retirement
 * by hand of a rule set version or a process definition version needs. One property for the whole host, read by every
 * engine starter through {@link #from(Environment)}, and published by each engine as {@value #META_SETTING} in its
 * module settings in {@code /api/bpm/meta}.
 */
public enum RetireApproval {

    /** One approver with a reason retires the version. The default. */
    SINGLE;

    /** The host property. */
    public static final String PROPERTY = "horus.bpm.lifecycle.retire-approval";
    /** The key in each engine module's {@code /api/bpm/meta} settings. */
    public static final String META_SETTING = "lifecycle.retire-approval";

    /** @return the property value, {@code single} */
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Reads the setting; absent means {@link #SINGLE}.
     *
     * @param environment the host environment
     * @return the setting
     * @throws IllegalStateException for {@code four-eyes}, which needs four-eyes retirement (M3), and for any other
     *     value, naming the property, so start-up stops instead of a control that would do nothing
     */
    public static RetireApproval from(Environment environment) {
        String raw = environment.getProperty(PROPERTY);
        if (raw == null || raw.isBlank()) {
            return SINGLE;
        }
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "single" -> SINGLE;
            case "four-eyes" -> throw new IllegalStateException(PROPERTY + ": four-eyes is not available yet (four-eyes"
                    + " retirement arrives in M3); use single");
            default -> throw new IllegalStateException(PROPERTY + " must be single (default), was '" + raw + "'");
        };
    }
}
