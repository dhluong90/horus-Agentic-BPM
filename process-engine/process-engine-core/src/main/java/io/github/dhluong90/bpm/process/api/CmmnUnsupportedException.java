package io.github.dhluong90.bpm.process.api;

/**
 * The document is a CMMN case model. The engines model cases as BPMN processes and never read CMMN. Code
 * {@code CMMN_UNSUPPORTED}.
 */
public final class CmmnUnsupportedException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** The answer's detail, the same at every import endpoint. */
    public static final String DETAIL = "CMMN is not supported; model the case as a BPMN process";

    private final String namespace;
    private final int line;

    /**
     * @param namespace the namespace of the root element, as written
     * @param line the 1-based line of the root element
     */
    public CmmnUnsupportedException(String namespace, int line) {
        super("CMMN_UNSUPPORTED", DETAIL);
        this.namespace = namespace;
        this.line = line;
    }

    /** @return the namespace of the root element */
    public String namespace() {
        return namespace;
    }

    /** @return the line of the root element */
    public int line() {
        return line;
    }
}
