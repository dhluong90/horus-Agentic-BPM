package io.github.dhluong90.bpm.rules.api;

/** Where a decision that the asynchronous decision log could not write stands (RLE-235). */
public enum DeadLetterState {
    /** The relay gave up after its attempts; nothing is waiting for it but an operator. */
    DEAD,
    /** An operator asked for the relay to try again; a second person has to approve. */
    RETRY_REQUESTED,
    /** An operator asked to close the row as handled outside the queue; a second person has to approve. */
    RESOLVE_REQUESTED
}
