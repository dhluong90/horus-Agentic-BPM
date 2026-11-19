package com.horus.bpm.rules.internal.service;

import com.horus.bpm.rules.api.RuleEngine;
import com.horus.bpm.rules.api.VersionService;
import com.horus.bpm.rules.internal.store.DecisionLog;
import com.horus.bpm.rules.internal.store.EventOutbox;
import com.horus.bpm.rules.internal.store.RuleStore;

/**
 * The engine's two entry points built over one set of adapters.
 *
 * @param ruleEngine evaluation
 * @param versionService import and lifecycle
 */
public record RuleEngines(RuleEngine ruleEngine, VersionService versionService) {

    /**
     * Builds both entry points.
     *
     * @param context shared engine context
     * @param store rule set storage
     * @param log decision log
     * @param outbox event outbox
     * @return the entry points
     */
    public static RuleEngines create(EngineContext context, RuleStore store, DecisionLog log, EventOutbox outbox) {
        return new RuleEngines(new DefaultRuleEngine(context, store, log), new DefaultVersionService(context, store, outbox));
    }
}
