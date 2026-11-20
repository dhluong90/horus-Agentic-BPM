package io.github.dhluong90.bpm.process.internal.exec;

/** Status of a process instance (process engine DESIGN section 3.3). */
public enum InstanceStatus {
    RUNNING, COMPLETED, CANCELLED, COMPENSATING, FAILED
}
