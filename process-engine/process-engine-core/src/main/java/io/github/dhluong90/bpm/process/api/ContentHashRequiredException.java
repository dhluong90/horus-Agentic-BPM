package io.github.dhluong90.bpm.process.api;

/**
 * {@code 400 CONTENT_HASH_REQUIRED}: {@code horus.bpm.processes.approval.require-content-hash} is on and an approval
 * did not name {@code expectedDefinitionHash}, the hash of the content the approver reviewed (PRE-177, PD-139).
 */
public final class ContentHashRequiredException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception. */
    public ContentHashRequiredException() {
        this("expectedDefinitionHash", "definitionHash of the version");
    }

    /**
     * Creates the exception for another kind of approval.
     *
     * @param field the request field that is missing, such as {@code expectedPlanHash}
     * @param hashOf what the hash is of, such as {@code contentHash of the plan}
     */
    public ContentHashRequiredException(String field, String hashOf) {
        super("CONTENT_HASH_REQUIRED", field + " is required: send the " + hashOf + " the approver reviewed"
                + " (horus.bpm.processes.approval.require-content-hash)");
    }
}
