package io.github.dhluong90.bpm.process.internal.exec.behavior.core;

import io.github.dhluong90.bpm.process.internal.exec.Command;
import io.github.dhluong90.bpm.process.internal.exec.Effect;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionException;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionMode;
import io.github.dhluong90.bpm.process.internal.exec.ServiceKind;
import io.github.dhluong90.bpm.process.internal.exec.StepResult;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.exec.TokenStatus;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import io.github.dhluong90.bpm.process.internal.model.horus.CompensateHandlerRef;
import io.github.dhluong90.bpm.process.internal.model.horus.OnFailure;
import java.util.Map;

/** Shared enter and trigger of steps that invoke something and wait for its result. */
final class InvocationSupport {

    /** Incident code of a business error that no path of the process handles (PRE-040, PRE-111). */
    static final String UNHANDLED_BUSINESS_ERROR = "UNHANDLED_BUSINESS_ERROR";

    /** The key under which an undo's input snapshot carries the forward step's result (#67). */
    static final String FORWARD_RESULT = "forwardResult";

    private InvocationSupport() {
    }

    /** @return whether the step declares {@code horus:onFailure="compensate"} */
    static boolean compensates(FlowNode node) {
        return node.extensions().first(OnFailure.class).map(OnFailure::compensates).orElse(false);
    }

    static void invoke(FlowNode node, Token token, ExecutionContext ctx, ServiceKind kind, String target,
            ExecutionMode mode) {
        invoke(node, token, ctx, kind, target, mode, null);
    }

    /**
     * Parks the token in {@code WAITING_ASYNC} under a new attempt id and asks the shell for the call. For
     * {@link ExecutionMode#ASYNC} the attempt id is also the id of the job the shell stores, so cancelling the token
     * cancels the job (ADR-0008).
     */
    static void invoke(FlowNode node, Token token, ExecutionContext ctx, ServiceKind kind, String target,
            ExecutionMode mode, java.time.Duration timeout) {
        Map<String, Object> inputs = StepSupport.inputs(node, token, ctx);
        String attemptId = ctx.nextId();
        Token waiting = new Token(token.id(), token.kind(), TokenStatus.WAITING_ASYNC, token.scopeTokenId(),
                token.nodeId(), attemptId, token.locals());
        ctx.update(waiting);
        ctx.effect(new Effect.InvokeService(token.id(), node.id(), attemptId, kind, target, inputs, mode, timeout));
    }

    /**
     * Delivers what the step waited for: the result of its call, or, for a step whose result was refused (#363), a
     * route to a review task and later that task's completion, which completes the step with the reviewer's result.
     */
    static void resume(FlowNode node, Token token, Command trigger, ExecutionContext ctx) {
        switch (trigger) {
            case Command.AsyncResult result -> result(node, token, result, ctx);
            case Command.RouteToReview review -> review(node, token, review, ctx);
            case Command.CompleteTask complete -> reviewed(node, token, complete, ctx);
            default -> throw new ExecutionException("COMMAND_NOT_SUPPORTED", node.id(), "Expected a step result");
        }
    }

    private static void result(FlowNode node, Token token, Command.AsyncResult result, ExecutionContext ctx) {
        switch (result.result()) {
            case StepResult.Completed completed -> {
                // #67: a step with an undo pushes it onto its scope's stack before anything moves on, so the stack
                // holds the steps in the order they completed. The entry keeps what the undo needs: the step's
                // inputs and its result.
                pushUndo(node, token, result.attemptId(), completed.output(), ctx);
                StepSupport.storeResult(node, completed.output(), ctx);
                ctx.effect(new Effect.RecordResult(token.id(), node.id(), result.attemptId(), completed.output(),
                        false));
                Token active = token.withStatus(TokenStatus.ACTIVE);
                ctx.update(active);
                ctx.history("NODE_COMPLETED", token, Map.of());
                ctx.takeOutgoing(active, node);
            }
            case StepResult.BusinessError error -> {
                // PRE-040: a business error the process has no path for is not lost and not rolled back. The token
                // stays on the step and the shell opens an incident in this transaction; nothing is retried, because
                // a business error is not technical. Error events (S1) will catch it before this point. The
                // handler's message never leaves the engine log: it may carry personal data.
                if (ctx.throwError(token, error.code(), true)) {
                    // #489: an error boundary on the step or on a scope around it catches the code first; no incident.
                    return;
                }
                if (compensates(node)) {
                    // PRE-073: the step declares onFailure compensate, so its scope is undone instead.
                    ctx.compensate(token, error.code());
                    return;
                }
                ctx.effect(new Effect.RaiseIncident(token.id(), node.id(), UNHANDLED_BUSINESS_ERROR, error.code()));
            }
            case StepResult.Failed failed -> throw new ExecutionException(failed.errorCode(), node.id(), failed.detail());
        }
    }

    /**
     * #67: a completed step with an undo pushes it onto its scope's stack before anything moves on, so the stack holds
     * the steps in the order they completed. The entry keeps what the undo needs: the step's inputs, and its result
     * under {@link #FORWARD_RESULT}.
     */
    private static void pushUndo(FlowNode node, Token token, String forwardAttempt, Map<String, Object> output,
            ExecutionContext ctx) {
        undoHandler(node, ctx).ifPresent(handler -> {
            Map<String, Object> snapshot = new java.util.LinkedHashMap<>(StepSupport.inputs(node, token, ctx));
            snapshot.put(FORWARD_RESULT, output);
            ctx.effect(new Effect.PushCompensation(token.scopeTokenId(), node.id(), forwardAttempt, handler,
                    snapshot));
        });
    }

    /**
     * The undo of a step (#67, #488): its {@code horus:compensate} handler, or the handler of the compensation activity
     * that a compensation boundary event on the step points at.
     */
    static java.util.Optional<String> undoHandler(FlowNode node, ExecutionContext ctx) {
        java.util.Optional<String> declared = node.extensions().first(CompensateHandlerRef.class)
                .map(CompensateHandlerRef::handler);
        if (declared.isPresent()) {
            return declared;
        }
        return ctx.process().compensationActivityOf(node.id()).flatMap(a -> a.extensions()
                .first(io.github.dhluong90.bpm.process.internal.model.horus.HandlerRef.class)
                .map(io.github.dhluong90.bpm.process.internal.model.horus.HandlerRef::handler));
    }

    /** The token waits on the same step for a person; the review task is its activity instance. */
    private static void review(FlowNode node, Token token, Command.RouteToReview review, ExecutionContext ctx) {
        String taskId = ctx.nextId();
        ctx.update(new Token(token.id(), token.kind(), TokenStatus.WAITING_USER, token.scopeTokenId(), token.nodeId(),
                taskId, token.locals()));
        ctx.effect(new Effect.CreateUserTask(taskId, token.id(), node.id(), node.name(), review.assignee(),
                review.fourEyes(), review.context() != null ? review.context()
                        : Map.of("code", review.code(), "reasons", review.reasons())));
        ctx.history("STEP_ROUTED_TO_REVIEW", token, Map.of("taskId", taskId, "attemptId", review.attemptId(),
                "code", review.code(), "assignee", review.assignee()));
        ctx.effect(new Effect.EmitEvent(ctx.nextId(), "processes.task.created.v1", Map.of("taskId", taskId,
                "nodeId", node.id(), "assignee", review.assignee())));
    }

    /** The reviewer completed the review task: the step completes with the reviewer's result. */
    private static void reviewed(FlowNode node, Token token, Command.CompleteTask complete, ExecutionContext ctx) {
        if (!complete.taskId().equals(token.activityInstanceId())) {
            throw new ExecutionException("TASK_MISMATCH", node.id(), "Task " + complete.taskId()
                    + " is not the open review task of this token");
        }
        pushUndo(node, token, token.activityInstanceId(), complete.result(), ctx);
        StepSupport.storeResult(node, complete.result(), ctx);
        ctx.effect(new Effect.RecordResult(token.id(), node.id(), complete.taskId(), complete.result(), true));
        Token active = token.withStatus(TokenStatus.ACTIVE);
        ctx.update(active);
        ctx.history("TASK_COMPLETED", token, Map.of("taskId", complete.taskId(), "review", true));
        ctx.history("NODE_COMPLETED", token, Map.of());
        ctx.effect(new Effect.EmitEvent(ctx.nextId(), "processes.task.completed.v1", Map.of("taskId",
                complete.taskId(), "nodeId", node.id(), "completedBy", String.valueOf(complete.actor()))));
        ctx.takeOutgoing(active, node);
    }
}
