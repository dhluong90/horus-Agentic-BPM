package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.api.DecisionLogService;
import io.github.dhluong90.bpm.rules.api.DecisionLogQuery;
import io.github.dhluong90.bpm.rules.api.RuleEngine;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.embedded.EmbeddedEngineGrant;
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
 * @param decisionLogQuery decision log search
 * @param cache the compiled versions of this replica, shared by evaluation and approval; the host's scheduler runs
 *     {@link CompiledCache#checkEpochs()} and its notification listener calls {@link CompiledCache#evict}
 */
public record RuleEngines(RuleEngine ruleEngine, VersionService versionService, CursorKeys cursorKeys,
        DecisionLogService decisionLog, MaskingStartupCheck maskingCheck, LifecycleSweep lifecycleSweep,
        DecisionLogQuery decisionLogQuery, CompiledCache cache) {

    /**
     * Builds the entry points with the default search bounds.
     *
     * @param context shared engine context
     * @param store rule set storage
     * @param log decision log
     * @param outbox event outbox
     * @return the entry points; call {@code cursorKeys().load()} and {@code maskingCheck().verify(...)} at
     *     start-up, after the migrations
     */
    public static RuleEngines create(EngineContext context, RuleStore store, DecisionLog log, EventOutbox outbox) {
        return create(context, store, log, outbox, DecisionQuerySettings.defaults());
    }

    /**
     * Builds the entry points.
     *
     * @param context shared engine context
     * @param store rule set storage
     * @param log decision log
     * @param outbox event outbox
     * @param querySettings bounds of decision log searches
     * @return the entry points; call {@code cursorKeys().load()} at start-up, after the migrations
     */
    public static RuleEngines create(EngineContext context, RuleStore store, DecisionLog log, EventOutbox outbox,
            DecisionQuerySettings querySettings) {
        CursorKeys keys = new CursorKeys(store, context.transactions(), context.clock());
        CompiledCache cache = new CompiledCache(context.settings().cacheMaxEntries(), store, context.transactions());
        Supersession supersession = new Supersession(store, outbox, context.settings());
        return new RuleEngines(new DefaultRuleEngine(context, store, log, cache, querySettings),
                new DefaultVersionService(context, store, outbox, keys, supersession, cache), keys,
                new DefaultDecisionLogService(context, log, outbox), new MaskingStartupCheck(store, context.masking(),
                        context.transactions(), context.clock()),
                new LifecycleSweep(context, supersession), new DefaultDecisionLogQuery(context, log, keys, querySettings),
                cache);
    }

    /**
     * Opens the grant of the tenant-scoped entry point over this Rule Engine, once (ADR-0027, #336). The Rule Engine
     * starter calls it; {@link #create} never does. The grant is useful only to the Process Engine's decision port.
     *
     * @return the grant
     * @throws IllegalStateException when this Rule Engine already has a grant, or is not the default engine
     */
    public EmbeddedEngineGrant openEmbeddedEngineGrant() {
        return EmbeddedEngineGrants.open(this);
    }
}
