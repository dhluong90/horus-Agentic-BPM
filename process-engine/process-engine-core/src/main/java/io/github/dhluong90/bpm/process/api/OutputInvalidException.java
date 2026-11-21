package io.github.dhluong90.bpm.process.api;

import java.util.List;

/**
 * {@code 422 OUTPUT_INVALID}: an operator's output for a step breaks the step's output schema (#68, PRE-078). Each
 * entry names a path and what is wrong there, never the value. Nothing was written.
 */
public final class OutputInvalidException extends ProcessException {

    private static final long serialVersionUID = 1L;

    private final List<String> paths;

    /**
     * @param message the detail
     * @param paths the paths that break the schema, with what is wrong, never the value
     */
    public OutputInvalidException(String message, List<String> paths) {
        super("OUTPUT_INVALID", message);
        this.paths = List.copyOf(paths);
    }

    /** @return the paths that break the schema */
    public List<String> paths() {
        return paths;
    }
}
