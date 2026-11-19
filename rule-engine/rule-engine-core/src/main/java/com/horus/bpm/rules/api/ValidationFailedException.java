package com.horus.bpm.rules.api;

import java.util.List;
import java.util.Objects;

/**
 * {@code 422 VALIDATION_FAILED}: the definition cannot be read or compiled. {@link #violations()} lists every
 * problem with its rule and column; it is never empty.
 */
public final class ValidationFailedException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    @SuppressWarnings("serial") // an immutable List.copyOf of serializable records
    private final List<Violation> violations;

    /**
     * Creates the exception for a problem of the document as a whole.
     *
     * @param detail the problem
     */
    public ValidationFailedException(String detail) {
        this(List.of(Violation.ofDocument(detail)));
    }

    /**
     * Creates the exception for one or more located problems.
     *
     * @param violations the problems, at least one, in document order
     */
    public ValidationFailedException(List<Violation> violations) {
        super("VALIDATION_FAILED", summary(violations));
        this.violations = List.copyOf(violations);
    }

    /**
     * Returns the problems in document order.
     *
     * @return at least one violation
     */
    public List<Violation> violations() {
        return violations;
    }

    private static String summary(List<Violation> violations) {
        Objects.requireNonNull(violations, "violations must not be null");
        if (violations.isEmpty()) {
            throw new IllegalArgumentException("violations must not be empty");
        }
        String first = violations.getFirst().describe();
        return violations.size() == 1 ? first : first + " (and " + (violations.size() - 1) + " more)";
    }
}
