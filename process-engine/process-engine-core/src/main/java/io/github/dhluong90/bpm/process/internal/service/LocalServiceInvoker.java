package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.internal.exec.Effect;
import io.github.dhluong90.bpm.process.internal.exec.StepResult;
import io.github.dhluong90.bpm.process.spi.HandlerResult;
import io.github.dhluong90.bpm.process.spi.ServiceCall;
import io.github.dhluong90.bpm.process.spi.ServiceTaskHandler;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Local handlers by name, and decisions through a {@link DecisionPort}; both inside the current transaction. A
 * decision always gets the instance's tenant explicitly, the engine clock and a key derived from instance, node and
 * attempt (ADR-0027). A decision's id is added
 * to the output as {@code decisionId}, so the instance links to the decision log. A decision with no matching row or
 * an evaluation error fails the step (fail closed).
 */
public final class LocalServiceInvoker implements ServiceInvoker {

    private static final System.Logger LOG = System.getLogger(LocalServiceInvoker.class.getName());

    private final Map<String, ServiceTaskHandler> handlers;
    private final Optional<DecisionPort> decisions;
    private final long slowCallNanos;
    private final LongSupplier ticker;

    /**
     * @param handlers registered handlers by name
     * @param decisions the decision port, or empty when the host has no decision engine; a definition with a business
     *     rule task is then refused at activation
     * @param slowCallWarning calls longer than this are logged at WARNING ({@code handlers.slow-call-warning})
     */
    public LocalServiceInvoker(Map<String, ServiceTaskHandler> handlers, Optional<DecisionPort> decisions,
            java.time.Duration slowCallWarning) {
        this(handlers, decisions, slowCallWarning, System::nanoTime);
    }

    /**
     * @param handlers registered handlers by name
     * @param decisions the decision port, or empty when the host has no decision engine
     * @param slowCallWarning calls longer than this are logged at WARNING ({@code handlers.slow-call-warning})
     * @param ticker the monotonic nanosecond source the call is timed with; {@code System::nanoTime} in production
     */
    public LocalServiceInvoker(Map<String, ServiceTaskHandler> handlers, Optional<DecisionPort> decisions,
            java.time.Duration slowCallWarning, LongSupplier ticker) {
        this.handlers = Map.copyOf(handlers);
        this.decisions = java.util.Objects.requireNonNull(decisions, "decisions must not be null");
        this.slowCallNanos = slowCallWarning.toNanos();
        this.ticker = java.util.Objects.requireNonNull(ticker, "ticker must not be null");
    }

    /** @param name handler name @return whether it is registered */
    public boolean hasHandler(String name) {
        return handlers.containsKey(name);
    }

    /**
     * @return the decision port, for the guardrails of the result transaction (#363); empty without a Rule Engine
     */
    Optional<DecisionPort> decisionPort() {
        return decisions;
    }

    /** @return whether a decision engine is present */
    public boolean hasRuleEngine() {
        return decisions.isPresent();
    }

    @Override
    public StepResult invoke(String tenantId, String instanceId, String businessKey, Effect.InvokeService call,
            DecisionCaller caller, Instant now) {
        long started = ticker.getAsLong();
        try {
            return switch (call.kind()) {
                case HANDLER -> handler(tenantId, instanceId, businessKey, call);
                case DECISION -> decision(tenantId, instanceId, call, caller, now);
                default -> new StepResult.Failed("NOT_SUPPORTED", call.kind() + " invocations are not supported yet",
                        false);
            };
        } finally {
            long elapsed = ticker.getAsLong() - started;
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

    /**
     * A local undo of a compensation walk (#67, PRE-073): the handler named by the step's {@code horus:compensate},
     * in the caller's transaction, with the forward step's attempt id. A thrown exception propagates, so the undo and
     * everything written with it roll back, as for a forward step (PRE-071).
     *
     * @param tenantId the instance's tenant, from the locked row
     * @param instanceId the instance
     * @param businessKey the business key, or null
     * @param nodeId the step being undone
     * @param handlerName the undo handler
     * @param attemptId the undo's attempt id
     * @param forwardAttemptId the forward step's attempt id
     * @param inputs the entry's input snapshot
     * @return the handler's result
     */
    public StepResult undo(String tenantId, String instanceId, String businessKey, String nodeId, String handlerName,
            String attemptId, String forwardAttemptId, Map<String, Object> inputs) {
        ServiceTaskHandler handler = handlers.get(handlerName);
        if (handler == null) {
            return new StepResult.Failed("HANDLER_NOT_REGISTERED", "No ServiceTaskHandler named " + handlerName, false);
        }
        long started = ticker.getAsLong();
        HandlerResult result;
        try {
            result = handler.handle(new ServiceCall(tenantId, instanceId, nodeId, businessKey, attemptId, inputs,
                    forwardAttemptId));
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "process.handler.error target={0} node={1} instance={2} exception={3}"
                    + " undo=true", handlerName, nodeId, instanceId, e.getClass().getName());
            throw new LocalHandlerException(handlerName, nodeId, e);
        } finally {
            long elapsed = ticker.getAsLong() - started;
            if (elapsed > slowCallNanos) {
                LOG.log(System.Logger.Level.WARNING, "process.step.slow kind=UNDO target={0} node={1} instance={2} "
                        + "elapsedMs={3} thresholdMs={4}", handlerName, nodeId, instanceId, elapsed / 1_000_000,
                        slowCallNanos / 1_000_000);
            }
        }
        return switch (result) {
            case HandlerResult.Completed c -> new StepResult.Completed(c.output());
            case HandlerResult.BusinessError e -> new StepResult.BusinessError(e.code(), e.message());
            case HandlerResult.Failed f -> new StepResult.Failed(f.errorCode(), f.detail(), f.retryable());
        };
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
            // PRE-071: the exception propagates, so the step and everything written in its transaction (the handler's
            // own rows included) roll back. A request answers 500 INTERNAL with a correlation id only; a job firing is
            // retried after its backoff. The log names the step and the exception class, never its message.
            LOG.log(System.Logger.Level.WARNING, "process.handler.error target={0} node={1} instance={2} exception={3}",
                    call.target(), call.nodeId(), instanceId, e.getClass().getName());
            throw new LocalHandlerException(call.target(), call.nodeId(), e);
        }
        return switch (result) {
            case HandlerResult.Completed c -> new StepResult.Completed(c.output());
            case HandlerResult.BusinessError e -> new StepResult.BusinessError(e.code(), e.message());
            case HandlerResult.Failed f -> new StepResult.Failed(f.errorCode(), f.detail(), f.retryable());
        };
    }

    private StepResult decision(String tenantId, String instanceId, Effect.InvokeService call, DecisionCaller caller,
            Instant now) {
        if (decisions.isEmpty()) {
            // Unreachable for an activated definition: activation refuses a business rule task without one.
            return new StepResult.Failed("BPMN_NOT_EXECUTABLE", "The host has no decision engine", false);
        }
        String key = DecisionKeys.forStep(instanceId, call.nodeId(), call.attemptId());
        return switch (decisions.get().evaluate(tenantId, caller, call.target(), call.inputs(), now, key,
                instanceId)) {
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
