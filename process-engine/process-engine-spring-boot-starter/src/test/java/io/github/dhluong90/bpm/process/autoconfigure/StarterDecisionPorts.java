package io.github.dhluong90.bpm.process.autoconfigure;

import io.github.dhluong90.bpm.process.internal.service.DecisionPort;
import io.github.dhluong90.bpm.rules.internal.service.EmbeddedEngineGrants;
import io.github.dhluong90.bpm.rules.internal.service.RuleEngines;
import java.util.Optional;

/**
 * The starter's decision port for tests that run the engine on PostgreSQL outside a Spring context: the same class
 * the auto-configuration builds, claiming a grant opened over the test's Rule Engine, as the Rule Engine starter
 * opens one per context (#336).
 */
public final class StarterDecisionPorts {

    private StarterDecisionPorts() {
    }

    /**
     * @param engines the test's Rule Engine
     * @return the starter's decision port, holding the claimed tenant-scoped entry point
     */
    public static DecisionPort over(RuleEngines engines) {
        return new RuleEngineDecisionPort(Optional.of(EmbeddedEngineGrants.open(engines)));
    }

    /** @return the starter's decision port over a Rule Engine that offers no grant */
    public static DecisionPort withoutEntryPoint() {
        return new RuleEngineDecisionPort(Optional.empty());
    }
}
