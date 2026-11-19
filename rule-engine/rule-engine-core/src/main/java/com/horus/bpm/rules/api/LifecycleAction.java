package com.horus.bpm.rules.api;

/** A transition recorded in the lifecycle history of a version (DESIGN section 3.3.3). */
public enum LifecycleAction {
    CREATED,
    REQUESTED,
    APPROVED,
    REJECTED,
    /** The author replaced the draft's document in place (RLE-047); records the old and new canonical hash. */
    DRAFT_UPDATED
}
