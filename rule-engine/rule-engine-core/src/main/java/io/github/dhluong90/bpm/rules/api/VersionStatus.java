package io.github.dhluong90.bpm.rules.api;

/** Lifecycle status of a rule set version (rule-engine/docs/DESIGN.md section 3.3). */
public enum VersionStatus {
    DRAFT,
    PENDING_APPROVAL,
    PUBLISHED,
    RETIRED,
    /**
     * A draft its author threw away (RLE-098, #418). Terminal: the row and its history stay, so the audit trail keeps
     * what was discarded and by whom, and the number is never reused; it is left out of the version list.
     */
    DISCARDED
}
