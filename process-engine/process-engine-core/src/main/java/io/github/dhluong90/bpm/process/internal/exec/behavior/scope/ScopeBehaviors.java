package io.github.dhluong90.bpm.process.internal.exec.behavior.scope;

import io.github.dhluong90.bpm.process.internal.exec.BehaviorModule;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorRegistry;

/**
 * Behaviours of the subprocess stream: embedded and event subprocesses, transactions, compensation, call activity. Owned by that stream; registers nothing until it lands.
 */
public final class ScopeBehaviors implements BehaviorModule {

    @Override
    public void register(BehaviorRegistry registry) {
        // Owned by the subprocess stream.
    }
}
