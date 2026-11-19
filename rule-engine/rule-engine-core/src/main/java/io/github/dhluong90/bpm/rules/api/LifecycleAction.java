package io.github.dhluong90.bpm.rules.api;

/** A transition recorded in the lifecycle history of a version (DESIGN section 3.3.3). */
public enum LifecycleAction {
    CREATED,
    REQUESTED,
    APPROVED,
    REJECTED,
    /** Taken out of service: by hand ({@code :retire}) or when a newer approved version is in effect (ADR-0025). */
    RETIRED
}
