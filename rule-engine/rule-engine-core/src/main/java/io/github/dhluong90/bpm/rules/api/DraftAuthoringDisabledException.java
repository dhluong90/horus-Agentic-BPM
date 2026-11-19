package com.horus.bpm.rules.api;

/**
 * {@code 403 DRAFT_AUTHORING_DISABLED}: the host keeps rule changes in its release pipeline
 * ({@code horus.bpm.rules.authoring.cockpit-drafts: false}), so drafts are not edited through the engine's API.
 * Import of a document, approvals and simulation stay available (DESIGN section 4.4, PD-094).
 */
public final class DraftAuthoringDisabledException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param ruleSet the rule set name
     * @param version the version number
     */
    public DraftAuthoringDisabledException(String ruleSet, int version) {
        super("DRAFT_AUTHORING_DISABLED", "Draft editing is turned off on this host"
                + " (horus.bpm.rules.authoring.cockpit-drafts); " + ruleSet + " v" + version
                + " cannot be replaced. Import the document as a new version instead");
    }
}
