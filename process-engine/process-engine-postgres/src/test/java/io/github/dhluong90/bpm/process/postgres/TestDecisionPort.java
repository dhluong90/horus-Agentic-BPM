package io.github.dhluong90.bpm.process.postgres;

import io.github.dhluong90.bpm.process.internal.service.DecisionCaller;
import io.github.dhluong90.bpm.process.internal.service.DecisionOutcome;
import io.github.dhluong90.bpm.process.internal.service.DecisionPort;
import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionRequest;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.HorusBpmException;
import io.github.dhluong90.bpm.rules.embedded.TenantScopedRuleEngine;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * The decision port these storage tests run with, over the real Rule Engine on the same database: the shape of the
 * starter's adapter (ADR-0027), which this module cannot depend on. The adapter's own rules (the request-tenant
 * check, the security log) are proven by the starter's tests; these tests prove the engine side: the instance's
 * tenant, the engine clock, the step key and the rollback with the step.
 */
final class TestDecisionPort implements DecisionPort {

    private final Optional<TenantScopedRuleEngine> engineWork;

    TestDecisionPort(Optional<TenantScopedRuleEngine> engineWork) {
        this.engineWork = engineWork;
    }

    @Override
    public DecisionOutcome evaluate(String tenantId, DecisionCaller caller, String decisionRef,
            Map<String, Object> inputs, Instant evaluationTime, String idempotencyKey, String correlationId) {
        if (engineWork.isEmpty()) {
            return new DecisionOutcome.Failed("TENANT_NOT_ALLOWED", "No tenant-scoped entry point");
        }
        DecisionResult result;
        try {
            result = engineWork.get().evaluate(tenantId, caller.onBehalfOf(), evaluationTime,
                    DecisionRequest.of(decisionRef, DecisionInput.of(inputs)).withIdempotencyKey(idempotencyKey)
                            .withCorrelationId(correlationId));
        } catch (HorusBpmException e) {
            return new DecisionOutcome.Failed(e.code(), e.getMessage());
        }
        return switch (result.outcome()) {
            case DecisionResult.Matched m -> new DecisionOutcome.Matched(result.decisionId(), m.outputs());
            case DecisionResult.NoMatch n -> new DecisionOutcome.NoMatch(result.decisionId());
            case DecisionResult.Failed f -> new DecisionOutcome.Failed(f.code(), f.detail());
        };
    }
}
