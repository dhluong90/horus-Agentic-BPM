package io.github.dhluong90.bpm.process.internal.exec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One node of the execution tree (ADR-0020).
 *
 * @param id token id, unique in the tenant
 * @param kind scope, multi-instance body or flow
 * @param status status
 * @param scopeTokenId enclosing scope token; null only for the root scope
 * @param nodeId the flow node the token is on; the process id for the root scope
 * @param activityInstanceId id of the current activity execution, used as attempt id; null when not in an activity
 * @param locals scope-local variables (data objects, multi-instance item); empty for most tokens
 */
public record Token(String id, TokenKind kind, TokenStatus status, String scopeTokenId, String nodeId,
        String activityInstanceId, Map<String, Object> locals) {

    public Token {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        locals = locals == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(locals));
    }

    /**
     * @param newStatus the status
     * @return a copy with it
     */
    public Token withStatus(TokenStatus newStatus) {
        return new Token(id, kind, newStatus, scopeTokenId, nodeId, activityInstanceId, locals);
    }

    /**
     * @param newNodeId the node
     * @param newActivityInstanceId the activity execution id, or null
     * @return a copy on that node, {@code ACTIVE}
     */
    public Token movedTo(String newNodeId, String newActivityInstanceId) {
        return new Token(id, kind, TokenStatus.ACTIVE, scopeTokenId, newNodeId, newActivityInstanceId, locals);
    }
}
