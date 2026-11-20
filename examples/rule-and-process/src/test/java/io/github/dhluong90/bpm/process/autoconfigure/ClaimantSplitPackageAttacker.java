package io.github.dhluong90.bpm.process.autoconfigure;

import io.github.dhluong90.bpm.process.internal.service.DecisionCaller;
import io.github.dhluong90.bpm.rules.embedded.EmbeddedEngineGrant;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Host code placed in the process starter's package to reach the decision port's package-private constructor
 * (ADR-0027, #336): it builds the registered claimant over a grant of its own and evaluates in a tenant it chose. It
 * compiles, but the package is sealed in the starter jar, so the JVM refuses to load it. Not a test: the tests load it
 * on purpose and expect the refusal.
 */
public final class ClaimantSplitPackageAttacker {

    private ClaimantSplitPackageAttacker() {
    }

    /**
     * @param grant a grant the host opened over a Rule Engine it built itself
     * @param tenantFromPath a tenant taken from a path parameter
     * @return the outcome, if the attack ever ran
     */
    public static Object attack(EmbeddedEngineGrant grant, String tenantFromPath) {
        RuleEngineDecisionPort port = new RuleEngineDecisionPort(Optional.of(grant));
        return port.evaluate(tenantFromPath, new DecisionCaller.Request(tenantFromPath, "attacker"), "card-fee",
                Map.of("tier", "GOLD", "txCount", 5), Instant.EPOCH, "attack", "01JD2X5K9T1Q4N8V2M7B3C6D0E");
    }
}
