package io.github.dhluong90.bpm.expr.feel;

/**
 * Prints a tree to the one normal form that is hashed (ADR-0026, ADR-0028): minimal parentheses, one spelling per
 * construct, strings escaped without Unicode normalisation. Frozen per language revision by a golden file.
 * Owned by #197.
 */
public interface FeelPrinter {

    /** @return the printer of this release; the stub until #197 */
    static FeelPrinter create() {
        return node -> {
            throw new FeelNotImplementedException("FeelPrinter", "#197");
        };
    }

    /**
     * @param node a tree
     * @return its normal form text
     */
    String print(FeelNode node);
}
