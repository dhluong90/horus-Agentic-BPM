package io.github.dhluong90.bpm.process.internal.exec.behavior.core;

import io.github.dhluong90.bpm.process.internal.exec.Command;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionMode;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.NotExecutableException;
import io.github.dhluong90.bpm.process.internal.exec.ServiceKind;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ServiceTask;
import io.github.dhluong90.bpm.process.internal.model.horus.AgentTaskSpec;
import io.github.dhluong90.bpm.process.internal.model.horus.HandlerRef;

/**
 * Service task bound to a host handler ({@code horus:handler}). Local mode runs the registered handler in the current
 * transaction and returns the result at once; remote mode ({@code horus:mode="remote"}) parks the token in
 * {@code WAITING_ASYNC} and the shell calls the host's {@code ServiceAdapter} outside any transaction (ADR-0008). An
 * agent step ({@code horus:type="agentTask"}, #52) always waits the same way, for the host's {@code AgentAdapter}.
 */
final class ServiceTaskBehavior implements FlowNodeBehavior<ServiceTask> {

    @Override
    public Class<ServiceTask> nodeType() {
        return ServiceTask.class;
    }

    @Override
    public void enter(ServiceTask node, Token token, ExecutionContext ctx) {
        AgentTaskSpec agent = node.extensions().first(AgentTaskSpec.class).orElse(null);
        if (agent != null) {
            // Agent step (#52): always an Async Wait-State; the shell parks an agent job with the step's guard spec.
            InvocationSupport.invoke(node, token, ctx, ServiceKind.AGENT, agent.agent(), ExecutionMode.ASYNC,
                    agent.timeout());
            return;
        }
        HandlerRef handler = node.extensions().first(HandlerRef.class).orElseThrow(() -> new NotExecutableException(node));
        if ("remote".equals(handler.mode())) {
            // Async Wait-State: the token waits, the shell stores a job, and the call runs outside any transaction.
            InvocationSupport.invoke(node, token, ctx, ServiceKind.HANDLER, handler.handler(), ExecutionMode.ASYNC,
                    handler.timeout());
            return;
        }
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
