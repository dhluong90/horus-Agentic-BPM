package io.github.dhluong90.bpm.rules.api;

import java.util.Objects;

/**
 * Something about a version that does not stop it but that its author and its approver should see (RLE-049). The
 * engine computes warnings from the version's definition and the tenant's settings each time it returns the version,
 * so the approver sees the same warning the author saw at import.
 *
 * @param code what kind of warning; clients branch on it. {@value #UNMASKED_PERSONAL_DATA} only, in this release
 * @param input the input the warning is about
 * @param matchedName the configured personal-data name the input matched, for example {@code national id}
 * @param message the warning in English
 */
public record VersionWarning(String code, String input, String matchedName, String message) {

    /** An input that declares no {@code mask} has a name that looks like personal data (PD-099). */
    public static final String UNMASKED_PERSONAL_DATA = "UNMASKED_PERSONAL_DATA";

    public VersionWarning {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(message, "message must not be null");
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
}
