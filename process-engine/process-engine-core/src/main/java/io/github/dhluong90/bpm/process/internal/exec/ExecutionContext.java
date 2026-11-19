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
