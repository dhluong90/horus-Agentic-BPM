package io.github.dhluong90.bpm.process.internal.exec.behavior.core;

import io.github.dhluong90.bpm.process.internal.exec.BehaviorModule;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorRegistry;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ManualTask;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Task;

/**
 * Behaviours of the execution kernel stream. This release: none start and end events, service task (local mode),
 * business rule task, user task, plain, manual and script task, exclusive gateway and parallel gateway. Terminate
 * end, link events and data associations follow in this module.
 */
public final class CoreBehaviors implements BehaviorModule {

    @Override
    public void register(BehaviorRegistry registry) {
        registry.register(new NoneStartEventBehavior())
                .register(new NoneEndEventBehavior())
                .register(new PassThroughTaskBehavior<>(Task.class))
                .register(new PassThroughTaskBehavior<>(ManualTask.class))
                .register(new ScriptTaskBehavior())
                .register(new ServiceTaskBehavior())
                .register(new BusinessRuleTaskBehavior())
                .register(new UserTaskBehavior())
                .register(new CallActivityBehavior())
                .register(new ExclusiveGatewayBehavior())
                .register(new ParallelGatewayBehavior())
                .compileStep(new ConditionCompileStep())
                .compileStep(new ScriptCompileStep())
                .compileStep(new ParallelJoinCompileStep());
    }
}
