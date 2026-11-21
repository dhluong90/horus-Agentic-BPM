package io.github.dhluong90.bpm.process.api;

import java.util.List;

/** The BPMN document uses elements outside the supported subset; each is named with its id, line and stage. Code {@code BPMN_UNSUPPORTED_ELEMENT}. */
public final class BpmnUnsupportedElementException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** Most entries listed; further ones set {@link #truncated()}. */
    public static final int MAX_PROBLEMS = 100;

    private final transient List<DefinitionProblem> problems;
    private final transient List<DefinitionProblem> warnings;
    private final boolean truncated;

    /** @param problems every problem found, never empty */
    public BpmnUnsupportedElementException(List<DefinitionProblem> problems) {
        this(problems, List.of(), false);
    }

    /**
     * @param problems the problems listed, never empty, in document order
     * @param warnings what was ignored without refusing the file, such as {@code FOREIGN_EXTENSION_IGNORED}
     * @param truncated whether more problems were found than are listed
     */
    public BpmnUnsupportedElementException(List<DefinitionProblem> problems, List<DefinitionProblem> warnings,
            boolean truncated) {
        super("BPMN_UNSUPPORTED_ELEMENT", summary(problems));
        this.problems = List.copyOf(problems);
        this.warnings = List.copyOf(warnings);
        this.truncated = truncated;
    }

    /** @return what was ignored without refusing the file */
    public List<DefinitionProblem> warnings() {
        return warnings;
    }

    /** @return whether more than {@link #MAX_PROBLEMS} problems were found */
    public boolean truncated() {
        return truncated;
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
