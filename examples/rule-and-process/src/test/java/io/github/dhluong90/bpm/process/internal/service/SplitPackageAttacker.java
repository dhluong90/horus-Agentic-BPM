package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.rules.api.RuleEngine;
import io.github.dhluong90.bpm.rules.embedded.EmbeddedEngineGrant;
import java.util.Map;
import java.util.Optional;

/**
 * Host code placed in the Process Engine's package to reach its package-private members (#336, security review on
 * #351): it builds the decision port over a grant of its own and asks for engine work in a tenant it chose. It
 * compiles, but the package is sealed, so the JVM refuses to load it next to the engine's jar. Not a test: the tests
 * load it on purpose and expect the refusal.
 */
public final class SplitPackageAttacker {

    private SplitPackageAttacker() {
    }

    /**
     * @param ruleEngine the host's Rule Engine
     * @param grant a grant the host opened over a Rule Engine it built itself
     * @param tenantFromPath a tenant taken from a path parameter
     * @return the outcome, if the attack ever ran
     */
    public static Object attack(RuleEngine ruleEngine, EmbeddedEngineGrant grant, String tenantFromPath) {
        RuleEngineDecisionPort port = new RuleEngineDecisionPort(ruleEngine, Optional.of(grant));
        return port.evaluate(tenantFromPath, new DecisionCaller.Engine("system:timer"), "card-fee",
                Map.of("tier", "GOLD", "txCount", 5), "01JD2X5K9T1Q4N8V2M7B3C6D0E");
    }
}
