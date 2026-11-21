package io.github.dhluong90.bpm.rules.api;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The result of checking an edited table of a draft as a save would, without storing anything (RLE-090).
 *
 * @param ruleSet the rule set name
 * @param version the draft's version number
 * @param valid whether a save of the same document would be accepted
 * @param problems the cell problems in document order, at most 100; empty when {@code valid}
 * @param problemsTruncated whether more problems were found than are listed
 * @param readings one reading per rule, in document order, when {@code valid} and the table is small enough to read;
 *     empty otherwise
 * @param readingsTruncated true when the document is valid but its readings are left out because the table has more
 *     rows than one request reads ({@link #TABLE_TOO_LARGE}); the validation itself is never refused for that
 * @param readingsReason why the readings are left out, or null when they are not
 */
public record DraftValidation(String ruleSet, int version, boolean valid, List<Problem> problems,
        boolean problemsTruncated, List<Reading> readings, boolean readingsTruncated, String readingsReason)
        implements Serializable {

    /** The reason code of {@code readingsReason} when the table has more rows than one request reads. */
    public static final String TABLE_TOO_LARGE = "TABLE_TOO_LARGE";

    public DraftValidation {
        Objects.requireNonNull(ruleSet, "ruleSet must not be null");
        problems = List.copyOf(problems);
        readings = List.copyOf(readings);
        if (readingsTruncated != (readingsReason != null)) {
            throw new IllegalArgumentException("readingsReason is set exactly when readingsTruncated is true");
        }
    }

    /**
     * A validation whose readings are whole or not applicable.
     *
     * @param ruleSet the rule set name
     * @param version the draft's version number
     * @param valid whether a save would be accepted
     * @param problems the cell problems
     * @param problemsTruncated whether more problems were found than are listed
     * @param readings the readings, empty when not valid
     */
    public DraftValidation(String ruleSet, int version, boolean valid, List<Problem> problems,
            boolean problemsTruncated, List<Reading> readings) {
        this(ruleSet, version, valid, problems, problemsTruncated, readings, false, null);
    }

    /**
     * One problem of a cell, row, column or the document.
     *
     * @param ruleId the rule (row), or null
     * @param column the input or output name (column), or null
     * @param code a stable code; {@code RULE_INVALID}, {@code COLUMN_INVALID} or {@code DOCUMENT_INVALID} when the
     *     engine's own problem carries none
     * @param position the zero-based character position in the cell text, or null
     * @param message the readable message by language code, written from the message catalogue and the code only, so
     *     it never repeats text of the cell
     */
    public record Problem(String ruleId, String column, String code, Integer position, Map<String, String> message)
            implements Serializable {

        public Problem {
            Objects.requireNonNull(code, "code must not be null");
            message = Map.copyOf(message);
        }
    }

    /**
     * The plain-language reading of one rule's condition.
     *
     * @param ruleId the rule
     * @param text the reading by language code
     */
    public record Reading(String ruleId, Map<String, String> text) implements Serializable {

        public Reading {
            Objects.requireNonNull(ruleId, "ruleId must not be null");
            text = Map.copyOf(text);
        }
    }
}
