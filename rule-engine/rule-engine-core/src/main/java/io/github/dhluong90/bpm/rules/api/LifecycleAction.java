package io.github.dhluong90.bpm.rules.api;

/** A transition recorded in the lifecycle history of a version (DESIGN section 3.3.3). */
public enum LifecycleAction {
    CREATED,
    REQUESTED,
    APPROVED,
    REJECTED,
    /** Taken out of service: by hand ({@code :retire}) or when a newer approved version is in effect (ADR-0025). */
    RETIRED,
    /** The requester took their own publication request back (PD-089, RLE-046). */
    WITHDRAWN,
    /** The author replaced the draft's document in place (RLE-047); records the old and new canonical hash. */
    DRAFT_UPDATED,
    /** In four-eyes mode, a retirement was requested; the version stays published and serving (RLE-038). */
    RETIRE_REQUESTED,
    /** A pending retirement request was withdrawn with a reason; the version stays published (RLE-038). */
    RETIRE_CANCELLED
}
