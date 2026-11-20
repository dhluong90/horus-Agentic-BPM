package io.github.dhluong90.bpm.rules.embedded;

import java.util.Map;

/**
 * The one-time hand-off of the {@link TenantScopedRuleEngine} to a registered embedded engine (ADR-0027).
 *
 * <p>The grant is a capability, not the entry point. {@link #claim(String, Object)} returns the entry point once per
 * engine identity, and only to the claimant registered for that identity in {@link #REGISTERED_CLAIMANTS}: for
 * {@code system:process-engine}, the process starter's decision port, which keeps the entry point to itself and uses
 * it for every decision step of a process (ADR-0027). Any other claimant, any other identity, and a second claim are refused.
 *
 * <p>Host code that holds this object therefore cannot get the entry point. The claimant is checked by class identity,
 * and the registered claimant is a package-private class in a sealed package, so host code can neither create one nor
 * define a class beside it. In a running context the Process Engine has claimed the entry point at start-up, and the
 * Rule Engine starter closes any identity still unclaimed when start-up finishes.
 */
public interface EmbeddedEngineGrant {

    /**
     * The registered claimant class for each engine identity. A closed list: the keys are
     * {@link TenantScopedRuleEngine#REGISTERED_ENGINE_IDS}.
     */
    Map<String, String> REGISTERED_CLAIMANTS = Map.of("system:process-engine",
            "io.github.dhluong90.bpm.process.autoconfigure.RuleEngineDecisionPort");

    /**
     * Hands the entry point for {@code engineId} to its registered claimant, once.
     *
     * @param engineId the calling engine's identity, a key of {@link #REGISTERED_CLAIMANTS}
     * @param claimant the object that will hold the entry point; its class must be the registered claimant
     * @return the entry point, bound to {@code engineId}
     * @throws IllegalArgumentException when the identity is not registered
     * @throws SecurityException when the claimant is not the registered claimant for that identity
     * @throws IllegalStateException when the entry point for that identity was already claimed, or closed unclaimed
     */
    TenantScopedRuleEngine claim(String engineId, Object claimant);

    /**
     * @param engineId an engine identity
     * @return whether the entry point for that identity can still be claimed: registered, not claimed, not closed
     */
    boolean isOpen(String engineId);

    /** Closes every identity not claimed yet, for good. The Rule Engine starter calls it when start-up finishes. */
    void closeUnclaimed();

    /**
     * @param engineId an engine identity
     * @return whether the entry point for that identity has been claimed
     */
    boolean isClaimed(String engineId);
}
