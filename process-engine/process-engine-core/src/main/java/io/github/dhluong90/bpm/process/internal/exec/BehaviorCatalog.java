package io.github.dhluong90.bpm.process.internal.exec;

import io.github.dhluong90.bpm.process.internal.exec.behavior.core.CoreBehaviors;
import io.github.dhluong90.bpm.process.internal.exec.behavior.event.EventBehaviors;
import io.github.dhluong90.bpm.process.internal.exec.behavior.gateway.GatewayBehaviors;
import io.github.dhluong90.bpm.process.internal.exec.behavior.loop.LoopBehaviors;
import io.github.dhluong90.bpm.process.internal.exec.behavior.scope.ScopeBehaviors;
import java.util.List;

/**
 * Every behaviour module. Fixed by the contract: no stream edits this file. A module that registers nothing for an
 * element leaves that element {@code BPMN_NOT_EXECUTABLE} at activation.
 */
public final class BehaviorCatalog {

    private BehaviorCatalog() {
    }

    /** @return the modules in registration order */
    public static List<BehaviorModule> modules() {
        return List.of(new CoreBehaviors(), new EventBehaviors(), new ScopeBehaviors(), new LoopBehaviors(),
                new GatewayBehaviors());
    }

    /** @return a registry filled by every module */
    public static BehaviorRegistry registry() {
        BehaviorRegistry registry = new BehaviorRegistry();
        modules().forEach(module -> module.register(registry));
        return registry;
    }
}
