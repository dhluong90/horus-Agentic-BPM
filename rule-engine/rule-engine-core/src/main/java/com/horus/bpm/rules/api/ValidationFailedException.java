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

    private final boolean truncated;

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
        this(violations, false);
    }

    /**
     * Creates the exception for located problems, of which only the first ones were kept.
     *
     * @param violations the problems kept, at least one, in document order
     * @param truncated whether more problems were found than are listed
     */
    public ValidationFailedException(List<Violation> violations, boolean truncated) {
        super("VALIDATION_FAILED", summary(violations, truncated));
        this.violations = List.copyOf(violations);
        this.truncated = truncated;
    }

    /**
     * Tells whether more problems were found than {@link #violations()} lists.
     *
     * @return whether the list was cut
     */
    public boolean truncated() {
        return truncated;
    }

    /**
     * Returns the problems in document order.
     *
     * @return at least one violation
     */
    public List<Violation> violations() {
        return violations;
    }

    private static String summary(List<Violation> violations, boolean truncated) {
        Objects.requireNonNull(violations, "violations must not be null");
        if (violations.isEmpty()) {
            throw new IllegalArgumentException("violations must not be empty");
        }
        String first = violations.getFirst().describe();
        if (truncated) {
            return first + " (and more; the first " + violations.size() + " problems are listed)";
        }
        return violations.size() == 1 ? first : first + " (and " + (violations.size() - 1) + " more)";
    }
}
