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

    static void resume(FlowNode node, Token token, Command trigger, ExecutionContext ctx) {
        if (!(trigger instanceof Command.AsyncResult result)) {
            throw new ExecutionException("COMMAND_NOT_SUPPORTED", node.id(), "Expected a step result");
        }
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
}
