package io.github.dhluong90.bpm.process.autoconfigure;

import io.github.dhluong90.bpm.process.api.TenantNotAllowedException;
import io.github.dhluong90.bpm.process.internal.service.DecisionCaller;
import io.github.dhluong90.bpm.process.internal.service.DecisionOutcome;
import io.github.dhluong90.bpm.process.internal.service.DecisionPort;
import io.github.dhluong90.bpm.process.internal.service.DecisionSignature;
import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionRequest;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.HorusBpmException;
import io.github.dhluong90.bpm.rules.embedded.EmbeddedEngineGrant;
import io.github.dhluong90.bpm.rules.embedded.RuleSetSignature;
import io.github.dhluong90.bpm.rules.embedded.TenantScopedRuleEngine;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * The {@link DecisionPort} over the embedded Rule Engine (ADR-0027). Built by the process starter only when the Rule
 * Engine is on the class path and the host has a {@code RuleEngine}; never registered as a bean.
 *
 * <p>This class is the registered claimant of {@code system:process-engine} in
 * {@link EmbeddedEngineGrant#REGISTERED_CLAIMANTS} (#336). Its constructor claims the tenant-scoped entry point from the
 * Rule Engine's one-time grant and keeps it to itself; nothing else in the engines can claim it. The class and its
 * constructor are package-private, and its package is sealed in the starter jar, so host code can neither create an
 * instance nor define a class beside it.
 * <ul>
 *   <li>Every decision step is evaluated through the Rule Engine's tenant-scoped entry point, in the instance's
 *       tenant, as {@link DecisionCaller#ENGINE_IDENTITY}, on behalf of the user who triggered the step (null after a
 *       timer). The user therefore needs no {@code rules:evaluate}, and the decision log names both.</li>
 *   <li>A step inside a request whose tenant is not the instance's is refused with {@code TENANT_NOT_ALLOWED} before
 *       any rule set is read, and one security log line is written (PRE-047). The engine checks the same before it
 *       calls the port; this is the second line.</li>
 *   <li>The step's idempotency key and the engine clock go to the Rule Engine, so a retried step replays the logged
 *       decision, with its original evaluation time, instead of writing a second row (PRE-046).</li>
 *   <li>Without the entry point the step fails closed with {@code TENANT_NOT_ALLOWED}. A refusal of the Rule Engine
 *       (rule set missing, no effective version, wrong input) is a failed outcome: the step fails closed.</li>
 * </ul>
 */
final class RuleEngineDecisionPort implements DecisionPort {

    private static final Log SECURITY = LogFactory.getLog("io.github.dhluong90.bpm.process.security");

    private final Optional<TenantScopedRuleEngine> engineWork;

    /**
     * @param grant the Rule Engine's one-time grant of the tenant-scoped entry point, or empty when the Rule Engine has
     *     none; claimed here, once, as {@link DecisionCaller#ENGINE_IDENTITY}
     * @throws IllegalStateException when the grant was already claimed or closed
     * @throws IllegalArgumentException when the entry point is bound to another identity
     */
    RuleEngineDecisionPort(Optional<EmbeddedEngineGrant> grant) {
        this.engineWork = Objects.requireNonNull(grant, "grant must not be null")
                .map(g -> g.claim(DecisionCaller.ENGINE_IDENTITY, this));
        engineWork.ifPresent(scoped -> {
            if (!DecisionCaller.ENGINE_IDENTITY.equals(scoped.engineId())) {
                throw new IllegalArgumentException("The tenant-scoped entry point is bound to " + scoped.engineId()
                        + ", not " + DecisionCaller.ENGINE_IDENTITY);
            }
        });
    }

    @Override
    public DecisionOutcome evaluate(String tenantId, DecisionCaller caller, String decisionRef,
            Map<String, Object> inputs, Instant evaluationTime, String idempotencyKey, String correlationId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(caller, "caller must not be null");
        Objects.requireNonNull(evaluationTime, "evaluationTime must not be null");
        if (caller instanceof DecisionCaller.Request request && !tenantId.equals(request.tenantId())) {
            // Never the inputs, never the rule set's content: the tenants, the step's decision and the user only.
            SECURITY.warn("security.decision.tenant_refused requestTenant=" + request.tenantId() + " instanceTenant="
                    + tenantId + " decision=" + decisionRef + " user=" + request.userId() + " correlation="
                    + correlationId);
            throw new TenantNotAllowedException("The request's tenant is not the instance's tenant");
        }
        if (engineWork.isEmpty()) {
            return new DecisionOutcome.Failed("TENANT_NOT_ALLOWED", "The host's Rule Engine has no tenant-scoped "
                    + "entry point for the Process Engine, so a decision step cannot run in the instance's tenant");
        }
        DecisionRequest request = DecisionRequest.of(decisionRef, DecisionInput.of(inputs))
                .withIdempotencyKey(idempotencyKey)
                .withCorrelationId(correlationId);
        DecisionResult result;
        try {
            result = engineWork.get().evaluate(tenantId, caller.onBehalfOf(), evaluationTime, request);
        } catch (HorusBpmException e) {
            // Rule set missing, no effective version, wrong input type, key reused with other inputs: fail closed.
            return new DecisionOutcome.Failed(e.code(), e.getMessage());
        }
        return switch (result.outcome()) {
            case DecisionResult.Matched m -> new DecisionOutcome.Matched(result.decisionId(), m.outputs(),
                    result.matchedRuleIds());
            case DecisionResult.NoMatch n -> new DecisionOutcome.NoMatch(result.decisionId());
            case DecisionResult.Failed f -> new DecisionOutcome.Failed(f.code(), f.detail() + " (decision "
                    + result.decisionId() + ")");
        };
    }

    /**
     * PRE-016: the hit policy, inputs and outputs of the guardrail rule set's version in effect, read through the
     * tenant-scoped entry point in the tenant the engine resolved. Without the entry point, or when the Rule Engine
     * refuses (rule set missing, no version in effect, integrity failure), the answer is {@code Unavailable} and the
     * definition is refused.
     */
    @Override
    public DecisionSignature signature(String tenantId, String decisionRef, Instant at) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(at, "at must not be null");
        if (engineWork.isEmpty()) {
            return new DecisionSignature.Unavailable("TENANT_NOT_ALLOWED", "The host's Rule Engine has no "
                    + "tenant-scoped entry point for the Process Engine");
        }
        try {
            RuleSetSignature found = engineWork.get().signature(tenantId, decisionRef, at);
            return new DecisionSignature.Found(found.ruleSet(), found.version(), found.hitPolicy(), found.inputs(),
                    found.outputs(), found.rejectsUndeclaredInputs());
        } catch (HorusBpmException e) {
            return new DecisionSignature.Unavailable(e.code(), e.getMessage());
        }
    }

    /**
     * PRE-094: the signature of a candidate guardrail version by number, in any status, through the tenant-scoped entry
     * point in the caller's tenant. Reads only.
     */
    @Override
    public DecisionSignature signature(String tenantId, String decisionRef, int version) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        if (engineWork.isEmpty()) {
            return new DecisionSignature.Unavailable("TENANT_NOT_ALLOWED", "The host's Rule Engine has no "
                    + "tenant-scoped entry point for the Process Engine");
        }
        try {
            RuleSetSignature found = engineWork.get().signature(tenantId, decisionRef, version);
            return new DecisionSignature.Found(found.ruleSet(), found.version(), found.hitPolicy(), found.inputs(),
                    found.outputs(), found.rejectsUndeclaredInputs());
        } catch (HorusBpmException e) {
            return new DecisionSignature.Unavailable(e.code(), e.getMessage());
        }
    }

    /**
     * PRE-092: a candidate guardrail version over recorded answers through the Rule Engine's simulation path, each at
     * its recorded time; writes no decision row. A refusal of the whole call (version missing, does not compile) is a
     * failed outcome for every case.
     */
    @Override
    public java.util.List<DecisionOutcome> simulate(String tenantId, String decisionRef, int version,
            java.util.List<SimulatedCase> cases) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        if (cases.isEmpty()) {
            return java.util.List.of();
        }
        if (engineWork.isEmpty()) {
            return cases.stream().<DecisionOutcome>map(c -> new DecisionOutcome.Failed("TENANT_NOT_ALLOWED",
                    "The host's Rule Engine has no tenant-scoped entry point for the Process Engine")).toList();
        }
        java.util.List<DecisionResult.Outcome> outcomes;
        try {
            outcomes = engineWork.get().simulate(tenantId, decisionRef, version, cases.stream()
                    .map(c -> new io.github.dhluong90.bpm.rules.embedded.TimedInput(DecisionInput.of(c.inputs()),
                            c.evaluationTime())).toList());
        } catch (HorusBpmException e) {
            return cases.stream().<DecisionOutcome>map(c -> new DecisionOutcome.Failed(e.code(), e.getMessage()))
                    .toList();
        }
        return outcomes.stream().<DecisionOutcome>map(outcome -> switch (outcome) {
            case DecisionResult.Matched m -> new DecisionOutcome.Matched(null, m.outputs(), m.ruleIds());
            case DecisionResult.NoMatch n -> new DecisionOutcome.NoMatch(null);
            case DecisionResult.Failed f -> new DecisionOutcome.Failed(f.code(), f.detail());
        }).toList();
    }
}
