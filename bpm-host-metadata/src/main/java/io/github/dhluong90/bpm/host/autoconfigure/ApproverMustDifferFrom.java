package io.github.dhluong90.bpm.host.autoconfigure;

import java.util.Locale;
import org.springframework.core.env.Environment;

/**
 * The shared host setting {@code horus.bpm.lifecycle.approver-must-differ-from} (PD-096): whom the approver of a rule
 * set version or a process definition version must differ from. One property for the whole host, read by every engine
 * starter through {@link #from(Environment)}, and published by each engine as {@value #META_SETTING} in its module
 * settings in {@code /api/bpm/meta}.
 */
public enum ApproverMustDifferFrom {

    /** Every maker of the version: the importer or creator, every editor and the requester. The default. */
    MAKERS,
    /** The requester only. A relaxation, never the default: logged at WARN at start-up and recorded on every approval. */
    REQUESTER;

    /** The host property. */
    public static final String PROPERTY = "horus.bpm.lifecycle.approver-must-differ-from";
    /** The key in each engine module's {@code /api/bpm/meta} settings. */
    public static final String META_SETTING = "lifecycle.approver-must-differ-from";

    /** @return the property value, {@code makers} or {@code requester} */
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Reads the setting; absent means {@link #MAKERS}.
     *
     * @param environment the host environment
     * @return the setting
     * @throws IllegalStateException for any other value, naming the property and the allowed values, so start-up stops
     */
    public static ApproverMustDifferFrom from(Environment environment) {
        String raw = environment.getProperty(PROPERTY);
        if (raw == null || raw.isBlank()) {
            return MAKERS;
        }
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "makers" -> MAKERS;
            case "requester" -> REQUESTER;
            default -> throw new IllegalStateException(PROPERTY + " must be makers (default) or requester, was '" + raw
                    + "'");
        };
    }
}
