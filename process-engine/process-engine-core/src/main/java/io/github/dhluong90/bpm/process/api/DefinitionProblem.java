package io.github.dhluong90.bpm.process.api;

import java.util.Objects;

/**
 * One problem found in a definition document.
 *
 * @param code machine-readable reason, for example {@code UNSUPPORTED_ELEMENT} or {@code NO_END_REACHABLE}
 * @param message human-readable explanation
 * @param element BPMN element name such as {@code boundaryEvent}, or null when not tied to an element
 * @param elementId the element id, or null
 * @param line 1-based line in the source document, or 0 when unknown
 */
public record DefinitionProblem(String code, String message, String element, String elementId, int line) {

    public DefinitionProblem {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }

    /**
     * A problem not tied to a source line.
     *
     * @param code reason
     * @param message explanation
     * @param elementId the element id, or null
     * @return the problem
     */
    public static DefinitionProblem of(String code, String message, String elementId) {
        return new DefinitionProblem(code, message, null, elementId, 0);
    }
}
