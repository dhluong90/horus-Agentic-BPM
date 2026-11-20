package io.github.dhluong90.bpm.rules.embedded;

import io.github.dhluong90.bpm.rules.api.DecisionRequest;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.NoEffectiveVersionException;
import io.github.dhluong90.bpm.rules.api.RuleSetNotFoundException;
import java.util.Set;

/**
 * Evaluation for an embedded engine that acts on its own stored work, where no caller is signed in: a process step
 * reached after a timer, for example (ADR-0027). The tenant is explicit instead of resolved from the host's security
 * context, and the caller written to the decision log is the engine identity this object was built for.
 *
 * <p>Not for hosts. The Rule Engine never registers this type as a bean and its {@code RuleEngine} does not implement
 * it. Only the wiring of a registered engine gets one, for its own identity from {@link #REGISTERED_ENGINE_IDS}. That
 * engine passes the tenant it read from its own stored aggregate under that aggregate's lock; never a tenant taken
 * from a request, a variable or an input. There is no REST endpoint for it. No permission is checked: the calling
 * engine authorises its own step. The evaluation is otherwise the same as
 * {@link io.github.dhluong90.bpm.rules.api.RuleEngine#evaluate(DecisionRequest)}: the same input limits, idempotency
 * and one {@code rle_decision} row in the given tenant, written in the caller's transaction.
 */
public interface TenantScopedRuleEngine {

    /**
     * The engine identities that may hold this entry point. A closed list: any other {@code system:} id, such as
     * {@code system:timer} or {@code system:dev-auto-approve}, is refused when the object is built.
     */
    Set<String> REGISTERED_ENGINE_IDS = Set.of("system:process-engine");

    /** @return the engine identity written as the caller of every decision, one of {@link #REGISTERED_ENGINE_IDS} */
    String engineId();

    /**
     * Evaluates the version of a rule set effective at the engine clock, in the given tenant, as {@link #engineId()}.
     *
     * @param tenantId the tenant, read by the calling engine from its stored aggregate; not blank
     * @param request the request
     * @return the logged result; on a replay of the same key and request, the original result
     * @throws IllegalArgumentException when the tenant is blank
     * @throws RuleSetNotFoundException when the tenant has no such rule set ({@code RULE_SET_NOT_FOUND})
     * @throws NoEffectiveVersionException when no published version is effective ({@code NO_EFFECTIVE_VERSION})
     * @throws InputInvalidException when an input has the wrong type ({@code INPUT_INVALID})
     */
    DecisionResult evaluate(String tenantId, DecisionRequest request);
}
