package io.github.dhluong90.bpm.process.internal.exec;

/** What an {@link Effect.InvokeService} asks the shell to call. */
public enum ServiceKind {
    /** A registered host handler (service task). */
    HANDLER,
    /** A Rule Engine decision (business rule task). */
    DECISION,
    /** An agent adapter (agentTask). */
    AGENT,
    /** A send task's message adapter. */
    MESSAGE
}
