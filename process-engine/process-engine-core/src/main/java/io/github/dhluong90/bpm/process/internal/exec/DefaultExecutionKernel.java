package io.github.dhluong90.bpm.process.internal.exec;

import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import io.github.dhluong90.bpm.process.internal.model.bpmn.StartEvent;
import io.github.dhluong90.bpm.process.internal.model.horus.TaskAssignment;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;

/**
 * The kernel of contract C3 for one compiled definition version. Pure: the result depends only on the state, the
 * command and the clock. Tokens move through an agenda, so a long chain of synchronous steps does not recurse.
 */
public final class DefaultExecutionKernel implements ExecutionKernel {

    /** Most nodes one apply may enter, a guard against a cycle of synchronous steps. */
    static final int MAX_STEPS_PER_APPLY = 10_000;

    private final CompiledProcess process;
    private final BehaviorRegistry behaviors;
    private final ExpressionEvaluator expressions;

    /**
     * @param process the compiled definition
     * @param behaviors registered behaviours
     * @param expressions the expression evaluator
     */
    public DefaultExecutionKernel(CompiledProcess process, BehaviorRegistry behaviors, ExpressionEvaluator expressions) {
        this.process = Objects.requireNonNull(process, "process must not be null");
        this.behaviors = Objects.requireNonNull(behaviors, "behaviors must not be null");
        this.expressions = Objects.requireNonNull(expressions, "expressions must not be null");
    }

    @Override
    public Transition apply(InstanceState state, Command command, Clock clock) {
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(command, "command must not be null");
        if (state.status() != InstanceStatus.RUNNING) {
            throw new ExecutionException("INVALID_STATE", null, "Instance " + state.instanceId() + " is " + state.status());
        }
        WorkingContext ctx = new WorkingContext(state, process, expressions, clock, actorOf(command));
        switch (command) {
            case Command.Start start -> start(start, ctx);
            case Command.CompleteTask complete -> trigger(complete.tokenId(), TokenStatus.WAITING_USER, null, complete, ctx);
            case Command.AsyncResult result -> trigger(result.tokenId(), TokenStatus.WAITING_ASYNC, result.attemptId(),
                    result, ctx);
            case Command.JobDue due -> trigger(due.tokenId(), TokenStatus.WAITING_TIMER, due.jobId(), due, ctx);
            case Command.Cancel cancel -> cancel(cancel, ctx);
            default -> throw new ExecutionException("COMMAND_NOT_SUPPORTED", null,
                    command.getClass().getSimpleName() + " is not supported in this release");
        }
        drain(ctx);
        return ctx.transition();
    }

    private void start(Command.Start start, WorkingContext ctx) {
        if (!ctx.tree().all().isEmpty()) {
            throw new ExecutionException("INVALID_STATE", null, "Instance already started");
        }
        ctx.putAllVariables(start.variables());
        ctx.addParticipant(start.actor());
        Token root = ctx.createToken(TokenKind.SCOPE, process.process().id(), null);
        ctx.history("INSTANCE_STARTED", root, Map.of("definitionId", process.definitionId()));
        ctx.effect(new Effect.EmitEvent(ctx.nextId(), "processes.instance.started.v1",
                Map.of("instanceId", ctx.snapshot().instanceId(), "definitionId", process.definitionId())));
        StartEvent startEvent = process.nodes().stream().filter(StartEvent.class::isInstance)
                .map(StartEvent.class::cast).filter(s -> s.eventDefinitions().isEmpty()).findFirst()
                .orElseThrow(() -> new ExecutionException("NO_START_EVENT", null, "No none start event"));
        Token flow = ctx.createToken(TokenKind.FLOW, startEvent.id(), root.id());
        ctx.enqueue(flow);
    }

    private void trigger(String tokenId, TokenStatus expected, String attemptId, Command command, WorkingContext ctx) {
        Token token = ctx.tree().get(tokenId).orElseThrow(() ->
                new ExecutionException("TOKEN_NOT_FOUND", null, "No token " + tokenId));
        if (token.status() != expected) {
            throw new ExecutionException("INVALID_STATE", token.nodeId(), "Token " + tokenId + " is " + token.status()
                    + ", expected " + expected);
        }
        if (attemptId != null && !attemptId.equals(token.activityInstanceId())) {
            throw new ExecutionException("STALE_ATTEMPT", token.nodeId(), "Attempt " + attemptId
                    + " is not the current attempt of token " + tokenId);
        }
        FlowNode node = node(token.nodeId());
        if (command instanceof Command.CompleteTask complete) {
            boolean fourEyes = node.extensions().first(TaskAssignment.class).map(TaskAssignment::fourEyes).orElse(false);
            if (fourEyes && ctx.isParticipant(complete.actor())) {
                throw new ExecutionException("SELF_APPROVAL_FORBIDDEN", node.id(), "The identity that started this instance"
                        + " or completed an earlier task of it cannot complete this four-eyes task");
            }
        }
        behaviorFor(node).trigger(node, token, command, ctx);
        if (command instanceof Command.CompleteTask complete) {
            ctx.addParticipant(complete.actor());
        }
    }

    private void cancel(Command.Cancel cancel, WorkingContext ctx) {
        Token root = ctx.tree().root().orElseThrow(() -> new ExecutionException("INVALID_STATE", null, "Not started"));
        ctx.cancelSubtree(root);
        ctx.status(InstanceStatus.CANCELLED);
        ctx.history("INSTANCE_ENDED", null, Map.of("status", "CANCELLED", "reason", String.valueOf(cancel.reason())));
        ctx.effect(new Effect.EmitEvent(ctx.nextId(), "processes.instance.cancelled.v1", Map.of("instanceId", ctx.snapshot().instanceId())));
    }

    private void drain(WorkingContext ctx) {
        int steps = 0;
        String next;
        while ((next = ctx.nextOnAgenda()) != null) {
            if (++steps > MAX_STEPS_PER_APPLY) {
                throw new ExecutionException("STEP_LIMIT", null, "More than " + MAX_STEPS_PER_APPLY
                        + " synchronous steps in one transaction; the process has a cycle without a wait state");
            }
            Token token = ctx.tree().get(next).orElseThrow();
            if (token.status() != TokenStatus.ACTIVE) {
                continue;
            }
            FlowNode node = node(token.nodeId());
            ctx.history("NODE_ENTERED", token, Map.of());
            behaviorFor(node).enter(node, token, ctx);
        }
    }

    private FlowNode node(String id) {
        return process.node(id).orElseThrow(() -> new ExecutionException("UNKNOWN_NODE", id, "No flow node " + id));
    }

    private <N extends FlowNode> FlowNodeBehavior<N> behaviorFor(N node) {
        return behaviors.behaviorFor(node).orElseThrow(() -> new NotExecutableException(node));
    }

    private static String actorOf(Command command) {
        return switch (command) {
            case Command.Start s -> s.actor();
            case Command.CompleteTask c -> c.actor();
            case Command.Cancel c -> c.actor();
            case Command.VariablesChanged v -> v.actor();
            default -> null;
        };
    }
}
