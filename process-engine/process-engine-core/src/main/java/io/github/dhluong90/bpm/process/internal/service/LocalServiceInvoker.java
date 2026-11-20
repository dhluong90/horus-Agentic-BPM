package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.rules.api.RuleEngine;
import io.github.dhluong90.bpm.rules.embedded.TenantScopedRuleEngine;
import io.github.dhluong90.bpm.process.internal.exec.Effect;
import io.github.dhluong90.bpm.process.internal.exec.StepResult;
import io.github.dhluong90.bpm.process.spi.HandlerResult;
import io.github.dhluong90.bpm.process.spi.ServiceCall;
import io.github.dhluong90.bpm.process.spi.ServiceTaskHandler;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Local handlers by name, and decisions through a {@link DecisionPort} over the {@link RuleEngine}; both inside the
 * current transaction. A decision always gets the instance's tenant explicitly (ADR-0027). A decision's id is added
 * to the output as {@code decisionId}, so the instance links to the decision log. A decision with no matching row or
 * an evaluation error fails the step (fail closed).
 */
public final class LocalServiceInvoker implements ServiceInvoker {

    private static final System.Logger LOG = System.getLogger(LocalServiceInvoker.class.getName());

    private final Map<String, ServiceTaskHandler> handlers;
    private final Optional<DecisionPort> decisions;
    private final long slowCallNanos;

    /**
     * @param handlers registered handlers by name
     * @param ruleEngine the Rule Engine, or empty when the host has none
     * @param slowCallWarning calls longer than this are logged at WARNING ({@code handlers.slow-call-warning})
     */
    public LocalServiceInvoker(Map<String, ServiceTaskHandler> handlers, Optional<RuleEngine> ruleEngine,
            java.time.Duration slowCallWarning) {
        this(handlers, ruleEngine, Optional.empty(), slowCallWarning);
    }

    /**
     * @param handlers registered handlers by name
     * @param ruleEngine the Rule Engine, or empty when the host has none
     * @param engineWork the Rule Engine's tenant-scoped entry point for this engine's identity, built by the wiring
     *     and never a bean; empty fails a decision after a timer closed with {@code TENANT_NOT_ALLOWED}
     * @param slowCallWarning calls longer than this are logged at WARNING ({@code handlers.slow-call-warning})
     */
    public LocalServiceInvoker(Map<String, ServiceTaskHandler> handlers, Optional<RuleEngine> ruleEngine,
            Optional<TenantScopedRuleEngine> engineWork, java.time.Duration slowCallWarning) {
        this.handlers = Map.copyOf(handlers);
        this.decisions = ruleEngine.map(engine -> new RuleEngineDecisionPort(engine, engineWork));
        this.slowCallNanos = slowCallWarning.toNanos();
    }

    /** @param name handler name @return whether it is registered */
    public boolean hasHandler(String name) {
        return handlers.containsKey(name);
    }

    /** @return whether a Rule Engine is present */
    public boolean hasRuleEngine() {
        return decisions.isPresent();
    }

    @Override
    public StepResult invoke(String tenantId, String instanceId, String businessKey, Effect.InvokeService call,
            DecisionCaller caller) {
        long started = System.nanoTime();
        try {
            return switch (call.kind()) {
                case HANDLER -> handler(tenantId, instanceId, businessKey, call);
                case DECISION -> decision(tenantId, instanceId, call, caller);
                default -> new StepResult.Failed("NOT_SUPPORTED", call.kind() + " invocations are not supported yet",
                        false);
            };
        } finally {
            long elapsed = System.nanoTime() - started;
            if (elapsed > slowCallNanos) {
                // Never the inputs: they may hold personal data.
                LOG.log(System.Logger.Level.WARNING, "process.step.slow kind={0} target={1} node={2} instance={3} "
                        + "elapsedMs={4} thresholdMs={5}: a local step holds the instance lock and a connection for "
                        + "its whole duration; a network call belongs in an asynchronous step (ADR-0008)",
                        call.kind(), call.target(), call.nodeId(), instanceId, elapsed / 1_000_000,
                        slowCallNanos / 1_000_000);
            }
        }
    }

    private StepResult handler(String tenantId, String instanceId, String businessKey, Effect.InvokeService call) {
        ServiceTaskHandler handler = handlers.get(call.target());
        if (handler == null) {
            return new StepResult.Failed("HANDLER_NOT_REGISTERED", "No ServiceTaskHandler named " + call.target(), false);
        }
        HandlerResult result;
        try {
            result = handler.handle(new ServiceCall(tenantId, instanceId, call.nodeId(), businessKey, call.attemptId(),
                    call.inputs()));
        } catch (RuntimeException e) {
            // The step fails and everything rolls back; the exception text stays in the log.
            LOG.log(System.Logger.Level.WARNING, "process.handler.error target={0} node={1} instance={2} exception={3}",
                    call.target(), call.nodeId(), instanceId, e.getClass().getName());
            return new StepResult.Failed("HANDLER_ERROR", String.valueOf(e.getMessage()), false);
        }
        return switch (result) {
            case HandlerResult.Completed c -> new StepResult.Completed(c.output());
            case HandlerResult.BusinessError e -> new StepResult.BusinessError(e.code(), e.message());
            case HandlerResult.Failed f -> new StepResult.Failed(f.errorCode(), f.detail(), f.retryable());
        };
    }

    private StepResult decision(String tenantId, String instanceId, Effect.InvokeService call, DecisionCaller caller) {
        if (decisions.isEmpty()) {
            return new StepResult.Failed("RULE_ENGINE_MISSING", "The host has no Rule Engine", false);
        }
        return switch (decisions.get().evaluate(tenantId, caller, call.target(), call.inputs(), instanceId)) {
            case DecisionOutcome.Matched m -> {
                Map<String, Object> output = new LinkedHashMap<>(m.outputs());
                output.put("decisionId", m.decisionId());
                yield new StepResult.Completed(output);
            }
            case DecisionOutcome.NoMatch n -> new StepResult.Failed("DECISION_NO_MATCH", "Rule set " + call.target()
                    + " matched no row (decision " + n.decisionId() + ")", false);
            case DecisionOutcome.Failed f -> new StepResult.Failed(f.code(), f.detail(), false);
        };
    }
}
