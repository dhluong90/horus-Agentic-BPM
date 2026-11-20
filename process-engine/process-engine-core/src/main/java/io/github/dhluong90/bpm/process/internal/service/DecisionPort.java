package io.github.dhluong90.bpm.process.internal.service;

import java.time.Instant;
import java.util.Map;

/**
 * How a {@code businessRuleTask} reaches a decision engine (ADR-0027). The Process Engine core has no dependency on
 * the Rule Engine: the process starter adapts this port to the Rule Engine when the host has one, and without one a
 * definition with a business rule task is refused at activation. Runs in the caller's transaction, so the decision
 * log row commits or rolls back with the step.
 */
@FunctionalInterface
public interface DecisionPort {

    /**
     * Evaluates a decision for a step.
     *
     * @param tenantId the instance's tenant, read from {@code pre_instance.tenant_id} under the instance lock; never
     *     from variables, inputs or the thread's security context
     * @param caller who the step runs for: the engine acts as {@link DecisionCaller#ENGINE_IDENTITY} on behalf of
     *     {@link DecisionCaller#onBehalfOf()}
     * @param decisionRef the decision (rule set) name
     * @param inputs the step's bounded inputs
     * @param evaluationTime the engine clock at the step; never a value taken from instance data
     * @param idempotencyKey derived by {@link DecisionKeys#forStep(String, String, String)} from instance, node and
     *     attempt, so a retried step replays the logged decision instead of writing a second one
     * @param correlationId the instance id, written to the decision log
     * @return the outcome; never throws for a refused decision
     * @throws io.github.dhluong90.bpm.process.api.TenantNotAllowedException when the caller is a request whose
     *     tenant is not {@code tenantId}; nothing is evaluated or written
     */
    DecisionOutcome evaluate(String tenantId, DecisionCaller caller, String decisionRef, Map<String, Object> inputs,
            Instant evaluationTime, String idempotencyKey, String correlationId);

    /**
     * Describes the version of a rule set in effect at an instant, in a tenant, without evaluating it: used at import
     * and approval to check that a guardrail rule set meets its contract (PRE-016). Reads only. The default cannot
     * describe anything, so a definition that needs a contract is refused (fail closed).
     *
     * @param tenantId the tenant resolved from the authenticated context; never from a request body
     * @param decisionRef the rule set name
     * @param at the engine clock
     * @return the signature, or why there is none; never throws for a missing rule set
     */
    default DecisionSignature signature(String tenantId, String decisionRef, Instant at) {
        return new DecisionSignature.Unavailable("SIGNATURE_UNAVAILABLE",
                "The host's decision engine cannot describe rule set " + decisionRef);
    }

    /**
     * Describes a stored version of a rule set by number, in any status, without evaluating it: used before an offline
     * replay to check that a candidate guardrail meets its contract (PRE-094). Reads only. The default cannot describe
     * anything, so the replay is refused (fail closed).
     *
     * @param tenantId the tenant resolved from the authenticated context; never from a request body
     * @param decisionRef the rule set name
     * @param version the version number
     * @return the signature, or why there is none ({@code VERSION_NOT_FOUND} for an unknown version or rule set);
     *     never throws for a missing rule set
     */
    default DecisionSignature signature(String tenantId, String decisionRef, int version) {
        return new DecisionSignature.Unavailable("SIGNATURE_UNAVAILABLE",
                "The host's decision engine cannot describe rule set " + decisionRef + " version " + version);
    }

    /**
     * Evaluates a stored version of a rule set over recorded cases, each at its own evaluation time, writing nothing
     * (offline replay, PRE-092): no decision row, no idempotency record, no event. The default cannot simulate, so
     * every case fails with {@code SIMULATION_UNAVAILABLE}.
     *
     * @param tenantId the tenant resolved from the authenticated context
     * @param decisionRef the rule set name
     * @param version the version number
     * @param cases the inputs and evaluation time of each case
     * @return one outcome per case, in order; a matched or unmatched outcome has no decision id
     */
    default java.util.List<DecisionOutcome> simulate(String tenantId, String decisionRef, int version,
            java.util.List<SimulatedCase> cases) {
        return cases.stream().<DecisionOutcome>map(c -> new DecisionOutcome.Failed("SIMULATION_UNAVAILABLE",
                "The host's decision engine cannot simulate")).toList();
    }

    /**
     * One case of {@link #simulate}.
     *
     * @param inputs the inputs
     * @param evaluationTime the instant the case was originally decided at; feeds {@code today()}
     */
    record SimulatedCase(Map<String, Object> inputs, Instant evaluationTime) {
        public SimulatedCase {
            inputs = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(inputs));
            java.util.Objects.requireNonNull(evaluationTime, "evaluationTime must not be null");
        }
    }
}
