package io.github.dhluong90.bpm.process.internal.exec.behavior.loop;

import io.github.dhluong90.bpm.process.internal.exec.BehaviorModule;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorRegistry;

/**
 * Behaviours of the loop stream: standard loops and multi-instance. Owned by that stream; registers nothing until it lands.
 */
public final class LoopBehaviors implements BehaviorModule {

    @Override
    public void register(BehaviorRegistry registry) {
        // Owned by the loop stream.
    }
}
