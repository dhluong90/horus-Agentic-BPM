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
 * @param readings one reading per rule, in document order, when {@code valid}; empty otherwise
 */
public record DraftValidation(String ruleSet, int version, boolean valid, List<Problem> problems,
        boolean problemsTruncated, List<Reading> readings) implements Serializable {

    public DraftValidation {
        Objects.requireNonNull(ruleSet, "ruleSet must not be null");
        problems = List.copyOf(problems);
        readings = List.copyOf(readings);
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
