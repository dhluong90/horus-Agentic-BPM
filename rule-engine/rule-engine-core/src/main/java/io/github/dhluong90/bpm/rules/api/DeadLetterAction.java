package io.github.dhluong90.bpm.rules.api;

/** What an operator asks to do with a dead-lettered decision (RLE-235). Neither action deletes it. */
public enum DeadLetterAction {
    /** The relay tries the row again, with its attempts counted from zero. */
    RETRY,
    /** The row is closed as handled outside the queue; it stays in the queue table as evidence. */
    RESOLVE
}
