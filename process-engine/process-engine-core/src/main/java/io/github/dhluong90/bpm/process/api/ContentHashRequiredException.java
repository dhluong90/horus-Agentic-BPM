package io.github.dhluong90.bpm.process.api;

/**
 * {@code 400 CONTENT_HASH_REQUIRED}: {@code horus.bpm.processes.approval.require-content-hash} is on and an approval
 * did not name {@code expectedDefinitionHash}, the hash of the content the approver reviewed (PRE-177, PD-139).
 */
public final class ContentHashRequiredException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception. */
    public ContentHashRequiredException() {
        super("CONTENT_HASH_REQUIRED", "expectedDefinitionHash is required: send the definitionHash of the version the"
                + " approver reviewed (horus.bpm.processes.approval.require-content-hash)");
    }
}
