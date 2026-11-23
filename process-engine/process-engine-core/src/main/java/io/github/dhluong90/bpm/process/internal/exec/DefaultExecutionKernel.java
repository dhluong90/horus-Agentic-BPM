package io.github.dhluong90.bpm.process.internal.exec;

import io.github.dhluong90.bpm.process.internal.model.bpmn.BoundaryEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import io.github.dhluong90.bpm.process.internal.model.bpmn.StartEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.TimerEventDefinition;
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
        InstanceStatus required = command instanceof Command.EndCompensation ? InstanceStatus.COMPENSATING
                : InstanceStatus.RUNNING;
        if (state.status() != required) {
            throw new ExecutionException("INVALID_STATE", null, "Instance " + state.instanceId() + " is " + state.status());
        }
        WorkingContext ctx = new WorkingContext(state, process, expressions, clock, actorOf(command));
        switch (command) {
            case Command.Start start -> start(start, ctx);
            case Command.CompleteTask complete -> trigger(complete.tokenId(), TokenStatus.WAITING_USER, null, complete, ctx);
            case Command.AsyncResult result -> trigger(result.tokenId(), TokenStatus.WAITING_ASYNC, result.attemptId(),
                    result, ctx);
            case Command.JobDue due -> trigger(due.tokenId(), TokenStatus.WAITING_TIMER, due.jobId(), due, ctx);
            case Command.DeliverSignal signal -> trigger(signal.tokenId(), TokenStatus.WAITING_EVENT,
                    signal.subscriptionId(), signal, ctx);
            case Command.ChildEnded ended -> trigger(ended.tokenId(), TokenStatus.WAITING_CHILD, ended.attemptId(),
                    ended, ctx);
            case Command.MoveToken move -> moveToken(move, ctx);
            case Command.RouteToReview review -> trigger(review.tokenId(), TokenStatus.WAITING_ASYNC, review.attemptId(),
                    review, ctx);
            case Command.Cancel cancel -> cancel(cancel, ctx);
            case Command.RetryStep retry -> retry(retry, ctx);
            case Command.Compensate compensate -> compensate(compensate, ctx);
            case Command.EndCompensation end -> ctx.endCompensation(end.scopeTokenId());
            default -> throw new ExecutionException("COMMAND_NOT_SUPPORTED", null,
                    command.getClass().getSimpleName() + " is not supported in this release");
        }
        drain(ctx);
        settleConditions(ctx);
        return ctx.transition();
    }

    /**
     * Conditional events (#492): when a command changed the instance variables, every token waiting on a conditional
     * catch event asks its condition again, in this transaction and for this instance only, never by polling. A token
     * that moves on may change variables itself, so the pass repeats until nothing fires, within the step budget.
     */
    private void settleConditions(WorkingContext ctx) {
        int passes = 0;
        boolean changed = ctx.variablesChanged();
        while (changed) {
            if (++passes > MAX_STEPS_PER_APPLY) {
                throw new ExecutionException("STEP_LIMIT", null, "Conditional events fire without end in one transaction");
            }
            java.util.Set<String> waiting = new java.util.LinkedHashSet<>();
            ctx.tree().all().stream().filter(t -> t.status() == TokenStatus.WAITING_EVENT)
                    .forEach(t -> waiting.add(t.id()));
            for (String id : waiting) {
                Token token = ctx.tree().get(id).orElseThrow();
                if (token.status() != TokenStatus.WAITING_EVENT) {
                    continue;
                }
                FlowNode node = node(token.nodeId());
                behaviorFor(node).trigger(node, token, new Command.VariablesChanged(Map.of(), ctx.actor()), ctx);
            }
            drain(ctx);
            changed = waiting.stream().anyMatch(id -> ctx.tree().get(id).map(Token::status).orElse(null)
                    != TokenStatus.WAITING_EVENT);
        }
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
        // A process has exactly one start event (the compiler checks), a none, signal or conditional one (#492).
        StartEvent startEvent = process.process().flowElements().stream().filter(StartEvent.class::isInstance)
                .map(StartEvent.class::cast).findFirst()
                .orElseThrow(() -> new ExecutionException("NO_START_EVENT", null, "No start event"));
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

    /** PRE-077: the waiting token enters its step again; the step's behaviour runs as on first entry. */
    private void retry(Command.RetryStep retry, WorkingContext ctx) {
        Token token = ctx.tree().get(retry.tokenId()).orElseThrow(() ->
                new ExecutionException("TOKEN_NOT_FOUND", null, "No token " + retry.tokenId()));
        if (token.status() != TokenStatus.WAITING_ASYNC && token.status() != TokenStatus.WAITING_CHILD
                || !retry.attemptId().equals(token.activityInstanceId())) {
            throw new ExecutionException("INVALID_STATE", token.nodeId(), "Token " + retry.tokenId()
                    + " does not wait on attempt " + retry.attemptId());
        }
        Token active = token.withStatus(TokenStatus.ACTIVE);
        ctx.update(active);
        ctx.enqueue(active);
    }

    /**
     * PRE-208: a migration moves a waiting token to a step of the instance's new version. The shell has cancelled what
     * the token waited for; the token enters the step as on arrival, so its behaviour opens a task or parks a job.
     */
    private void moveToken(Command.MoveToken move, WorkingContext ctx) {
        Token token = ctx.tree().get(move.tokenId()).orElseThrow(() ->
                new ExecutionException("TOKEN_NOT_FOUND", null, "No token " + move.tokenId()));
        if (!token.status().live() || token.kind() != TokenKind.FLOW) {
            throw new ExecutionException("INVALID_STATE", token.nodeId(), "Token " + move.tokenId() + " is "
                    + token.status() + " and cannot be moved");
        }
        node(move.nodeId());
        Token active = new Token(token.id(), token.kind(), TokenStatus.ACTIVE, token.scopeTokenId(), move.nodeId(), null,
                token.locals());
        ctx.update(active);
        ctx.history("TOKEN_MOVED", token, Map.of("from", token.nodeId(), "to", move.nodeId()));
        ctx.enqueue(active);
    }

    /** PRE-073: a step whose job failed for good, and which declares onFailure compensate, starts compensation. */
    private void compensate(Command.Compensate compensate, WorkingContext ctx) {
        Token token = ctx.tree().get(compensate.tokenId()).orElseThrow(() ->
                new ExecutionException("TOKEN_NOT_FOUND", null, "No token " + compensate.tokenId()));
        if (token.status() != TokenStatus.WAITING_ASYNC && token.status() != TokenStatus.WAITING_CHILD
                || !compensate.attemptId().equals(token.activityInstanceId())) {
            throw new ExecutionException("INVALID_STATE", token.nodeId(), "Token " + compensate.tokenId()
                    + " does not wait on attempt " + compensate.attemptId());
        }
        FlowNode node = node(token.nodeId());
        if (!node.extensions().first(io.github.dhluong90.bpm.process.internal.model.horus.OnFailure.class)
                .map(io.github.dhluong90.bpm.process.internal.model.horus.OnFailure::compensates).orElse(false)) {
            throw new ExecutionException("INVALID_STATE", node.id(), "Step " + node.id()
                    + " does not declare onFailure compensate");
        }
        ctx.compensate(token, compensate.cause());
    }

    private void cancel(Command.Cancel cancel, WorkingContext ctx) {
        Token root = ctx.tree().root().orElseThrow(() -> new ExecutionException("INVALID_STATE", null, "Not started"));
        ctx.cancelSubtree(root);
        ctx.status(InstanceStatus.CANCELLED);
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("status", "CANCELLED");
        details.put("reason", String.valueOf(cancel.reason()));
        if (cancel.actor() != null) {
            details.put("actor", cancel.actor());
        }
        ctx.history("INSTANCE_ENDED", null, details);
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("instanceId", ctx.snapshot().instanceId());
        if (cancel.actor() != null) {
            payload.put("cancelledBy", cancel.actor());
        }
        if (cancel.reason() != null) {
            payload.put("reason", cancel.reason());
        }
        ctx.effect(new Effect.EmitEvent(ctx.nextId(), "processes.instance.cancelled.v1", payload));
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
            armBoundaries(node, token, ctx);
            behaviorFor(node).enter(node, token, ctx);
        }
    }

    /**
     * #489: an activity with timer boundary events parks one timer token per boundary before it runs. Error and
     * escalation boundaries hold no token: they are found when something is thrown. A step entered again by a retry
     * keeps the timers it already has.
     */
    private void armBoundaries(FlowNode node, Token token, WorkingContext ctx) {
        for (BoundaryEvent boundary : process.boundariesOf(node.id())) {
            if (boundary.eventDefinitions().size() != 1
                    || !(boundary.eventDefinitions().get(0) instanceof TimerEventDefinition)
                    || ctx.hasLiveBoundaryToken(boundary, token.scopeTokenId())) {
                continue;
            }
            Token waiting = ctx.createToken(TokenKind.FLOW, boundary.id(), token.scopeTokenId());
            behaviorFor(boundary).enter(boundary, waiting, ctx);
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
            case Command.RetryStep r -> r.actor();
            case Command.VariablesChanged v -> v.actor();
            default -> null;
        };
    }
}
