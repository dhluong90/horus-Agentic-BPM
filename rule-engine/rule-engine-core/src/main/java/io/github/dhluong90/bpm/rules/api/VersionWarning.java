package io.github.dhluong90.bpm.rules.api;

import java.util.Objects;

/**
 * Something about a version that does not stop it but that its author and its approver should see (RLE-049). The
 * engine computes warnings from the version's definition and the tenant's settings each time it returns the version,
 * so the approver sees the same warning the author saw at import.
 *
 * @param code what kind of warning; clients branch on it: {@value #UNMASKED_PERSONAL_DATA} or
 *     {@value #UNCLASSIFIED_INPUT}
 * @param input the input the warning is about
 * @param matchedName the configured personal-data name the input matched, for example {@code national id}; null for
 *     {@value #UNCLASSIFIED_INPUT}, which never looks at the name
 * @param message the warning in English
 * @param requiresAcknowledgement whether an approval must acknowledge this {@code {code, input}} pair (RLE-053,
 *     PD-104): its code is listed in {@code lifecycle.acknowledge-warnings} and its input is not masked only by the
 *     tenant's {@code decision-log.mask-default}. Computed by the same rule the approve check applies, so a client
 *     shows a checkbox for exactly these warnings
 */
public record VersionWarning(String code, String input, String matchedName, String message,
        boolean requiresAcknowledgement) {

    /** An input that declares no {@code mask} has a name that looks like personal data (PD-099). */
    public static final String UNMASKED_PERSONAL_DATA = "UNMASKED_PERSONAL_DATA";

    /**
     * An input declares neither {@code mask: true} nor {@code mask: false} (PD-146, #438). A reminder only: it never
     * blocks submission or approval, never needs an acknowledgement, and is silenced by
     * {@code horus.bpm.rules.classification.warn-unclassified: false}. The engine never infers sensitivity from the
     * input's name or values.
     */
    public static final String UNCLASSIFIED_INPUT = "UNCLASSIFIED_INPUT";

    /**
     * The warning codes {@code lifecycle.acknowledge-warnings} may list. {@value #UNCLASSIFIED_INPUT} is not one: it
     * never needs an acknowledgement.
     */
    public static final java.util.Set<String> CODES = java.util.Set.of(UNMASKED_PERSONAL_DATA);

    public VersionWarning {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }

    /**
     * Creates a warning that needs no acknowledgement.
     *
     * @param code see the record
     * @param input see the record
     * @param matchedName see the record
     * @param message see the record
     */
    public VersionWarning(String code, String input, String matchedName, String message) {
        this(code, input, matchedName, message, false);
    }

    /**
     * Returns a copy that says whether an approval must acknowledge it.
     *
     * @param required see {@link #requiresAcknowledgement()}
     * @return the warning
     */
    public VersionWarning withRequiresAcknowledgement(boolean required) {
        return new VersionWarning(code, input, matchedName, message, required);
    }

    /**
     * Returns the warning for an undeclared input whose name looks like personal data.
     *
     * @param input the input name
     * @param matchedName the configured name it matched
     * @param maskedByDefault whether the tenant's {@code decision-log.mask-default} masks it anyway
     * @return the warning
     */
    public static VersionWarning unmaskedPersonalData(String input, String matchedName, boolean maskedByDefault) {
        String logged = maskedByDefault
                ? "It is masked only because the tenant masks undeclared inputs, and would be logged in plain text if"
                        + " that setting changed."
                : "Its values will be stored in plain text in the decision log, which can never be changed.";
        return new VersionWarning(UNMASKED_PERSONAL_DATA, input, matchedName, "Input " + input
                + " looks like personal data (it matches \"" + matchedName + "\") but declares no mask. " + logged
                + " Declare mask: true, or mask: false if it holds no personal data.");
    }

    /**
     * Returns the reminder for an input with no classification (PD-146).
     *
     * @param input the input name
     * @return the warning; it needs no acknowledgement
     */
    public static VersionWarning unclassified(String input) {
        return new VersionWarning(UNCLASSIFIED_INPUT, input, null, "Input " + input + " is not classified: it "
                + "declares neither mask: true nor mask: false. Declare mask: true if it can hold personal data, or "
                + "mask: false if it cannot. This does not block approval.");
    }
}
