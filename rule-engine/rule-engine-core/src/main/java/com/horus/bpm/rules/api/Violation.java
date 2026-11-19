package com.horus.bpm.rules.api;

import java.io.Serializable;
import java.util.Objects;

/**
 * One problem of a refused rule set document, placed on its cell so a screen can mark it.
 *
 * @param ruleId the rule (row) the problem is in, or null for a problem outside the rules
 * @param column the input or output name (column) the problem is in, or null when it concerns the row or the document
 * @param message the problem in English, without the rule id or column
 */
public record Violation(String ruleId, String column, String message) implements Serializable {

    public Violation {
        Objects.requireNonNull(message, "message must not be null");
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
