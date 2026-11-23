package io.github.dhluong90.bpm.process.internal.exec.behavior.event;

import io.github.dhluong90.bpm.process.internal.exec.BehaviorModule;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorRegistry;

/**
 * Behaviours of the events stream: timer, message, signal, conditional, error and escalation events, boundary events,
 * event-based gateway. This release registers the intermediate timer catch event with a duration or a date, which
 * the replica-safe job claim (PRE-006) fires, the boundary events (#489) and the error, escalation, terminate and link
 * events (#493), the signal and conditional events (#492), and the message set: start, intermediate catch and throw
 * and end events, receive and send task (#491). The rest of the event set follows in this module.
 */
public final class EventBehaviors implements BehaviorModule {

    @Override
    public void register(BehaviorRegistry registry) {
        registry.register(new TimerCatchEventBehavior());
        // #489: boundary events on tasks, call activities and sub-processes.
        registry.register(new BoundaryTimerBehavior());
        registry.register(BoundaryCatchBehavior.forError());
        registry.register(BoundaryCatchBehavior.forEscalation());
        // #493: error and escalation throws, terminate end, link pair.
        registry.register(new ThrowEventBehaviors.ErrorEnd());
        registry.register(new ThrowEventBehaviors.EscalationEnd());
        registry.register(new ThrowEventBehaviors.EscalationThrow());
        registry.register(new ThrowEventBehaviors.TerminateEnd());
        registry.register(new ThrowEventBehaviors.LinkThrow());
        registry.register(new ThrowEventBehaviors.LinkCatch());
        registry.register(new SignalCatchEventBehavior());
        registry.register(new SignalThrowEventBehavior());
        registry.register(new SignalEndEventBehavior());
        registry.register(new SignalStartEventBehavior());
        registry.register(new ConditionalCatchEventBehavior());
        registry.register(new ConditionalStartEventBehavior());
        registry.register(new MessageCatchEventBehavior());
        registry.register(new MessageThrowEventBehavior());
        registry.register(new MessageEndEventBehavior());
        registry.register(new MessageStartEventBehavior());
        registry.register(new ReceiveTaskBehavior());
        registry.register(new SendTaskBehavior());
    }
}
