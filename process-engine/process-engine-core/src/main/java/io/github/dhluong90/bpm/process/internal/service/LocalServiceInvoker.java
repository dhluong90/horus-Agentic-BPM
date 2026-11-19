package io.github.dhluong90.bpm.process.internal.service;

import com.horus.bpm.rules.api.DecisionInput;
import com.horus.bpm.rules.api.DecisionRequest;
import com.horus.bpm.rules.api.DecisionResult;
import com.horus.bpm.rules.api.HorusBpmException;
import com.horus.bpm.rules.api.RuleEngine;
import io.github.dhluong90.bpm.process.internal.exec.Effect;
import io.github.dhluong90.bpm.process.internal.exec.StepResult;
import io.github.dhluong90.bpm.process.spi.HandlerResult;
import io.github.dhluong90.bpm.process.spi.ServiceCall;
import io.github.dhluong90.bpm.process.spi.ServiceTaskHandler;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Local handlers by name, and decisions through {@link RuleEngine#evaluate}; both inside the current transaction.
 * A decision's id is added to the output as {@code decisionId}, so the instance links to the decision log. A
 * decision with no matching row or an evaluation error fails the step (fail closed).
 */
public final class LocalServiceInvoker implements ServiceInvoker {

    private static final System.Logger LOG = System.getLogger(LocalServiceInvoker.class.getName());

    private final Map<String, ServiceTaskHandler> handlers;
    private final Optional<RuleEngine> ruleEngine;
    private final long slowCallNanos;

    /**
     * @param handlers registered handlers by name
     * @param ruleEngine the Rule Engine, or empty when the host has none
     * @param slowCallWarning calls longer than this are logged at WARNING ({@code handlers.slow-call-warning})
     */
    public LocalServiceInvoker(Map<String, ServiceTaskHandler> handlers, Optional<RuleEngine> ruleEngine,
            java.time.Duration slowCallWarning) {
        this.handlers = Map.copyOf(handlers);
        this.ruleEngine = ruleEngine;
        this.slowCallNanos = slowCallWarning.toNanos();
    }

    /** @param name handler name @return whether it is registered */
    public boolean hasHandler(String name) {
        return handlers.containsKey(name);
    }

    /** @return whether a Rule Engine is present */
    public boolean hasRuleEngine() {
        return ruleEngine.isPresent();
    }

    @Override
    public StepResult invoke(String tenantId, String instanceId, String businessKey, Effect.InvokeService call) {
        long started = System.nanoTime();
        try {
            return switch (call.kind()) {
                case HANDLER -> handler(tenantId, instanceId, businessKey, call);
                case DECISION -> decision(instanceId, call);
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
        HandlerResult result = handler.handle(new ServiceCall(tenantId, instanceId, call.nodeId(), businessKey,
                call.attemptId(), call.inputs()));
        return switch (result) {
            case HandlerResult.Completed c -> new StepResult.Completed(c.output());
            case HandlerResult.BusinessError e -> new StepResult.BusinessError(e.code(), e.message());
            case HandlerResult.Failed f -> new StepResult.Failed(f.errorCode(), f.detail(), f.retryable());
        };
    }

    private StepResult decision(String instanceId, Effect.InvokeService call) {
        if (ruleEngine.isEmpty()) {
            return new StepResult.Failed("RULE_ENGINE_MISSING", "The host has no Rule Engine", false);
        }
        DecisionResult result;
        try {
            result = ruleEngine.get().evaluate(DecisionRequest.of(call.target(), DecisionInput.of(call.inputs()))
                    .withCorrelationId(instanceId));
        } catch (HorusBpmException e) {
            // Rule set missing, no effective version, wrong input type, caller without rules:evaluate: fail closed.
            return new StepResult.Failed(e.code(), e.getMessage(), false);
        }
        return switch (result.outcome()) {
            case DecisionResult.Matched m -> {
                Map<String, Object> output = new LinkedHashMap<>(m.outputs());
                output.put("decisionId", result.decisionId());
                yield new StepResult.Completed(output);
            }
            case DecisionResult.NoMatch n -> new StepResult.Failed("DECISION_NO_MATCH", "Rule set " + call.target()
                    + " matched no row (decision " + result.decisionId() + ")", false);
            case DecisionResult.Failed f -> new StepResult.Failed(f.code(), f.detail() + " (decision "
                    + result.decisionId() + ")", false);
        };
    }
}
