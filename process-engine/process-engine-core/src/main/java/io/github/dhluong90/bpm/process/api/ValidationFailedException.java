package io.github.dhluong90.bpm.process.api;

import java.util.List;

/** The definition breaks a structural rule. Code {@code VALIDATION_FAILED}. */
public final class ValidationFailedException extends ProcessException {

    private static final long serialVersionUID = 1L;

    private final transient List<DefinitionProblem> problems;

    /** @param problems every problem found, never empty */
    public ValidationFailedException(List<DefinitionProblem> problems) {
        super("VALIDATION_FAILED", summary(problems));
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
