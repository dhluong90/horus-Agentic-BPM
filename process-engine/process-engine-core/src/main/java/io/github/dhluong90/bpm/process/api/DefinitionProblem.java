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
 * @param stage the stage of the standards coverage table that adds the refused element ({@code S1}, {@code S2},
 *     {@code S3}) or {@code not planned}; null for a problem that is not a refusal of a standard
 */
public record DefinitionProblem(String code, String message, String element, String elementId, int line,
        String stage) {

    /** Stage label of an element the engines do not plan to support. */
    public static final String NOT_PLANNED = "not planned";

    public DefinitionProblem {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }

    /**
     * A problem that names no stage.
     *
     * @param code reason
     * @param message explanation
     * @param element the element name, or null
     * @param elementId the element id, or null
     * @param line 1-based line, or 0
     */
    public DefinitionProblem(String code, String message, String element, String elementId, int line) {
        this(code, message, element, elementId, line, null);
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
