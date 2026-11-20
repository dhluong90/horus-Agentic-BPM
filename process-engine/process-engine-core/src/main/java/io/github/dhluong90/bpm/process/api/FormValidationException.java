package io.github.dhluong90.bpm.process.api;

import java.util.List;

/**
 * A task completion does not meet the schema its result must follow. Code {@code FORM_VALIDATION_FAILED}. Nothing is
 * written and the task stays claimed; correct the result and complete again. For the review task of a refused async
 * result (#363), the schema is the step's output schema.
 */
public final class FormValidationException extends ProcessException {

    private static final long serialVersionUID = 1L;
    private final transient List<String> violations;

    /**
     * @param message the detail
     * @param violations each names a path and a rule, never a value
     */
    public FormValidationException(String message, List<String> violations) {
        super("FORM_VALIDATION_FAILED", message);
        this.violations = List.copyOf(violations);
    }

    /** @return each failing path and rule */
    public List<String> violations() {
        return violations;
    }
}
