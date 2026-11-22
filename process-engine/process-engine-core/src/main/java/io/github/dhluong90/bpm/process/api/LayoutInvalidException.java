package io.github.dhluong90.bpm.process.api;

import java.util.List;

/**
 * A layout document is not valid for the definition version it is saved for: it breaks the layout schema, or it names a
 * step the definition does not have (PRE-026). Code {@code LAYOUT_INVALID}; nothing is written.
 */
public final class LayoutInvalidException extends ProcessException {

    private static final long serialVersionUID = 1L;

    private final List<String> problems;

    /**
     * @param message the detail
     * @param problems each problem as a JSON path and a reason, at most 20; never a value
     */
    public LayoutInvalidException(String message, List<String> problems) {
        super("LAYOUT_INVALID", message);
        this.problems = List.copyOf(problems);
    }

    /** @return each problem as a JSON path and a reason */
    public List<String> problems() {
        return problems;
    }
}
