package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionRequest;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.HorusBpmException;
import io.github.dhluong90.bpm.rules.api.RuleEngine;
import io.github.dhluong90.bpm.rules.embedded.TenantScopedRuleEngine;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@link DecisionPort} over the embedded Rule Engine.
 * <ul>
 *   <li>Engine-owned work ({@link DecisionCaller.Engine}) calls the tenant-scoped entry point with the instance's
 *       tenant. That entry point is handed to the engine's wiring by the Rule Engine, bound to
 *       {@link DecisionCaller#ENGINE_IDENTITY}; it is never looked up from, or cast out of, the host's
 *       {@link RuleEngine}. Without it the step fails closed with {@code TENANT_NOT_ALLOWED}: the host's Rule Engine
 *       could only resolve a tenant from the thread, which is not the instance's.</li>
 *   <li>A request ({@link DecisionCaller.Request}) evaluates as the host's caller, whose tenant the engine has
 *       already checked against the instance's.</li>
 * </ul>
 * A refusal of the Rule Engine (rule set missing, no effective version, wrong input) is a failed outcome: the step
 * fails closed.
 */
public final class RuleEngineDecisionPort implements DecisionPort {

    private final RuleEngine ruleEngine;
    private final Optional<TenantScopedRuleEngine> engineWork;

    /**
     * @param ruleEngine the host's Rule Engine, for steps inside a request
     * @param engineWork the tenant-scoped entry point for engine-owned work, or empty when the Rule Engine has none
     * @throws IllegalArgumentException when the entry point is bound to another identity than
     *     {@link DecisionCaller#ENGINE_IDENTITY}
     */
    public RuleEngineDecisionPort(RuleEngine ruleEngine, Optional<TenantScopedRuleEngine> engineWork) {
        this.ruleEngine = Objects.requireNonNull(ruleEngine, "ruleEngine must not be null");
        this.engineWork = Objects.requireNonNull(engineWork, "engineWork must not be null");
        engineWork.ifPresent(scoped -> {
            if (!DecisionCaller.ENGINE_IDENTITY.equals(scoped.engineId())) {
                throw new IllegalArgumentException("The tenant-scoped entry point is bound to " + scoped.engineId()
                        + ", not " + DecisionCaller.ENGINE_IDENTITY);
            }
        });
    }

    @Override
    public DecisionOutcome evaluate(String tenantId, DecisionCaller caller, String decisionRef,
            Map<String, Object> inputs, String correlationId) {
        DecisionRequest request = DecisionRequest.of(decisionRef, DecisionInput.of(inputs))
                .withCorrelationId(correlationId);
        if (caller instanceof DecisionCaller.Engine && engineWork.isEmpty()) {
            return new DecisionOutcome.Failed("TENANT_NOT_ALLOWED", "The host's Rule Engine cannot evaluate in an "
                    + "explicit tenant, so a decision step with no caller cannot run");
        }
        DecisionResult result;
        try {
            result = switch (caller) {
                case DecisionCaller.Engine ignored ->
                        engineWork.get().evaluate(tenantId, request);
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
