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
}
