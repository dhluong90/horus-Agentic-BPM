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

    /**
     * The failing fields as JSON Pointers (RFC 6901), one per violation in the same order: {@code $.reason: required}
     * is {@code /reason}, {@code $.items[0].qty: below the minimum} is {@code /items/0/qty}, a violation of the whole
     * result is the empty pointer.
     *
     * @return the pointers
     */
    public List<String> pointers() {
        return violations.stream().map(FormValidationException::pointer).toList();
    }

    /**
     * @param violation a violation as the validator words it, {@code <path>: <rule>}
     * @return its JSON Pointer
     */
    static String pointer(String violation) {
        int colon = violation.indexOf(": ");
        String path = colon < 0 ? violation : violation.substring(0, colon);
        if (!path.startsWith("$")) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String part : path.substring(1).replace("[", ".").replace("]", "").split("\\.")) {
            if (!part.isEmpty()) {
                out.append('/').append(part.replace("~", "~0").replace("/", "~1"));
            }
        }
        return out.toString();
    }
}
