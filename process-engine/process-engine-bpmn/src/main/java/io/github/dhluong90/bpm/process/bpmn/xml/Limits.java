package io.github.dhluong90.bpm.process.bpmn.xml;

/**
 * Bounds of one read (process engine DESIGN section 5.5). Hosts may lower them, never raise them (ADR-0024).
 *
 * <p>The MIWG harness must confirm that all 22 reference models, diagram interchange included, fit within
 * {@link #DEFAULTS}. If one does not, the default is raised once here, never per stream.
 *
 * @param maxBytes largest document
 * @param maxDepth deepest element nesting
 * @param maxElements most elements
 * @param maxForeignFragmentBytes largest foreign extension fragment kept opaque
 * @param maxTextChars most characters of text in one element
 * @param maxAttributeChars most characters in one attribute value
 */
public record Limits(int maxBytes, int maxDepth, int maxElements, int maxForeignFragmentBytes, int maxTextChars,
        int maxAttributeChars) {

    /** Engine defaults: 1 MB, depth 32, 5,000 elements, 16 KB per foreign fragment, 65,536 text and 4,096 attribute characters. */
    public static final Limits DEFAULTS = new Limits(1_048_576, 32, 5_000, 16_384, 65_536, 4_096);

    public Limits {
        if (maxBytes < 1 || maxDepth < 1 || maxElements < 1 || maxForeignFragmentBytes < 0 || maxTextChars < 1
                || maxAttributeChars < 1) {
            throw new IllegalArgumentException("Limits must be positive");
        }
    }
}
