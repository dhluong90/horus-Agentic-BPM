package io.github.dhluong90.bpm.expr.feel;

import java.util.List;

/**
 * The result of evaluating FEEL where the caller needs the warnings: the value and the errors that {@code spec} mode
 * turned into null, in evaluation order.
 *
 * @param value the FEEL value, possibly null
 * @param warnings recoverable errors seen on the way, empty in {@code strict} mode
 */
public record FeelOutcome(Object value, List<Warning> warnings) {

    public FeelOutcome {
        warnings = List.copyOf(warnings);
    }

    /**
     * @param kind the error class
     * @param message what failed, without input values
     * @param offset source offset of the node, from 0
     */
    public record Warning(FeelOptions.ErrorKind kind, String message, int offset) { }
}
