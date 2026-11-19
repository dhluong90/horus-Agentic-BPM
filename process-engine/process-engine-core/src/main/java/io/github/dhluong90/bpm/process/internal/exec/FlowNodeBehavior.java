package io.github.dhluong90.bpm.process.internal.exec;

import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;

/**
 * Semantics of one flow node type. Registered by a {@link BehaviorModule}; one behaviour per node type.
 * Behaviours mutate state only through the {@link ExecutionContext} and perform no I/O.
 *
 * @param <N> the node type
 */
public interface FlowNodeBehavior<N extends FlowNode> {

    /** @return the node type this behaviour executes */
    Class<N> nodeType();

    /**
     * A token arrived at the node.
     *
     * @param node the node
     * @param token the arriving token, already positioned on the node
     * @param ctx the execution context
     */
    void enter(N node, Token token, ExecutionContext ctx);

    /**
     * A command addressed a token waiting on the node (task completed, result arrived, job due, event correlated).
     *
     * @param node the node
     * @param token the waiting token
     * @param trigger the command
     * @param ctx the execution context
     */
    default void trigger(N node, Token token, Command trigger, ExecutionContext ctx) {
        throw new NotExecutableException(node);
    }
}
