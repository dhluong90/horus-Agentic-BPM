package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.api.RuleEngine;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.internal.store.DecisionLog;
import io.github.dhluong90.bpm.rules.internal.store.EventOutbox;
import io.github.dhluong90.bpm.rules.internal.store.RuleStore;

/**
 * The engine's two entry points built over one set of adapters.
 *
 * @param ruleEngine evaluation
 * @param versionService import and lifecycle
 * @param cursorKeys the cursor key in memory; {@link CursorKeys#load()} runs at start-up
 * @param cache the compiled versions of this replica, shared by evaluation and approval; the host's scheduler runs
 *     {@link CompiledCache#checkEpochs()} and its notification listener calls {@link CompiledCache#evict}
 */
public record RuleEngines(RuleEngine ruleEngine, VersionService versionService, CursorKeys cursorKeys,
        CompiledCache cache) {

    /**
     * Builds both entry points.
     *
     * @param context shared engine context
     * @param store rule set storage
     * @param log decision log
     * @param outbox event outbox
     * @return the entry points; call {@code cursorKeys().load()} at start-up, after the migrations
     */
    public static RuleEngines create(EngineContext context, RuleStore store, DecisionLog log, EventOutbox outbox) {
        CursorKeys keys = new CursorKeys(store, context.transactions(), context.clock());
        CompiledCache cache = new CompiledCache(context.settings().cacheMaxEntries(), store, context.transactions());
        return new RuleEngines(new DefaultRuleEngine(context, store, log, cache),
                new DefaultVersionService(context, store, outbox, keys, cache), keys, cache);
    }
}
