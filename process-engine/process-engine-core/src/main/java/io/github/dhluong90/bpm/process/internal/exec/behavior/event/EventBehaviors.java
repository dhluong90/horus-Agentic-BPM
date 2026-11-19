package io.github.dhluong90.bpm.process.internal.exec.behavior.event;

import io.github.dhluong90.bpm.process.internal.exec.BehaviorModule;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorRegistry;

/**
 * Behaviours of the events stream: timer, message, signal, conditional, error and escalation events, boundary events, event-based gateway. Owned by that stream; registers nothing until it lands.
 */
public final class EventBehaviors implements BehaviorModule {

    @Override
    public void register(BehaviorRegistry registry) {
        // Owned by the events stream.
    }
}
