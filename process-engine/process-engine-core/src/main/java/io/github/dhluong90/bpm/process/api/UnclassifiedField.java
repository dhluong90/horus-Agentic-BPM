package io.github.dhluong90.bpm.process.api;

import java.util.Objects;

/**
 * A field of a process definition that declares no classification (PD-146, #438): an agent step's
 * {@code horus:input} without {@code mask}, or a property of its output schema without {@code mask}. A reminder only;
 * it never blocks a request or an approval, and the engine never infers sensitivity from the name or a value.
 *
 * @param step the id of the agent step
 * @param field the input name, or the output property path such as {@code summary} or {@code applicant.name}
 * @param direction {@value #INPUT} or {@value #OUTPUT}
 */
public record UnclassifiedField(String step, String field, String direction) {

    /** The warning code clients branch on. */
    public static final String CODE = "UNCLASSIFIED_FIELD";

    /** An agent step's input. */
    public static final String INPUT = "INPUT";

    /** A property of an agent step's output schema. */
    public static final String OUTPUT = "OUTPUT";

    public UnclassifiedField {
        Objects.requireNonNull(step, "step must not be null");
        Objects.requireNonNull(field, "field must not be null");
        if (!INPUT.equals(direction) && !OUTPUT.equals(direction)) {
            throw new IllegalArgumentException("direction is INPUT or OUTPUT, was " + direction);
        }
    }

    /** @return the reminder in English, naming the step and the field, never a value */
    public String message() {
        return (INPUT.equals(direction) ? "Input " : "Output field ") + field + " of step " + step
                + " is not classified: it declares neither mask true nor mask false. This does not block approval.";
    }
}
