package io.github.dhluong90.bpm.rules.api;

import java.io.Serializable;
import java.util.Objects;

/**
 * One problem of a refused rule set document, placed on its cell so a screen can mark it.
 *
 * @param ruleId the rule (row) the problem is in, or null for a problem outside the rules
 * @param column the input or output name (column) the problem is in, or null when it concerns the row or the document
 * @param message the problem in English, without the rule id or column
 * @param code a stable code for a program to act on, for example {@code UNKNOWN_INPUT}, {@code TYPE_MISMATCH} or
 *     {@code FORBIDDEN_CONSTRUCT} (RLE-004), or null for a problem that has none
 * @param position the zero-based character position of the problem in the cell text as written, or null when the
 *     problem concerns the cell as a whole or is not in a cell
 */
public record Violation(String ruleId, String column, String message, String code, Integer position)
        implements Serializable {

    public Violation {
        Objects.requireNonNull(message, "message must not be null");
        if (position != null && position < 0) {
            throw new IllegalArgumentException("position must not be negative, was " + position);
        }
    }

    /**
     * Creates a problem with no code and no position.
     *
     * @param ruleId the rule (row), or null
     * @param column the input or output name (column), or null
     * @param message the problem
     */
    public Violation(String ruleId, String column, String message) {
        this(ruleId, column, message, null, null);
    }

    /**
     * Creates a problem with a code and no position.
     *
     * @param ruleId the rule (row), or null
     * @param column the input or output name (column), or null
     * @param message the problem
     * @param code the code, or null
     */
    public Violation(String ruleId, String column, String message, String code) {
        this(ruleId, column, message, code, null);
    }

    /**
     * Returns a problem of the document as a whole.
     *
     * @param message the problem
     * @return the violation with no rule and no column
     */
    public static Violation ofDocument(String message) {
        return new Violation(null, null, message);
    }

    /**
     * Returns the problem as one sentence with its location, for logs and the {@code detail} of a problem response.
     *
     * @return for example {@code Rule R2, output fee: ...}
     */
    public String describe() {
        if (ruleId == null && column == null) {
            return message;
        }
        StringBuilder where = new StringBuilder();
        if (ruleId != null) {
            where.append("Rule ").append(ruleId);
        }
        if (column != null) {
            where.append(where.isEmpty() ? "Column " : ", column ").append(column);
        }
        return where + ": " + message;
    }
}
