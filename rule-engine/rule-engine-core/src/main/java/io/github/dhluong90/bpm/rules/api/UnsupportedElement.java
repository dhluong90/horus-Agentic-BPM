package io.github.dhluong90.bpm.rules.api;

import java.io.Serializable;

/**
 * One element of an imported file that the engine refuses by name.
 *
 * @param element the element or attribute name, for example {@code businessKnowledgeModel}
 * @param id the element's {@code id}, or null; control characters are removed and it is cut at 200 characters
 * @param line the 1-based line where the element starts
 * @param stage the stage of the coverage table that adds it ({@code S1}, {@code S2}, {@code S3}) or
 *     {@code not planned}
 */
public record UnsupportedElement(String element, String id, int line, String stage) implements Serializable {

    /** Stage label of an element the engines do not plan to support. */
    public static final String NOT_PLANNED = "not planned";

    /** Longest id echoed. */
    public static final int MAX_ID = 200;

    public UnsupportedElement {
        id = id == null ? null : clean(id);
    }

    /**
     * The plain-English sentence for this element, as {@code message} in the {@code unsupportedElements} list
     * (PD-141). The wording may change; the element and the stage are the contract.
     *
     * @return the sentence
     */
    public String message() {
        String when = NOT_PLANNED.equals(stage) ? "is not planned" : "is added in stage " + stage;
        return "<" + element + (id == null ? "" : " id=\"" + id + "\"") + "> is not supported; it " + when + " (line "
                + line + ")";
    }

    private static String clean(String id) {
        StringBuilder out = new StringBuilder(Math.min(id.length(), MAX_ID));
        for (int i = 0; i < id.length() && out.length() < MAX_ID; i++) {
            char c = id.charAt(i);
            if (!Character.isISOControl(c) && c != ' ' && c != ' ') {
                out.append(c);
            }
        }
        return out.toString();
    }
}
