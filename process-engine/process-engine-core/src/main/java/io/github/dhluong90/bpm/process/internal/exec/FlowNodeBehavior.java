package io.github.dhluong90.bpm.process.internal.exec;

import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;

/**
 * Semantics of one flow node type with one kind of event definition. Registered by a {@link BehaviorModule}; one
 * behaviour per node class and {@link EventDefinitionKey}, looked up exactly (DESIGN section 5.8).
 * Behaviours mutate state only through the {@link ExecutionContext} and perform no I/O.
 *
 * @param <N> the node type
 */
public interface FlowNodeBehavior<N extends FlowNode> {

    /** @return the node type this behaviour executes */
    Class<N> nodeType();

    /**
     * @return the event definition this behaviour executes: {@link EventDefinitionKey#NONE} (the default, for every
     *     node that is not an event and for none events), one definition class, or {@link EventDefinitionKey#MULTIPLE}
     */
    default EventDefinitionKey eventDefinition() {
        return EventDefinitionKey.NONE;
    }

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

    /**
     * The scope this node opened ended normally (a sub-process or a transaction, #487): the place to push the undo of a
     * sub-process that has one, before its token leaves. Nothing by default.
     *
     * @param node the node
     * @param scope the scope token that ended
     * @param ctx the execution context
     */
    default void scopeCompleted(N node, Token scope, ExecutionContext ctx) {
    }
}
