package io.github.dhluong90.bpm.process.internal.exec.behavior.core;

import io.github.dhluong90.bpm.process.internal.exec.BehaviorModule;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorRegistry;

/**
 * Behaviours of the execution kernel stream: none start and end, user, service, business rule, manual and none tasks, exclusive and parallel gateways, terminate end, link events, script task, data objects and associations. Owned by that stream; registers nothing until it lands.
 */
public final class CoreBehaviors implements BehaviorModule {

    @Override
    public void register(BehaviorRegistry registry) {
        // Owned by the execution kernel stream.
    }
}
