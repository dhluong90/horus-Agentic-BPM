package com.horus.bpm.rules.api;

/** A transition recorded in the lifecycle history of a version (DESIGN section 3.3.3). */
public enum LifecycleAction {
    CREATED,
    REQUESTED,
    APPROVED,
    REJECTED
}
