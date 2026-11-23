package io.github.dhluong90.bpm.process.internal.exec.behavior.scope;

import io.github.dhluong90.bpm.process.internal.exec.BehaviorModule;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorRegistry;

/**
 * Behaviours of the subprocess stream: embedded and event subprocesses, transactions, compensation, call activity.
 * This release registers the embedded {@code subProcess} (#486), the {@code saga} block (a BPMN {@code transaction})
 * and {@code throwCompensation} (#67); the
 * rest of the stream follows in this module.
 */
public final class ScopeBehaviors implements BehaviorModule {

    @Override
    public void register(BehaviorRegistry registry) {
        registry.register(new SagaBehavior());
        registry.register(new SubProcessBehavior());
        registry.register(new AdHocSubProcessBehavior());
        registry.register(new ThrowCompensationBehavior());
        registry.register(new CompensationEndBehavior.Compensate());
        registry.register(new CompensationEndBehavior.Cancel());
        // A compensation boundary and a cancel boundary hold no token; the walk and the cancel end find them.
        registry.register(BoundaryCompensationBehavior.forCompensation());
        registry.register(BoundaryCompensationBehavior.forCancel());
    }
}
