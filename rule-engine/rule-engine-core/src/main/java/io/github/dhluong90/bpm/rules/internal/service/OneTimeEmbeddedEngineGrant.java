package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.embedded.EmbeddedEngineGrant;
import io.github.dhluong90.bpm.rules.embedded.TenantScopedRuleEngine;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A grant over one Rule Engine: each registered identity is claimed at most once, by its registered claimant, and
 * never after {@link #closeUnclaimed()}.
 *
 * <p>A claimant is accepted only when all three hold: its class is the very class resolved for the registered name
 * (through the thread's context class loader, falling back to this grant's own); that class's package is sealed; and
 * its jar's {@code Implementation-Version} equals this Rule Engine's. A class of the same name from another loader, from
 * a class directory, from an unsealed jar, or from a jar of another version is refused.
 */
final class OneTimeEmbeddedEngineGrant implements EmbeddedEngineGrant {

    /** Why a resolved claimant class is not trusted, or empty when it is. */
    @FunctionalInterface
    interface ClaimantCheck {
        Optional<String> refusal(Class<?> claimant);
    }

    /** The {@code Implementation-Version} of the rule-engine-core jar, or null when it runs from a class directory. */
    static final String ENGINE_VERSION = OneTimeEmbeddedEngineGrant.class.getPackage().getImplementationVersion();

    private final DefaultRuleEngine engine;
    private final Map<String, String> claimants;
    private final ClaimantCheck check;
    private final Set<String> claimed = ConcurrentHashMap.newKeySet();
    private final Set<String> closed = ConcurrentHashMap.newKeySet();

    OneTimeEmbeddedEngineGrant(DefaultRuleEngine engine) {
        this(engine, REGISTERED_CLAIMANTS, claimant -> refusal(claimant, ENGINE_VERSION));
    }

    /**
     * @param claimants the claimant class name per engine identity; tests register their own
     * @param check the sealing and version check of a resolved claimant class; tests pass their own
     */
    OneTimeEmbeddedEngineGrant(DefaultRuleEngine engine, Map<String, String> claimants, ClaimantCheck check) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.claimants = Map.copyOf(claimants);
        this.check = Objects.requireNonNull(check, "check must not be null");
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
        Optional<String> refusal = check.refusal(claimant.getClass());
        if (refusal.isPresent()) {
            throw new SecurityException("The tenant-scoped entry point of " + engineId + " is not granted: "
                    + refusal.get());
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
     * Why the registered claimant of {@code engineId}, as it resolves now, would be refused.
     *
     * @param engineId an engine identity
     * @return empty when its class resolves, is in a sealed package and carries this Rule Engine's version
     */
    static Optional<String> registeredClaimantRefusal(String engineId) {
        String registered = engineId == null ? null : REGISTERED_CLAIMANTS.get(engineId);
        if (registered == null) {
            return Optional.of("not a registered engine identity: " + engineId);
        }
        try {
            return refusal(resolve(registered), ENGINE_VERSION);
        } catch (ClassNotFoundException e) {
            return Optional.of(registered + " is not on the class path");
        }
    }

    /**
     * @param claimant a resolved claimant class
     * @param engineVersion the Rule Engine's {@code Implementation-Version}, or null when it has none
     * @return why the class is not trusted, or empty when its package is sealed and carries {@code engineVersion}
     */
    static Optional<String> refusal(Class<?> claimant, String engineVersion) {
        Package pkg = claimant.getPackage();
        if (pkg == null || !pkg.isSealed()) {
            return Optional.of(claimant.getName() + " is not in a sealed package");
        }
        if (engineVersion == null) {
            return Optional.of("rule-engine-core has no Implementation-Version to compare " + claimant.getName()
                    + " with");
        }
        if (!engineVersion.equals(pkg.getImplementationVersion())) {
            return Optional.of(claimant.getName() + " has Implementation-Version " + pkg.getImplementationVersion()
                    + ", not the Rule Engine's " + engineVersion);
        }
        return Optional.empty();
    }

    /**
     * The class resolved for the registered name (see {@link #resolve}), without initialising it. One constant
     * name from {@link #REGISTERED_CLAIMANTS} or a test registry; never a name from a definition, a request or an
     * expression.
     */
    private static Class<?> registeredClass(String name) {
        try {
            return resolve(name);
        } catch (ClassNotFoundException e) {
            throw new SecurityException("The registered claimant " + name + " is not on this class path", e);
        }
    }

    /**
     * Resolves the name, uninitialised, through the thread's context class loader, so a layout that loads the Process
     * Engine in a child loader (an application server, a plugin loader) finds it, and falls back to this class's own
     * loader when the context loader is unset or does not see it.
     */
    private static Class<?> resolve(String name) throws ClassNotFoundException {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (context != null) {
            try {
                return Class.forName(name, false, context);
            } catch (ClassNotFoundException e) {
                // fall back to this class's own loader
            }
        }
        return Class.forName(name, false, OneTimeEmbeddedEngineGrant.class.getClassLoader());
    }
}
