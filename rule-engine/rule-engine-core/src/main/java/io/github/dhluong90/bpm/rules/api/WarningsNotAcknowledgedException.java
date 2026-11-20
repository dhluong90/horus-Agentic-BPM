package io.github.dhluong90.bpm.rules.api;

import java.util.List;
import java.util.stream.Collectors;

/**
 * {@code 409 WARNINGS_NOT_ACKNOWLEDGED}: the pairs the approval acknowledged differ from the pairs the engine
 * recomputed under the settings in force, the warnings whose codes the host lists in
 * {@code lifecycle.acknowledge-warnings} (RLE-053, PD-104). A pair may be missing, or extra. Nothing is published;
 * show {@link #warnings()} again and let the approver accept exactly those.
 */
public final class WarningsNotAcknowledgedException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    @SuppressWarnings("serial") // an immutable List.copyOf of serializable records
    private final List<Unacknowledged> warnings;

    @SuppressWarnings("serial") // an immutable List.copyOf of strings
    private final List<String> setting;

    /**
     * Creates the exception.
     *
     * @param ruleSet the rule set name
     * @param version the version number
     * @param warnings the full current set of pairs that need an acknowledgement, in input order; empty when the
     *     approval sent pairs that no longer need one and nothing needs one now
     * @param setting the codes the host requires, {@code lifecycle.acknowledge-warnings}
     */
    public WarningsNotAcknowledgedException(String ruleSet, int version, List<Unacknowledged> warnings,
            List<String> setting) {
        super("WARNINGS_NOT_ACKNOWLEDGED", ruleSet + " v" + version + ": acknowledgedWarnings must name exactly"
                + " the warnings that need an acknowledgement now: " + (warnings.isEmpty() ? "none"
                        : warnings.stream().map(w -> w.code() + " on " + w.input()).collect(Collectors.joining(", "))));
        this.warnings = List.copyOf(warnings);
        this.setting = List.copyOf(setting);
    }

    /**
     * Returns the codes the host requires an acknowledgement for.
     *
     * @return {@code lifecycle.acknowledge-warnings}
     */
    public List<String> setting() {
        return setting;
    }

    /**
     * Returns the full current set of pairs that need an acknowledgement.
     *
     * @return the pairs, in input order; possibly empty
     */
    public List<Unacknowledged> warnings() {
        return warnings;
    }

    /**
     * One warning the approval did not acknowledge.
     *
     * @param code the warning code, for example {@code UNMASKED_PERSONAL_DATA}
     * @param input the input it is about
     * @param matchedName the personal-data name the input matched, for example {@code national id}, or null
     */
    public record Unacknowledged(String code, String input, String matchedName) implements java.io.Serializable {
    }
}
