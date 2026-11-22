package io.github.dhluong90.bpm.process.api;

import java.util.List;

/**
 * A migration plan's step instructions cannot be carried out (#106, PRE-200, PRE-202): a mapping to a step of another
 * type or to a step that is missing, a step both mapped and cancelled, a cancellation without a reason or a step to
 * continue at. Code {@code MIGRATION_PLAN_INVALID}; nothing is written.
 */
public final class MigrationPlanInvalidException extends ProcessException {
    private static final long serialVersionUID = 1L;
    private final List<String> problems;

    /**
     * @param message the first problem
     * @param problems each problem, naming the step ids involved and never a value
     */
    public MigrationPlanInvalidException(String message, List<String> problems) {
        super("MIGRATION_PLAN_INVALID", message);
        this.problems = List.copyOf(problems);
    }

    /** @return each problem */
    public List<String> problems() {
        return problems;
    }
}
