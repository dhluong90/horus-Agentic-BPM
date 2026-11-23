package io.github.dhluong90.bpm.process.internal.exec;

/**
 * What the kernel does when a scope opens or ends (#487). When a scope token has been created, before its first flow
 * token runs, the kernel arms the event sub-processes of the scope that wait on a timer, a message, a signal or a
 * condition; a scope with none does nothing. When a sub-process or transaction ends normally, its behaviour may push the
 * undo it has.
 */
@FunctionalInterface
interface ScopeArming {

    /**
     * @param ctx the working copy
     * @param scope the new scope token: the process, a sub-process, a transaction or an event sub-process
     */
    void arm(WorkingContext ctx, Token scope);

    /**
     * A sub-process or transaction scope ended normally; its behaviour may push its undo (#487, #488).
     *
     * @param ctx the working copy
     * @param scope the scope token that ended
     * @param block the sub-process or transaction
     */
    default void completed(WorkingContext ctx, Token scope, io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode
            block) {
    }
}
