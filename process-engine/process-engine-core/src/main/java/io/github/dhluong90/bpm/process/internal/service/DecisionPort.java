package io.github.dhluong90.bpm.process.internal.service;

import java.util.Map;

/**
 * How a {@code businessRuleTask} reaches the Rule Engine (ADR-0027). Runs in the caller's transaction, so the
 * decision log row commits or rolls back with the step.
 */
@FunctionalInterface
public interface DecisionPort {

    /**
     * Evaluates a rule set for a step.
     *
     * @param tenantId the instance's tenant, read from {@code pre_instance.tenant_id} under the instance lock; never
     *     from variables, inputs or the thread's security context
     * @param caller who the step runs for
     * @param decisionRef the rule set name
     * @param inputs the step's bounded inputs
     * @param correlationId the instance id, written to the decision log
     * @return the outcome; never throws for a refused decision
     */
    DecisionOutcome evaluate(String tenantId, DecisionCaller caller, String decisionRef, Map<String, Object> inputs,
            String correlationId);
}
