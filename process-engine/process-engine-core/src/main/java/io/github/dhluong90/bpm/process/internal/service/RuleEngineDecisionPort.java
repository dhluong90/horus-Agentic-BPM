package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionRequest;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.HorusBpmException;
import io.github.dhluong90.bpm.rules.api.RuleEngine;
import io.github.dhluong90.bpm.rules.api.TenantScopedRuleEngine;
import java.util.Map;
import java.util.Objects;

/**
 * The {@link DecisionPort} over the embedded Rule Engine.
 * <ul>
 *   <li>Engine-owned work ({@link DecisionCaller.Engine}) calls the tenant-scoped entry point with the instance's
 *       tenant and {@link DecisionCaller#ENGINE_IDENTITY}. A Rule Engine without that entry point fails the step
 *       closed with {@code TENANT_NOT_ALLOWED}: it could only resolve a tenant from the thread, which is not the
 *       instance's.</li>
 *   <li>A request ({@link DecisionCaller.Request}) evaluates as the host's caller, whose tenant the engine has
 *       already checked against the instance's.</li>
 * </ul>
 * A refusal of the Rule Engine (rule set missing, no effective version, wrong input) is a failed outcome: the step
 * fails closed.
 */
public final class RuleEngineDecisionPort implements DecisionPort {

    private final RuleEngine ruleEngine;

    /** @param ruleEngine the host's Rule Engine */
    public RuleEngineDecisionPort(RuleEngine ruleEngine) {
        this.ruleEngine = Objects.requireNonNull(ruleEngine, "ruleEngine must not be null");
    }

    @Override
    public DecisionOutcome evaluate(String tenantId, DecisionCaller caller, String decisionRef,
            Map<String, Object> inputs, String correlationId) {
        DecisionRequest request = DecisionRequest.of(decisionRef, DecisionInput.of(inputs))
                .withCorrelationId(correlationId);
        if (caller instanceof DecisionCaller.Engine && !(ruleEngine instanceof TenantScopedRuleEngine)) {
            return new DecisionOutcome.Failed("TENANT_NOT_ALLOWED", "The host's Rule Engine cannot evaluate in an "
                    + "explicit tenant, so a decision step with no caller cannot run");
        }
        DecisionResult result;
        try {
            result = switch (caller) {
                case DecisionCaller.Engine ignored ->
                        ((TenantScopedRuleEngine) ruleEngine).evaluate(tenantId, DecisionCaller.ENGINE_IDENTITY, request);
                case DecisionCaller.Request ignored -> ruleEngine.evaluate(request);
            };
        } catch (HorusBpmException e) {
            // Rule set missing, no effective version, wrong input type, caller without rules:evaluate: fail closed.
            return new DecisionOutcome.Failed(e.code(), e.getMessage());
        }
        return switch (result.outcome()) {
            case DecisionResult.Matched m -> new DecisionOutcome.Matched(result.decisionId(), m.outputs());
            case DecisionResult.NoMatch n -> new DecisionOutcome.NoMatch(result.decisionId());
            case DecisionResult.Failed f -> new DecisionOutcome.Failed(f.code(), f.detail() + " (decision "
                    + result.decisionId() + ")");
        };
    }
}
