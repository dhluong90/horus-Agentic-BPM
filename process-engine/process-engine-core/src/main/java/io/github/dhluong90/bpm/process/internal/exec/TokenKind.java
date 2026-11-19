package io.github.dhluong90.bpm.process.internal.exec;

/** Kind of a token in the execution tree (ADR-0020). */
public enum TokenKind {
    /** A scope: the process, a subprocess, a transaction, an event subprocess or a multi-instance instance. */
    SCOPE,
    /** The body of a multi-instance activity. */
    MI_BODY,
    /** A position on a flow node. */
    FLOW
}
