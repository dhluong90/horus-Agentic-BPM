package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.embedded.EmbeddedEngineGrant;
import io.github.dhluong90.bpm.rules.embedded.TenantScopedRuleEngine;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A grant over one Rule Engine: each registered identity is claimed at most once, by its registered claimant, and
 * never after {@link #closeUnclaimed()}.
 *
 * <p>The claimant is checked by class identity: its class must be the very class that this grant's class loader
 * resolves for the registered name. A class of the same name defined by another class loader is refused. Within one
 * class loader the name is unique, and the claimant's package is sealed, so only the Process Engine's own jar can
 * provide it.
 */
final class OneTimeEmbeddedEngineGrant implements EmbeddedEngineGrant {

    private final DefaultRuleEngine engine;
    private final Map<String, String> claimants;
    private final Set<String> claimed = ConcurrentHashMap.newKeySet();
    private final Set<String> closed = ConcurrentHashMap.newKeySet();

    OneTimeEmbeddedEngineGrant(DefaultRuleEngine engine) {
        this(engine, REGISTERED_CLAIMANTS);
    }

    /** @param claimants the claimant class name per engine identity; tests register their own */
    OneTimeEmbeddedEngineGrant(DefaultRuleEngine engine, Map<String, String> claimants) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.claimants = Map.copyOf(claimants);
    }

    @Override
    public synchronized TenantScopedRuleEngine claim(String engineId, Object claimant) {
        String registered = engineId == null ? null : claimants.get(engineId);
        if (registered == null) {
            throw new IllegalArgumentException("Not a registered engine identity: " + engineId);
        }
        if (claimant == null || claimant.getClass() != registeredClass(registered)) {
            throw new SecurityException("Only " + registered + " may claim the tenant-scoped entry point of "
                    + engineId + "; refused for " + (claimant == null ? "null" : claimant.getClass().getName()));
        }
        if (claimed.contains(engineId)) {
            throw new IllegalStateException("The tenant-scoped entry point of " + engineId + " was already claimed");
        }
        if (closed.contains(engineId)) {
            throw new IllegalStateException("The tenant-scoped entry point of " + engineId + " was closed unclaimed "
                    + "when start-up finished");
        }
        claimed.add(engineId);
        return new EngineScopedRuleEngine(engine, engineId);
    }

    @Override
    public boolean isClaimed(String engineId) {
        return engineId != null && claimed.contains(engineId);
    }

    @Override
    public boolean isOpen(String engineId) {
        return engineId != null && claimants.containsKey(engineId) && !claimed.contains(engineId)
                && !closed.contains(engineId);
    }

    @Override
    public synchronized void closeUnclaimed() {
        for (String engineId : claimants.keySet()) {
            if (!claimed.contains(engineId)) {
                closed.add(engineId);
            }
        }
    }

    /**
     * The class this grant's own class loader resolves for the registered name, without initialising it. One constant
     * name from {@link #REGISTERED_CLAIMANTS} or a test registry; never a name from a definition, a request or an
     * expression.
     */
    private static Class<?> registeredClass(String name) {
        try {
            return Class.forName(name, false, OneTimeEmbeddedEngineGrant.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new SecurityException("The registered claimant " + name + " is not on this class path", e);
        }
    }
}
