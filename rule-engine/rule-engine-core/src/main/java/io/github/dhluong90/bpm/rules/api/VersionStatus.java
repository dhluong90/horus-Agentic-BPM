package io.github.dhluong90.bpm.rules.api;

/** Lifecycle status of a rule set version (rule-engine/docs/DESIGN.md section 3.3). */
public enum VersionStatus {
    DRAFT,
    PENDING_APPROVAL,
    PUBLISHED,
    RETIRED
}
