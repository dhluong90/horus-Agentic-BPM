package com.horus.bpm.rules.internal.service;

import com.horus.bpm.rules.api.DecisionLogQuery;
import com.horus.bpm.rules.api.RuleEngine;
import com.horus.bpm.rules.api.VersionService;
import com.horus.bpm.rules.internal.store.DecisionLog;
import com.horus.bpm.rules.internal.store.EventOutbox;
import com.horus.bpm.rules.internal.store.RuleStore;

/**
 * The engine's entry points built over one set of adapters.
 *
 * @param ruleEngine evaluation
 * @param versionService import and lifecycle
 * @param cursorKeys the cursor key in memory; {@link CursorKeys#load()} runs at start-up
 * @param decisionLogQuery decision log search
 */
public record RuleEngines(RuleEngine ruleEngine, VersionService versionService, CursorKeys cursorKeys,
        DecisionLogQuery decisionLogQuery) {

    /**
     * Builds the entry points with the default search bounds.
     *
     * @param context shared engine context
     * @param store rule set storage
     * @param log decision log
     * @param outbox event outbox
     * @return the entry points; call {@code cursorKeys().load()} at start-up, after the migrations
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
        return new RuleEngines(new DefaultRuleEngine(context, store, log),
                new DefaultVersionService(context, store, outbox, keys), keys,
                new DefaultDecisionLogQuery(context, log, keys, querySettings));
    }
}
