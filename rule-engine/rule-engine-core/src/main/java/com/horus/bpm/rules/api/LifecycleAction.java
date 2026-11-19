package com.horus.bpm.rules.api;

/** A transition recorded in the lifecycle history of a version (DESIGN section 3.3.3). */
public enum LifecycleAction {
    CREATED,
    REQUESTED,
    APPROVED,
    REJECTED,
    /** The requester took their own publication request back (PD-089, RLE-046). */
    WITHDRAWN
}
