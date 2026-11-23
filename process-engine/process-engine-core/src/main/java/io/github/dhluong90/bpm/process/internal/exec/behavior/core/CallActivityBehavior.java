package io.github.dhluong90.bpm.process.internal.exec.behavior.core;

import io.github.dhluong90.bpm.process.internal.exec.Command;
import io.github.dhluong90.bpm.process.internal.exec.Effect;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionException;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.exec.TokenStatus;
import io.github.dhluong90.bpm.process.internal.model.bpmn.CallActivity;
import io.github.dhluong90.bpm.process.internal.model.horus.CalledVersion;
import io.github.dhluong90.bpm.process.internal.model.horus.OutputMapping;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code callProcess} (BPMN {@code callActivity}, #92, PRE-211 to PRE-214): the token waits as {@code WAITING_CHILD}
 * under a new attempt id while the shell starts a child instance of {@code calledElement} in the same tenant, and the
 * child's declared outputs come back when it ends. Variables cross only through {@code horus:input} (in) and
 * {@code horus:output} (out). A child that fails or is cancelled fails the step: with {@code horus:onFailure="compensate"}
 * the scope is undone, otherwise an incident is opened. A completed child is undone only by the step's own
 * {@code horus:compensate} handler (PD-127); the child's own stack is never walked.
 */
final class CallActivityBehavior implements FlowNodeBehavior<CallActivity> {

    /** The failure code of a step whose child ended {@code FAILED}. */
    static final String CHILD_FAILED = "CHILD_FAILED";
    /** The failure code of a step whose child was cancelled. */
    static final String CHILD_CANCELLED = "CHILD_CANCELLED";

    @Override
    public Class<CallActivity> nodeType() {
        return CallActivity.class;
    }

    @Override
    public void enter(CallActivity node, Token token, ExecutionContext ctx) {
        Map<String, Object> inputs = StepSupport.inputs(node, token, ctx);
        String attemptId = ctx.nextId();
        ctx.update(new Token(token.id(), token.kind(), TokenStatus.WAITING_CHILD, token.scopeTokenId(), token.nodeId(),
                attemptId, token.locals()));
        Integer version = node.extensions().first(CalledVersion.class).map(CalledVersion::version).orElse(null);
        ctx.effect(new Effect.StartChildInstance(node.calledElement(), token.id(), inputs, version, attemptId));
    }

    @Override
    public void trigger(CallActivity node, Token token, Command trigger, ExecutionContext ctx) {
        if (!(trigger instanceof Command.ChildEnded ended)) {
            throw new ExecutionException("COMMAND_NOT_SUPPORTED", node.id(), "Expected the end of the called child");
        }
        switch (ended.outcome()) {
            case COMPLETED -> {
                Map<String, Object> brought = new LinkedHashMap<>();
                for (OutputMapping out : node.extensions().all(OutputMapping.class)) {
                    // Only what the step names is copied; an output the child never set is a failure of the step.
                    if (!ended.output().containsKey(out.name())) {
                        // The child ended, so there is nothing to retry: an operator resolves the incident.
                        ctx.effect(new Effect.RaiseIncident(token.id(), node.id(), "OUTPUT_VARIABLE_MISSING",
                                out.name()));
                        return;
                    }
                    brought.put(out.name(), ended.output().get(out.name()));
                }
                brought.forEach((k, v) -> ctx.variables().set(k, v));
                // PD-127: the call is one step; its undo is the step's own handler, which gets the child's id.
                // #488: the undo is the step's own handler, or the compensation activity of a compensation boundary event.
                InvocationSupport.undoHandler(node, ctx).ifPresent(undo -> {
                    Map<String, Object> snapshot = new LinkedHashMap<>(StepSupport.inputs(node, token, ctx));
                    snapshot.put(InvocationSupport.FORWARD_RESULT, brought);
                    snapshot.put("childInstanceId", ended.childInstanceId());
                    ctx.effect(new Effect.PushCompensation(token.scopeTokenId(), node.id(), ended.attemptId(),
                            undo, snapshot));
                });
                ctx.history("CHILD_COMPLETED", token, Map.of("childInstanceId", ended.childInstanceId()));
                Token active = token.withStatus(TokenStatus.ACTIVE);
                ctx.update(active);
                ctx.history("NODE_COMPLETED", token, Map.of());
                ctx.takeOutgoing(active, node);
            }
            case FAILED, CANCELLED -> {
                String code = ended.outcome() == Command.ChildOutcome.FAILED ? CHILD_FAILED : CHILD_CANCELLED;
                ctx.history("CHILD_ENDED", token, Map.of("childInstanceId", ended.childInstanceId(), "outcome",
                        ended.outcome().name(), "code", code));
                if (ctx.throwError(token, code, true)) {
                    // #489: an error boundary on the call, with this code or none, takes the failed child.
                    return;
                }
                if (InvocationSupport.compensates(node)) {
                    ctx.compensate(token, code);
                    return;
                }
                // The token stays on the step; an operator retries (a new child) or resolves the incident (#68).
                ctx.effect(new Effect.RaiseIncident(token.id(), node.id(), code, ended.reason() == null ? code
                        : ended.reason()));
            }
        }
    }
}
