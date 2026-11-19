package io.github.dhluong90.bpm.process.internal.exec.behavior.core;

import io.github.dhluong90.bpm.process.internal.exec.BehaviorModule;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorRegistry;

/**
 * Behaviours of the execution kernel stream. This release: none start and end events, service task (local mode),
 * business rule task, user task, exclusive gateway and parallel gateway. Manual and none tasks, terminate end,
 * link events, script task and data associations follow in this module.
 */
public final class CoreBehaviors implements BehaviorModule {

    @Override
    public void register(BehaviorRegistry registry) {
        registry.register(new NoneStartEventBehavior())
                .register(new NoneEndEventBehavior())
                .register(new ServiceTaskBehavior())
                .register(new BusinessRuleTaskBehavior())
                .register(new UserTaskBehavior())
                .register(new ExclusiveGatewayBehavior())
                .register(new ParallelGatewayBehavior())
                .compileStep(new ConditionCompileStep())
                .compileStep(new ParallelJoinCompileStep());
    }
}
