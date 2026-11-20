package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.api.DecisionLogService;
import io.github.dhluong90.bpm.rules.api.RuleEngine;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.embedded.TenantScopedRuleEngine;
import io.github.dhluong90.bpm.rules.internal.store.DecisionLog;
import io.github.dhluong90.bpm.rules.internal.store.EventOutbox;
import io.github.dhluong90.bpm.rules.internal.store.RuleStore;

/**
 * The engine's entry points built over one set of adapters.
 *
 * @param ruleEngine evaluation
 * @param versionService import and lifecycle
 * @param cursorKeys the cursor key in memory; {@link CursorKeys#load()} runs at start-up
 * @param decisionLog reads of the decision log
 * @param maskingCheck the start-up check of the masking key (RLE-035), run after the migrations
 * @param lifecycleSweep retires superseded versions; the starter runs it every {@code lifecycle.sweep-interval}
 * @param cache the compiled versions of this replica, shared by evaluation and approval; the host's scheduler runs
 *     {@link CompiledCache#checkEpochs()} and its notification listener calls {@link CompiledCache#evict}
 */
public record RuleEngines(RuleEngine ruleEngine, VersionService versionService, CursorKeys cursorKeys,
        DecisionLogService decisionLog, MaskingStartupCheck maskingCheck, LifecycleSweep lifecycleSweep,
        CompiledCache cache) {

    /**
     * Builds the entry points.
     *
     * @param context shared engine context
     * @param store rule set storage
     * @param log decision log
     * @param outbox event outbox
     * @return the entry points; call {@code cursorKeys().load()} and {@code maskingCheck().verify(...)} at
     *     start-up, after the migrations
     */
    public static RuleEngines create(EngineContext context, RuleStore store, DecisionLog log, EventOutbox outbox) {
        CursorKeys keys = new CursorKeys(store, context.transactions(), context.clock());
        CompiledCache cache = new CompiledCache(context.settings().cacheMaxEntries(), store, context.transactions());
        Supersession supersession = new Supersession(store, outbox, context.settings());
        return new RuleEngines(new DefaultRuleEngine(context, store, log, cache),
                new DefaultVersionService(context, store, outbox, keys, supersession, cache), keys,
                new DefaultDecisionLogService(context, log, outbox), new MaskingStartupCheck(store, context.masking()),
                new LifecycleSweep(context, supersession), cache);
    }

    /**
     * Builds the tenant-scoped entry point for a registered embedded engine (ADR-0027). The identity is fixed here:
     * every decision it evaluates is logged with {@code engineId} as the caller. Not a bean: the calling engine's
     * wiring builds it and keeps it to itself.
     *
     * @param engineId the calling engine's identity, one of {@link TenantScopedRuleEngine#REGISTERED_ENGINE_IDS}
     * @return the entry point for that engine
     * @throws IllegalArgumentException when the identity is not a registered engine identity
     * @throws IllegalStateException when {@link #ruleEngine()} is not the default engine
     */
    public TenantScopedRuleEngine forEmbeddedEngine(String engineId) {
        if (engineId == null || !TenantScopedRuleEngine.REGISTERED_ENGINE_IDS.contains(engineId)) {
            throw new IllegalArgumentException("Not a registered engine identity: " + engineId);
        }
        if (!(ruleEngine instanceof DefaultRuleEngine engine)) {
            throw new IllegalStateException("The tenant-scoped entry point needs the default Rule Engine");
        }
        return new EngineScopedRuleEngine(engine, engineId);
    }
}
