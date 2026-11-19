package com.horus.bpm.rules.internal.service;

import com.horus.bpm.rules.api.DecisionInput;
import com.horus.bpm.rules.api.DecisionRequest;
import com.horus.bpm.rules.api.DecisionResult;
import com.horus.bpm.rules.api.NoEffectiveVersionException;
import com.horus.bpm.rules.api.RuleEngine;
import com.horus.bpm.rules.api.RuleSetNotFoundException;
import com.horus.bpm.rules.internal.eval.CompiledRuleSet;
import com.horus.bpm.rules.internal.json.CanonicalJson;
import com.horus.bpm.rules.internal.json.EngineJson;
import com.horus.bpm.rules.internal.json.EngineValues;
import com.horus.bpm.rules.internal.json.Hashes;
import com.horus.bpm.rules.internal.json.Ulid;
import com.horus.bpm.rules.internal.model.InputClause;
import com.horus.bpm.rules.internal.read.RuleSetReader;
import com.horus.bpm.rules.internal.store.DecisionLog;
import com.horus.bpm.rules.internal.store.DecisionRecord;
import com.horus.bpm.rules.internal.store.RuleStore;
import com.horus.bpm.rules.internal.store.StoredVersion;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import tools.jackson.databind.node.ArrayNode;

/**
 * Evaluates the effective version and logs one decision row in the caller's transaction (RLE-013).
 * Compiled versions are cached by version id: a stored version never changes, so the cache needs no
 * invalidation. Its size is bounded by {@code cache.max-entries}; a full cache is cleared. Thread-safe.
 */
public final class DefaultRuleEngine implements RuleEngine {

    private final EngineContext context;
    private final RuleStore store;
    private final DecisionLog log;
    private final Map<String, CompiledRuleSet> compiled = new ConcurrentHashMap<>();

    /**
     * Creates the engine.
     *
     * @param context shared engine context
     * @param store rule set storage
     * @param log decision log
     */
    public DefaultRuleEngine(EngineContext context, RuleStore store, DecisionLog log) {
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.log = Objects.requireNonNull(log, "log must not be null");
    }

    @Override
    public DecisionResult evaluate(String ruleSet, DecisionInput input) {
        return evaluate(DecisionRequest.of(ruleSet, input));
    }

    @Override
    public DecisionResult evaluate(DecisionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String tenant = context.tenant();
        String caller = context.callers().currentCallerId();
        String requestHash = Hashes.sha256Parts("evaluate", request.ruleSet(),
                CanonicalJson.write(EngineValues.inputs(request.input(), Set.of())));
        return context.idempotent(tenant, "evaluate", request.idempotencyKey(), requestHash, 200,
                () -> doEvaluate(tenant, caller, request),
                result -> EngineValues.write(EngineValues.decision(result)),
                EngineValues::decisionFromJson);
    }

    private DecisionResult doEvaluate(String tenant, String caller, DecisionRequest request) {
        Instant now = context.clock().instant();
        StoredVersion version = store.findEffective(tenant, request.ruleSet(), now).orElseThrow(() ->
                store.findRuleSetId(tenant, request.ruleSet()).isPresent()
                        ? new NoEffectiveVersionException(request.ruleSet())
                        : new RuleSetNotFoundException(request.ruleSet()));
        CompiledRuleSet rules = compiled(version);
        long started = System.nanoTime();
        DecisionResult.Outcome outcome = rules.evaluate(request.input());
        long micros = (System.nanoTime() - started) / 1_000;
        DecisionResult result = new DecisionResult(Ulid.next(now), version.ruleSet(), version.version(), now, outcome);
        Set<String> masked = rules.inputs().stream().filter(InputClause::masked).map(InputClause::name)
                .collect(Collectors.toSet());
        ArrayNode matched = EngineJson.NODES.arrayNode();
        result.matchedRuleIds().forEach(matched::add);
        String errorCode = outcome instanceof DecisionResult.Failed failed ? failed.code() : null;
        log.append(new DecisionRecord(tenant, result.decisionId(), now, version.ruleSet(), version.version(),
                version.hitPolicy(), errorCode == null ? "OK" : errorCode, errorCode,
                EngineValues.write(EngineValues.inputs(request.input(), masked)),
                errorCode == null ? EngineValues.write(EngineValues.decision(result).get("outputs")) : null,
                EngineValues.write(matched), caller, request.correlationId(), now, micros));
        return result;
    }

    private CompiledRuleSet compiled(StoredVersion version) {
        String key = version.tenantId() + "/" + version.id();
        CompiledRuleSet cached = compiled.get(key);
        if (cached != null) {
            return cached;
        }
        if (compiled.size() >= context.settings().cacheMaxEntries()) {
            compiled.clear();
        }
        CompiledRuleSet fresh = CompiledRuleSet.compile(RuleSetReader.readCanonical(version.canonicalJson()));
        compiled.put(key, fresh);
        return fresh;
    }
}
