package io.github.dhluong90.bpm.process.internal.exec;

/** Where the shell runs an invocation (ADR-0008). */
public enum ExecutionMode {
    /**
     * Inside the current transaction; the shell delivers the result as {@link Command.AsyncResult} in the same
     * transaction. Only for calls that touch nothing but the host database (local handlers, decisions).
     */
    SYNC_LOCAL,
    /** Async Wait-State: TX1 claims a job, the call runs with no connection, TX2 delivers the result. */
    ASYNC
}
