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
import java.util.Map;

/** Shared enter and trigger of steps that invoke something and wait for its result. */
final class InvocationSupport {

    private InvocationSupport() {
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
                StepSupport.storeResult(node, completed.output(), ctx);
                Token active = token.withStatus(TokenStatus.ACTIVE);
                ctx.update(active);
                ctx.history("NODE_COMPLETED", token, Map.of());
                ctx.takeOutgoing(active, node);
            }
            case StepResult.BusinessError error -> throw new ExecutionException("UNHANDLED_BUSINESS_ERROR", node.id(),
                    error.code() + ": " + error.message());
            case StepResult.Failed failed -> throw new ExecutionException(failed.errorCode(), node.id(), failed.detail());
        }
    }

    /** The token waits on the same step for a person; the review task is its activity instance. */
    private static void review(FlowNode node, Token token, Command.RouteToReview review, ExecutionContext ctx) {
        String taskId = ctx.nextId();
        ctx.update(new Token(token.id(), token.kind(), TokenStatus.WAITING_USER, token.scopeTokenId(), token.nodeId(),
                taskId, token.locals()));
        ctx.effect(new Effect.CreateUserTask(taskId, token.id(), node.id(), node.name(), review.assignee(),
                review.fourEyes(), Map.of("code", review.code(), "reasons", review.reasons())));
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
        StepSupport.storeResult(node, complete.result(), ctx);
        Token active = token.withStatus(TokenStatus.ACTIVE);
        ctx.update(active);
        ctx.history("TASK_COMPLETED", token, Map.of("taskId", complete.taskId(), "review", true));
        ctx.history("NODE_COMPLETED", token, Map.of());
        ctx.effect(new Effect.EmitEvent(ctx.nextId(), "processes.task.completed.v1", Map.of("taskId",
                complete.taskId(), "nodeId", node.id(), "completedBy", String.valueOf(complete.actor()))));
        ctx.takeOutgoing(active, node);
    }
}
