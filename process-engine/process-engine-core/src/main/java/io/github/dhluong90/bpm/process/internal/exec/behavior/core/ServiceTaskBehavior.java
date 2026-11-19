package io.github.dhluong90.bpm.process.internal.exec.behavior.core;

import io.github.dhluong90.bpm.process.internal.exec.Command;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionMode;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.NotExecutableException;
import io.github.dhluong90.bpm.process.internal.exec.ServiceKind;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ServiceTask;
import io.github.dhluong90.bpm.process.internal.model.horus.HandlerRef;

/**
 * Service task bound to a registered host handler ({@code horus:handler}). Local mode only in this release: the
 * shell runs the handler in the current transaction and returns the result at once.
 */
final class ServiceTaskBehavior implements FlowNodeBehavior<ServiceTask> {

    @Override
    public Class<ServiceTask> nodeType() {
        return ServiceTask.class;
    }

    @Override
    public void enter(ServiceTask node, Token token, ExecutionContext ctx) {
        HandlerRef handler = node.extensions().first(HandlerRef.class).orElseThrow(() -> new NotExecutableException(node));
        if (handler.mode() != null && !"local".equals(handler.mode())) {
            throw new NotExecutableException(node.id(), "serviceTask horus:mode=" + handler.mode());
        }
        InvocationSupport.invoke(node, token, ctx, ServiceKind.HANDLER, handler.handler(), ExecutionMode.SYNC_LOCAL);
    }

    @Override
    public void trigger(ServiceTask node, Token token, Command trigger, ExecutionContext ctx) {
        InvocationSupport.resume(node, token, trigger, ctx);
    }
}
