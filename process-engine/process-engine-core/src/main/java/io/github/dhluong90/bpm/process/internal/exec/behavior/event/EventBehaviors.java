package io.github.dhluong90.bpm.process.internal.exec.behavior.event;

import io.github.dhluong90.bpm.process.internal.exec.BehaviorModule;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorRegistry;

/**
 * Behaviours of the events stream: timer, message, signal, conditional, error and escalation events, boundary events,
 * event-based gateway. This release registers the intermediate timer catch event with a duration or a date, which
 * the replica-safe job claim (PRE-006) fires, and the signal and conditional events (#492); the rest of the event
 * set follows in this module.
 */
public final class EventBehaviors implements BehaviorModule {

    @Override
    public void register(BehaviorRegistry registry) {
        registry.register(new TimerCatchEventBehavior());
        registry.register(new SignalCatchEventBehavior());
        registry.register(new SignalThrowEventBehavior());
        registry.register(new SignalEndEventBehavior());
        registry.register(new SignalStartEventBehavior());
        registry.register(new ConditionalCatchEventBehavior());
        registry.register(new ConditionalStartEventBehavior());
    }
}
