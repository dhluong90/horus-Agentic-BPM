package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.embedded.EmbeddedEngineGrant;
import java.util.Objects;
import java.util.Optional;

/**
 * Opens the grant of the tenant-scoped entry point over a set of engine entry points (ADR-0027, #336). The opener is
 * package-private: the only route to it is {@link RuleEngines#openEmbeddedEngineGrant()}, which opens at most one grant
 * per Rule Engine, and {@link RuleEngines#create} never opens one.
 *
 * <p>A grant is useful only to its registered claimant, the Process Engine's decision port, and only when that class is
 * in a sealed package of a jar with this Rule Engine's {@code Implementation-Version}.
 */
public final class EmbeddedEngineGrants {

    private EmbeddedEngineGrants() {
    }

    /**
     * Why the registered claimant of {@code engineId} would be refused as the class path stands, for wiring that
     * registers the grant only when it can be claimed.
     *
     * @param engineId an engine identity, a key of {@link EmbeddedEngineGrant#REGISTERED_CLAIMANTS}
     * @return empty when the claimant class resolves, its package is sealed and its jar carries this Rule Engine's
     *     {@code Implementation-Version}; otherwise the reason
     */
    public static Optional<String> registeredClaimantRefusal(String engineId) {
        return OneTimeEmbeddedEngineGrant.registeredClaimantRefusal(engineId);
    }

    /**
     * @param engines the entry points whose Rule Engine the tenant-scoped entry point evaluates with
     * @return a new grant, open for every registered engine identity
     * @throws IllegalStateException when the Rule Engine is not the default engine, or already has a grant
     */
    static EmbeddedEngineGrant open(RuleEngines engines) {
        Objects.requireNonNull(engines, "engines must not be null");
        if (!(engines.ruleEngine() instanceof DefaultRuleEngine engine)) {
            throw new IllegalStateException("The tenant-scoped entry point needs the default Rule Engine");
        }
        if (!engine.markGrantOpened()) {
            throw new IllegalStateException("This Rule Engine already has a grant of its tenant-scoped entry point");
        }
        return new OneTimeEmbeddedEngineGrant(engine);
    }
}
