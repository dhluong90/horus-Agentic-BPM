package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.embedded.EmbeddedEngineGrant;
import java.util.Objects;

/**
 * Opens the one-time grant of the tenant-scoped entry point over a set of engine entry points (ADR-0027, #336). Kept
 * apart from {@link RuleEngines#create}, so building a Rule Engine never hands out a grant. Engine wiring only: the
 * Rule Engine starter opens one per context, and nothing else in the engines calls it (ArchUnit).
 *
 * <p>A grant is useful only to its registered claimant, the Process Engine's decision port. That class is
 * package-private in a sealed package, so a grant opened by host code cannot be claimed by host code.
 */
public final class EmbeddedEngineGrants {

    private EmbeddedEngineGrants() {
    }

    /**
     * @param engines the entry points whose Rule Engine the tenant-scoped entry point evaluates with
     * @return a new grant, open for every registered engine identity
     * @throws IllegalStateException when the Rule Engine is not the default engine
     */
    public static EmbeddedEngineGrant open(RuleEngines engines) {
        Objects.requireNonNull(engines, "engines must not be null");
        if (!(engines.ruleEngine() instanceof DefaultRuleEngine engine)) {
            throw new IllegalStateException("The tenant-scoped entry point needs the default Rule Engine");
        }
        return new OneTimeEmbeddedEngineGrant(engine);
    }
}
