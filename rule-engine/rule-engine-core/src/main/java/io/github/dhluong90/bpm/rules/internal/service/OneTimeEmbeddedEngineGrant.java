package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.embedded.EmbeddedEngineGrant;
import io.github.dhluong90.bpm.rules.embedded.TenantScopedRuleEngine;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The grant of one {@link RuleEngines}: each registered identity is claimed at most once, by its registered claimant.
 * The claimant is checked by its class, which a host cannot instantiate: the Process Engine's decision port is a
 * package-private class.
 */
final class OneTimeEmbeddedEngineGrant implements EmbeddedEngineGrant {

    private final DefaultRuleEngine engine;
    private final Map<String, String> claimants;
    private final Set<String> claimed = ConcurrentHashMap.newKeySet();

    OneTimeEmbeddedEngineGrant(DefaultRuleEngine engine) {
        this(engine, REGISTERED_CLAIMANTS);
    }

    /** @param claimants the claimant class name per engine identity; tests register their own */
    OneTimeEmbeddedEngineGrant(DefaultRuleEngine engine, Map<String, String> claimants) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.claimants = Map.copyOf(claimants);
    }

    @Override
    public TenantScopedRuleEngine claim(String engineId, Object claimant) {
        String registered = engineId == null ? null : claimants.get(engineId);
        if (registered == null) {
            throw new IllegalArgumentException("Not a registered engine identity: " + engineId);
        }
        if (claimant == null || !claimant.getClass().getName().equals(registered)) {
            throw new SecurityException("Only " + registered + " may claim the tenant-scoped entry point of "
                    + engineId + "; refused for " + (claimant == null ? "null" : claimant.getClass().getName()));
        }
        if (!claimed.add(engineId)) {
            throw new IllegalStateException("The tenant-scoped entry point of " + engineId + " was already claimed");
        }
        return new EngineScopedRuleEngine(engine, engineId);
    }

    @Override
    public boolean isClaimed(String engineId) {
        return engineId != null && claimed.contains(engineId);
    }
}
