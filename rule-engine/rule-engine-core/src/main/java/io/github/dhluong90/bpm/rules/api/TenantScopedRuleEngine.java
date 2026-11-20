package io.github.dhluong90.bpm.rules.api;

/**
 * Evaluation for an embedded engine that acts on its own stored work, where no caller is signed in: a process step
 * reached after a timer, for example (ADR-0027). The tenant and the caller are explicit instead of resolved from the
 * host's security context.
 *
 * <p>Only engine code calls this, with the tenant it read from its own stored aggregate under that aggregate's lock;
 * never with a tenant taken from a request, a variable or an input. There is no REST endpoint for it. The caller is a
 * system identity ({@code system:...}) and no permission is checked: the calling engine authorises its own step. The
 * evaluation is otherwise the same as {@link RuleEngine#evaluate(DecisionRequest)}: the same input limits,
 * idempotency and one {@code rle_decision} row in the given tenant, written in the caller's transaction.
 */
public interface TenantScopedRuleEngine {

    /** Prefix of every engine identity; people and service accounts never carry it. */
    String SYSTEM_CALLER_PREFIX = "system:";

    /**
     * Evaluates the version of a rule set effective at the engine clock, in the given tenant.
     *
     * @param tenantId the tenant, read by the calling engine from its stored aggregate; not blank
     * @param systemCallerId the engine identity written to the decision log, starting with {@code system:}
     * @param request the request
     * @return the logged result; on a replay of the same key and request, the original result
     * @throws IllegalArgumentException when the tenant is blank or the caller is not a system identity
     * @throws RuleSetNotFoundException when the tenant has no such rule set ({@code RULE_SET_NOT_FOUND})
     * @throws NoEffectiveVersionException when no published version is effective ({@code NO_EFFECTIVE_VERSION})
     * @throws InputInvalidException when an input has the wrong type ({@code INPUT_INVALID})
     */
    DecisionResult evaluate(String tenantId, String systemCallerId, DecisionRequest request);
}
