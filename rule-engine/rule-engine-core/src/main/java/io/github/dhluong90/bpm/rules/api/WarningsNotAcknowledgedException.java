package io.github.dhluong90.bpm.rules.api;

import java.util.List;
import java.util.stream.Collectors;

/**
 * {@code 409 WARNINGS_NOT_ACKNOWLEDGED}: the version carries warnings whose codes the host lists in
 * {@code lifecycle.acknowledge-warnings}, and the approval did not acknowledge them (RLE-053, PD-104). Nothing is
 * published; approve again with the codes in {@code acknowledgedWarnings}.
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
     * @param warnings the warnings not acknowledged, at least one, in input order
     * @param setting the codes the host requires, {@code lifecycle.acknowledge-warnings}
     */
    public WarningsNotAcknowledgedException(String ruleSet, int version, List<Unacknowledged> warnings,
            List<String> setting) {
        super("WARNINGS_NOT_ACKNOWLEDGED", ruleSet + " v" + version + " carries warnings the approval must"
                + " acknowledge in acknowledgedWarnings: " + warnings.stream()
                        .map(w -> w.code() + " on " + w.input()).collect(Collectors.joining(", ")));
        if (warnings.isEmpty()) {
            throw new IllegalArgumentException("warnings must not be empty");
        }
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
     * Returns the warnings not acknowledged.
     *
     * @return at least one, in input order
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
