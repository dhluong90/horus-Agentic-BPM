package io.github.dhluong90.bpm.process.api;

import java.util.List;

/** The BPMN document uses elements outside the supported subset; each is named with its id and line. Code {@code BPMN_UNSUPPORTED_ELEMENT}. */
public final class BpmnUnsupportedElementException extends ProcessException {

    private static final long serialVersionUID = 1L;

    private final transient List<DefinitionProblem> problems;

    /** @param problems every problem found, never empty */
    public BpmnUnsupportedElementException(List<DefinitionProblem> problems) {
        super("BPMN_UNSUPPORTED_ELEMENT", summary(problems));
        this.problems = List.copyOf(problems);
    }

    /** @return every problem found */
    public List<DefinitionProblem> problems() {
        return problems;
    }

    private static String summary(List<DefinitionProblem> problems) {
        StringBuilder out = new StringBuilder();
        for (DefinitionProblem p : problems) {
            if (!out.isEmpty()) {
                out.append("; ");
            }
            if (p.element() != null) {
                out.append(p.element()).append(' ');
            }
            if (p.elementId() != null) {
                out.append('\'').append(p.elementId()).append("' ");
            }
            if (p.line() > 0) {
                out.append("(line ").append(p.line()).append(") ");
            }
            out.append(p.message());
        }
        return out.toString();
    }
}
