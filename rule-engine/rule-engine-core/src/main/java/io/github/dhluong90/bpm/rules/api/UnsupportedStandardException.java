package io.github.dhluong90.bpm.rules.api;

import java.util.List;
import java.util.Objects;

/**
 * {@code 422}: the file is in a standard, or uses an element of one, that the engine does not support. It is
 * refused by name and nothing is stored. The code is {@code CMMN_UNSUPPORTED}, {@code DMN_UNSUPPORTED_ELEMENT} or
 * {@code EXPRESSION_LANGUAGE_UNSUPPORTED}.
 */
public final class UnsupportedStandardException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    /** Most elements listed; further ones set {@link #truncated()}. */
    public static final int MAX_ELEMENTS = 100;

    /** The detail of every CMMN refusal. */
    public static final String CMMN_DETAIL = "CMMN is not supported; model the case as a BPMN process";

    @SuppressWarnings("serial") // an immutable List.copyOf of serializable records
    private final List<UnsupportedElement> elements;

    private final boolean truncated;
    private final String namespace;
    private final int line;

    private UnsupportedStandardException(String code, String detail, List<UnsupportedElement> elements,
            boolean truncated, String namespace, int line) {
        super(code, detail);
        this.elements = List.copyOf(elements);
        this.truncated = truncated;
        this.namespace = namespace;
        this.line = line;
    }

    /**
     * The file is a CMMN case model.
     *
     * @param namespace the namespace of the root element, already bounded
     * @param line the 1-based line of the root element
     * @return the exception
     */
    public static UnsupportedStandardException cmmn(String namespace, int line) {
        return new UnsupportedStandardException("CMMN_UNSUPPORTED", CMMN_DETAIL, List.of(), false, namespace, line);
    }

    /**
     * DMN elements outside the current stage.
     *
     * @param found every element found in document order, at least one; only the first {@link #MAX_ELEMENTS} are kept
     * @return the exception
     */
    public static UnsupportedStandardException dmnElements(List<UnsupportedElement> found) {
        Objects.requireNonNull(found, "found must not be null");
        if (found.isEmpty()) {
            throw new IllegalArgumentException("found must not be empty");
        }
        boolean cut = found.size() > MAX_ELEMENTS;
        List<UnsupportedElement> kept = cut ? found.subList(0, MAX_ELEMENTS) : found;
        UnsupportedElement first = kept.getFirst();
        String detail = describe(first) + (found.size() == 1 ? "" : " (and " + (found.size() - 1) + " more)");
        return new UnsupportedStandardException("DMN_UNSUPPORTED_ELEMENT", detail, kept, cut, null, 0);
    }

    /**
     * An expression language other than FEEL.
     *
     * @param language the language as written, already bounded
     * @param at the element that names it
     * @return the exception
     */
    public static UnsupportedStandardException expressionLanguage(String language, UnsupportedElement at) {
        return new UnsupportedStandardException("EXPRESSION_LANGUAGE_UNSUPPORTED", "Expression language '" + language
                + "' is not supported; only FEEL is (line " + at.line() + ")", List.of(at), false, null, 0);
    }

    private static String describe(UnsupportedElement e) {
        return e.message();
    }

    /** @return the elements refused, in document order; empty for CMMN */
    public List<UnsupportedElement> elements() {
        return elements;
    }

    /** @return whether more elements were found than are listed */
    public boolean truncated() {
        return truncated;
    }

    /** @return the namespace of the CMMN root element, or null for another refusal */
    public String namespace() {
        return namespace;
    }

    /** @return the line of the CMMN root element, or 0 for another refusal */
    public int line() {
        return line;
    }
}
