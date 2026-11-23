package io.github.dhluong90.bpm.process.internal.exec.behavior.event;

import io.github.dhluong90.bpm.process.internal.exec.BehaviorModule;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorRegistry;

/**
 * Behaviours of the events stream: timer, message, signal, conditional, error and escalation events, boundary events,
 * event-based gateway. This release registers the intermediate timer catch event with a duration or a date, which
 * the replica-safe job claim (PRE-006) fires, and the message set: start, intermediate catch and throw and end events,
 * receive and send task (#491). The rest of the event set follows in this module.
 */
public final class EventBehaviors implements BehaviorModule {

    @Override
    public void register(BehaviorRegistry registry) {
        registry.register(new TimerCatchEventBehavior())
                .register(new MessageCatchEventBehavior())
                .register(new MessageThrowEventBehavior())
                .register(new MessageEndEventBehavior())
                .register(new MessageStartEventBehavior())
                .register(new ReceiveTaskBehavior())
                .register(new SendTaskBehavior());
    }
}
