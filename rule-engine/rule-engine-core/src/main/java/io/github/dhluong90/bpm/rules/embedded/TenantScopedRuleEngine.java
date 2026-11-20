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
 * it. Only the registered claimant of an engine gets one, once, through {@link EmbeddedEngineGrant}, for its own identity
 * from {@link #REGISTERED_ENGINE_IDS}. That
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
    Set<String> REGISTERED_ENGINE_IDS = EmbeddedEngineGrant.REGISTERED_CLAIMANTS.keySet();

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

    /**
     * Evaluates for a step of the calling engine (ADR-0027): in the given tenant, as {@link #engineId()}, on behalf of
     * the human who triggered the step, at the calling engine's clock. {@code onBehalfOf} is written to the decision
     * log beside the caller. Neither {@code onBehalfOf} nor the evaluation time is part of the idempotency check: a
     * retried step with the same key replays the logged decision, with its original evaluation time, and writes no
     * second row.
     *
     * @param tenantId the tenant, read by the calling engine from its stored aggregate; not blank
     * @param onBehalfOf the human the engine acts for, or null for engine-owned work such as a timer
     * @param evaluationTime the calling engine's clock at the step; it selects the version in force and feeds
     *     {@code today()} and {@code now()}
     * @param request the request, with no clock and no pinned version
     * @return the logged result; on a replay of the same key and request, the original result
     * @throws IllegalArgumentException when the tenant is blank, the evaluation time is null, or the request carries a
     *     clock or a pinned version
     * @throws RuleSetNotFoundException when the tenant has no such rule set ({@code RULE_SET_NOT_FOUND})
     * @throws NoEffectiveVersionException when no published version is effective ({@code NO_EFFECTIVE_VERSION})
     * @throws InputInvalidException when an input has the wrong type ({@code INPUT_INVALID})
     */
    DecisionResult evaluate(String tenantId, String onBehalfOf, java.time.Instant evaluationTime,
            DecisionRequest request);

    /**
     * Describes the version of a rule set effective at an instant, in the given tenant: its hit policy and its declared
     * inputs and outputs, never its rules. Reads only; writes no decision. Used by the calling engine to check that a
     * rule set it will rely on meets its contract before it accepts a definition that names it (PRE-016).
     *
     * @param tenantId the tenant, resolved by the calling engine from the authenticated context or its stored
     *     aggregate; not blank
     * @param ruleSet the rule set name
     * @param at the instant that selects the version in force
     * @return the signature of the effective version
     * @throws IllegalArgumentException when the tenant is blank or the instant is null
     * @throws RuleSetNotFoundException when the tenant has no such rule set ({@code RULE_SET_NOT_FOUND})
     * @throws NoEffectiveVersionException when no published version is effective ({@code NO_EFFECTIVE_VERSION})
     */
    RuleSetSignature signature(String tenantId, String ruleSet, java.time.Instant at);

    /**
     * Describes a stored version of a rule set by number, in any status (normally a draft or a version pending
     * approval): its hit policy and declared inputs and outputs, never its rules. Reads only. Used by the calling
     * engine to check that a candidate guardrail meets its contract before it replays recorded work against it.
     *
     * @param tenantId the tenant, resolved by the calling engine from the authenticated context; not blank
     * @param ruleSet the rule set name
     * @param version the version number
     * @return the signature of that version
     * @throws IllegalArgumentException when the tenant is blank
     * @throws io.github.dhluong90.bpm.rules.api.VersionNotFoundException when the tenant has no such version
     *     ({@code VERSION_NOT_FOUND}), the same answer whether or not the rule set exists in another tenant
     * @throws UnsupportedOperationException from an implementation written before this method existed
     */
    default RuleSetSignature signature(String tenantId, String ruleSet, int version) {
        throw new UnsupportedOperationException("signature of a stored version is not supported by "
                + getClass().getName());
    }

    /**
     * Runs a stored version of a rule set, in any status, over recorded cases, each at its own evaluation time, in the
     * given tenant (the simulation path, RLE-015). Writes nothing: no decision row, idempotency record or event. A case
     * the version cannot evaluate (a wrong input type, an undeclared input the engine refuses, a regular expression
     * over its budget) is a {@link DecisionResult.Failed} outcome at its position; the other cases still run.
     *
     * @param tenantId the tenant, resolved by the calling engine from the authenticated context; not blank
     * @param ruleSet the rule set name
     * @param version the version number
     * @param cases the cases, at most {@code simulation.max-inputs}
     * @return one outcome per case, in order
     * @throws IllegalArgumentException when the tenant is blank, or there are no cases or too many
     * @throws io.github.dhluong90.bpm.rules.api.VersionNotFoundException when the tenant has no such version
     * @throws io.github.dhluong90.bpm.rules.api.ValidationFailedException when the stored version does not compile
     * @throws UnsupportedOperationException from an implementation written before this method existed
     */
    default java.util.List<DecisionResult.Outcome> simulate(String tenantId, String ruleSet, int version,
            java.util.List<TimedInput> cases) {
        throw new UnsupportedOperationException("simulate is not supported by " + getClass().getName());
    }
}
