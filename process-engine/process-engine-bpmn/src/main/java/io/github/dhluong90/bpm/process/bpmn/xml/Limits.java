package io.github.dhluong90.bpm.process.bpmn.xml;

/**
 * Bounds of one read (process engine DESIGN section 5.5). Hosts may lower them, never raise them (ADR-0024).
 *
 * @param maxBytes largest document
 * @param maxDepth deepest element nesting
 * @param maxElements most elements
 * @param maxForeignFragmentBytes largest foreign extension fragment kept opaque
 */
public record Limits(int maxBytes, int maxDepth, int maxElements, int maxForeignFragmentBytes) {

    /** Engine defaults: 1 MB, depth 32, 5,000 elements, 16 KB per foreign fragment. */
    public static final Limits DEFAULTS = new Limits(1_048_576, 32, 5_000, 16_384);

    public Limits {
        if (maxBytes < 1 || maxDepth < 1 || maxElements < 1 || maxForeignFragmentBytes < 0) {
            throw new IllegalArgumentException("Limits must be positive");
        }
    }
}
