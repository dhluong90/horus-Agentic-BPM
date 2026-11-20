package io.github.dhluong90.bpm.process.postgres;

import io.github.dhluong90.bpm.process.internal.service.DecisionPort;
import io.github.dhluong90.bpm.rules.embedded.EmbeddedEngineGrant;
import io.github.dhluong90.bpm.rules.internal.service.RuleEngines;
import java.lang.reflect.Constructor;
import java.util.Optional;

/**
 * The process starter's decision port for the integration tests that run the engine on PostgreSQL outside a Spring
 * context: the very class the auto-configuration builds, loaded from the starter jar (sealed, with the engines'
 * version), claiming the grant the test's Rule Engine opens, once, as the Rule Engine starter does (#336). The class
 * and its constructor are package-private and its package is sealed, so test code cannot sit beside it; it is created
 * reflectively here, which the threat model leaves out of scope.
 */
final class StarterDecisionPorts {

    private static final String PORT = "io.github.dhluong90.bpm.process.autoconfigure.RuleEngineDecisionPort";

    private StarterDecisionPorts() {
    }

    /**
     * @param engines the test's Rule Engine
     * @return the starter's decision port, holding the claimed tenant-scoped entry point
     */
    static DecisionPort over(RuleEngines engines) {
        return create(Optional.of(engines.openEmbeddedEngineGrant()));
    }

    /** @return the starter's decision port over a Rule Engine that offers no grant */
    static DecisionPort withoutEntryPoint() {
        return create(Optional.empty());
    }

    private static DecisionPort create(Optional<EmbeddedEngineGrant> grant) {
        try {
            Constructor<?> constructor = Class.forName(PORT).getDeclaredConstructor(Optional.class);
            constructor.setAccessible(true);
            return (DecisionPort) constructor.newInstance(grant);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause() instanceof RuntimeException r ? r : new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("The process starter's decision port is not on the class path", e);
        }
    }
}
