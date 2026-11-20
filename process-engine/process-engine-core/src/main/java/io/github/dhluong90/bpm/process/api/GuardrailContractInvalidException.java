package io.github.dhluong90.bpm.process.api;

import java.util.List;

/**
 * An agent step names a guardrail rule set that breaks the guardrail contract (PRE-016): it is missing in the tenant or
 * has no version in effect, its hit policy is not {@code first} or {@code unique}, it does not declare
 * {@code allow: boolean}, its {@code reasons} is neither {@code string} nor {@code list<string>}, or it reads an input
 * the step's output schema does not provide. Checked at import, at request-approval and at approval; nothing is
 * written. Code {@code GUARDRAIL_CONTRACT_INVALID}.
 */
public final class GuardrailContractInvalidException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** The code of the exception and of each of its problems. */
    public static final String CODE = "GUARDRAIL_CONTRACT_INVALID";

    private final transient List<DefinitionProblem> problems;

    /** @param problems each failed check, naming its step; never empty */
    public GuardrailContractInvalidException(List<DefinitionProblem> problems) {
        super(CODE, summary(problems));
        this.problems = List.copyOf(problems);
    }

    /** @return each failed check, naming its step */
    public List<DefinitionProblem> problems() {
        return problems;
    }

    private static String summary(List<DefinitionProblem> problems) {
        StringBuilder out = new StringBuilder();
        for (DefinitionProblem p : problems) {
            if (!out.isEmpty()) {
                out.append("; ");
            }
            out.append("step ").append(p.elementId()).append(": ").append(p.message());
        }
        return out.toString();
    }
}
