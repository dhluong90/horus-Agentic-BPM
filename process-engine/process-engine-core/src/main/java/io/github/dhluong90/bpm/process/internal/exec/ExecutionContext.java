package io.github.dhluong90.bpm.process.internal.exec;

import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;

/**
 * Mutation API over a working copy of {@link InstanceState} during one {@link ExecutionKernel#apply}.
 * Not thread-safe; one context per apply.
 */
public interface ExecutionContext {

    /** @return the token tree of the working copy */
    TokenTree tree();

    /** @return the scope walker over the tree */
    ScopeWalker scopes();

    /** @return variables, resolved through the scope chain */
    VariableScopes variables();

    /** @return the compiled definition */
    CompiledProcess process();

    /**
     * Leaves a node by its outgoing sequence flows with default BPMN semantics: one flow moves the token, several
     * unconditional flows fork it, a default flow is taken when no condition holds.
     *
     * @param token the token leaving
     * @param from the node it leaves
     */
    void takeOutgoing(Token token, FlowNode from);

    /**
     * Moves a token along one sequence flow and enters the target node.
     *
     * @param token the token
     * @param sequenceFlowId the flow
     */
    void take(Token token, String sequenceFlowId);

    /**
     * @param kind token kind
     * @param nodeId node the token is on
     * @param scopeTokenId enclosing scope token, or null for a root scope
     * @return the new token, {@code ACTIVE} (or {@code WAITING_SCOPE} for a scope), added to the tree
     */
    Token createToken(TokenKind kind, String nodeId, String scopeTokenId);

    /**
     * Replaces a token in the tree.
     *
     * @param token the new version, same id
     */
    void update(Token token);

    /**
     * Ends a flow token and, when it was the last live token of its scope, completes the scope.
     *
     * @param token the token
     */
    void complete(Token token);

    /**
     * Cancels a scope token and everything under it: tokens, tasks, jobs and subscriptions.
     *
     * @param scope the scope token
     */
    void cancelSubtree(Token scope);

    /**
     * Enters a {@code saga} block (#67, ADR-0020): a {@code SCOPE} token for the block under the arriving token's
     * scope, the arriving token ends, and a first {@code FLOW} token under the new scope enters the block's start
     * event. When the last live flow token of the block ends, a new flow token leaves the block in the enclosing scope.
     *
     * @param token the arriving token, on the block
     * @param block the block
     */
    void enterScope(Token token, FlowNode block);

    /**
     * Starts the compensation of the token's scope (#67, PRE-013, PRE-044): every live flow token of the instance is
     * cancelled with its task or job, the instance is {@code COMPENSATING}, and the shell walks the scope's stack
     * newest first. The instance can only end {@code FAILED} from here.
     *
     * @param failing the token whose step failed, or that reached {@code throwCompensation}
     * @param cause the code that started it, for example a business error code
     */
    void compensate(Token failing, String cause);

    /**
     * A compensation throw event that the flow goes on after (#488): intermediate with an outgoing flow, or an end
     * event. Every other live token under the thrower's scope is cancelled, the thrower waits, the instance is
     * {@code COMPENSATING}, and the shell walks the undo entries of the scope and of the sub-processes completed in it,
     * newest first. When the walk is done the instance is {@code RUNNING} again and the thrower leaves.
     *
     * @param thrower the token on the throw event
     * @param targetActivityId the activity to undo (an {@code activityRef}), or null for the whole scope
     */
    default void compensateAndResume(Token thrower, String targetActivityId) {
        throw new UnsupportedOperationException("compensateAndResume");
    }

    /**
     * A cancel end event inside a transaction (#488): the transaction is compensated as for
     * {@link #compensateAndResume}, and when the walk is done its cancel boundary event leaves the flow in the
     * enclosing scope.
     *
     * @param end the token on the cancel end event
     */
    default void cancelTransaction(Token end) {
        throw new UnsupportedOperationException("cancelTransaction");
    }

    /**
     * Raises a BPMN error from a token (#489, #493). The nearest error boundary that matches the code, on the step
     * itself or on an enclosing sub-process, call activity or saga block, catches it: the host activity and
     * everything under it is cancelled and the boundary's flow runs. A boundary without an error code catches every
     * code; a boundary with one catches only that code and wins over a catch-all at the same level.
     *
     * @param source the token that raised it: the failing step, or the error end event
     * @param code the error code
     * @param fromStep true when {@code source} is the step that failed, so its own boundaries are searched first; false
     *     for an error end event, whose search starts at the enclosing scope
     * @return true when a boundary caught it, and the caller must stop; false when nothing catches it
     */
    default boolean throwError(Token source, String code, boolean fromStep) {
        throw new UnsupportedOperationException("throwError");
    }

    /**
     * Raises an escalation from a token (#493). It is recorded in history either way. An interrupting escalation
     * boundary that matches cancels the host, including the thrower, and runs its flow; a non-interrupting one runs
     * its flow beside the host. An escalation nothing catches is only recorded, and the flow goes on.
     *
     * @param thrower the token on the throw event
     * @param code the escalation code
     * @return true when an interrupting boundary caught it and cancelled the thrower, so the caller must stop; false
     *     when the thrower may go on
     */
    default boolean throwEscalation(Token thrower, String code) {
        throw new UnsupportedOperationException("throwEscalation");
    }

    /**
     * A terminate end event (#493): cancels every other live token of the scope, with their tasks, jobs and called
     * children, and ends the token itself, so the scope completes: the instance at the top level, the sub-process
     * otherwise.
     *
     * @param token the token on the terminate end event
     */
    default void terminate(Token token) {
        throw new UnsupportedOperationException("terminate");
    }

    /**
     * Moves a token to another node of the same scope without a sequence flow, as a link throw does (#493).
     *
     * @param token the token
     * @param nodeId the node to enter
     */
    default void jump(Token token, String nodeId) {
        throw new UnsupportedOperationException("jump");
    }

    /**
     * A boundary event's own trigger fired (#489): an interrupting one cancels its host activity first, and the token
     * then leaves by the boundary's outgoing flow.
     *
     * @param boundaryToken the token that waited on the boundary event
     * @param boundary the boundary event
     */
    default void fireBoundary(Token boundaryToken, io.github.dhluong90.bpm.process.internal.model.bpmn.BoundaryEvent
            boundary) {
        throw new UnsupportedOperationException("fireBoundary");
    }

    /**
     * The trigger of an event sub-process fired (#487). An interrupting one first cancels every other live token of the
     * scope, with their tasks, jobs, called children and waits; a non-interrupting one leaves them. Then it opens a
     * {@code SCOPE} token for the event sub-process under the scope and a flow token at its start event. The caller
     * ends or keeps its own listener token before it calls.
     *
     * @param scope the scope token the event sub-process stands in
     * @param start the start event of the event sub-process, which carries the trigger
     * @param trigger {@code TIMER}, {@code MESSAGE}, {@code SIGNAL}, {@code CONDITIONAL}, {@code ERROR} or
     *     {@code ESCALATION}, for the timeline
     * @param details more for the timeline, for example the error code
     * @param exceptWorkOf a token whose job or task is not cancelled because it already ended, or null
     */
    default void startEventSubProcess(Token scope, io.github.dhluong90.bpm.process.internal.model.bpmn.StartEvent start,
            String trigger, java.util.Map<String, Object> details, String exceptWorkOf) {
        throw new UnsupportedOperationException("startEventSubProcess");
    }

    /** Records that a conditional event sub-process was armed, so the kernel asks its condition once after this apply. */
    default void conditionArmed() {
        throw new UnsupportedOperationException("conditionArmed");
    }

    /** @param effect an effect for the shell */
    void effect(Effect effect);

    /** @param entry a history entry */
    void history(HistoryEntry entry);

    /**
     * Records a history entry of the given type at the kernel clock.
     *
     * @param type entry type
     * @param token the token, or null
     * @param details details
     */
    void history(String type, Token token, java.util.Map<String, Object> details);

    /** @return a new id derived from the instance id and the sequence counter */
    String nextId();

    /**
     * Evaluates an expression through the {@code ExpressionLanguage} registered for the language id; variables
     * resolve through the scope of the token.
     *
     * @param expression the text
     * @param languageId the language, for example {@code urn:horus-bpm:expr:v1}
     * @param in the token whose scope supplies variables
     * @return the value
     */
    Object evaluate(String expression, String languageId, Token in);

    /**
     * @param key facet key
     * @param <T> facet type
     * @return compile-time precomputation for this definition
     */
    <T> T facet(FacetKey<T> key);

    /** @return the actor of the current command, or null */
    String actor();

    /** @return the kernel clock instant of this apply */
    java.time.Instant now();
}
