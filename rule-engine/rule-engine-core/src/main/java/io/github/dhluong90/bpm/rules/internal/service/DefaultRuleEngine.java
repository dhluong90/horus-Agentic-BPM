package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionRequest;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.NoEffectiveVersionException;
import io.github.dhluong90.bpm.rules.api.RuleEngine;
import io.github.dhluong90.bpm.rules.api.RuleSetNotFoundException;
import io.github.dhluong90.bpm.rules.internal.eval.CompiledRuleSet;
import io.github.dhluong90.bpm.rules.internal.json.CanonicalJson;
import io.github.dhluong90.bpm.rules.internal.json.CorrelationIds;
import io.github.dhluong90.bpm.rules.internal.json.ExactForm;
import io.github.dhluong90.bpm.rules.internal.json.InputLimits;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import io.github.dhluong90.bpm.rules.internal.json.EngineValues;
import io.github.dhluong90.bpm.rules.internal.json.Hashes;
import io.github.dhluong90.bpm.rules.internal.json.Ulid;
import io.github.dhluong90.bpm.rules.internal.read.RuleSetReader;
import io.github.dhluong90.bpm.rules.internal.store.DecisionLog;
import io.github.dhluong90.bpm.rules.internal.store.DecisionRecord;
import io.github.dhluong90.bpm.rules.internal.store.RuleStore;
import io.github.dhluong90.bpm.rules.internal.store.StoredVersion;
import java.time.Instant;
import java.util.Objects;
import tools.jackson.databind.node.ArrayNode;

/**
 * Evaluates the effective version and logs one decision row in the caller's transaction (RLE-013).
 * The effective version of a rule set is served from the {@link CompiledCache} while its window and epoch hold,
 * so a cached evaluation reads nothing and writes one row; a miss reads the version, its epoch and the next
 * hand-over instant in one statement (ADR-0009). Thread-safe.
 */
public final class DefaultRuleEngine implements RuleEngine {

    private final EngineContext context;
    private final RuleStore store;
    private final DecisionLog log;
    private final CompiledCache cache;

    /**
     * Creates the engine with a cache of its own.
     *
     * @param context shared engine context
     * @param store rule set storage
     * @param log decision log
     */
    public DefaultRuleEngine(EngineContext context, RuleStore store, DecisionLog log) {
        this(context, store, log, new CompiledCache(context.settings().cacheMaxEntries(), store, context.transactions()));
    }

    DefaultRuleEngine(EngineContext context, RuleStore store, DecisionLog log, CompiledCache cache) {
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.log = Objects.requireNonNull(log, "log must not be null");
        this.cache = Objects.requireNonNull(cache, "cache must not be null");
    }

    @Override
    public DecisionResult evaluate(String ruleSet, DecisionInput input) {
        return evaluate(DecisionRequest.of(ruleSet, input));
    }

    @Override
    public DecisionResult evaluate(DecisionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String tenant = context.tenant();
        String caller = context.callerWith("rules:evaluate");
        if (request.correlationId() != null && !CorrelationIds.isValid(request.correlationId())) {
            throw new InputInvalidException("correlationId must be 1 to " + EngineDefaults.CORRELATION_ID_MAX_LENGTH
                    + " characters of A-Z, a-z, 0-9, '.', '_', ':' or '-'");
        }
        InputLimits.check(request.input(), EngineDefaults.DECIMAL_PRECISION, context.settings().maxInputScale(),
                context.settings().maxStringLength());
        String requestHash = Hashes.sha256Parts("evaluate", caller, request.ruleSet(), ExactForm.of(request.input()));
        return context.idempotent(tenant, "evaluate", request.idempotencyKey(), requestHash, 200,
                () -> doEvaluate(tenant, caller, request),
                result -> EngineValues.write(EngineValues.decision(result)),
                EngineValues::decisionFromJson);
    }

    private DecisionResult doEvaluate(String tenant, String caller, DecisionRequest request) {
        Instant now = context.clock().instant();
        if (!io.github.dhluong90.bpm.rules.internal.read.RuleSetReader.isRuleSetName(request.ruleSet())) {
            // Not a possible name (a NUL byte, upper case): not found, without a query the driver would refuse.
            throw new RuleSetNotFoundException("(invalid name)");
        }
        CompiledCache.Entry entry = cache.get(tenant, request.ruleSet(), now);
        if (entry == null) {
            RuleStore.EffectiveVersion effective = store.findEffectiveForCache(tenant, request.ruleSet(), now)
                    .orElseThrow(() -> store.findRuleSetId(tenant, request.ruleSet()).isPresent()
                            ? new NoEffectiveVersionException(request.ruleSet())
                            : new RuleSetNotFoundException(request.ruleSet()));
            try {
                entry = new CompiledCache.Entry(effective.version(), compile(effective.version()),
                        effective.cacheEpoch(), effective.nextEffectiveFrom());
            } catch (IntegrityFailure failure) {
                return refuseTampered(tenant, caller, request, effective.version(), now, failure.getMessage());
            }
            cache.put(entry);
        }
        StoredVersion version = entry.version();
        CompiledRuleSet rules = entry.compiled();
        if (context.settings().rejectUndeclaredInputs()) {
            rules.requireOnlyDeclared(request.input());
        }
        long started = System.nanoTime();
        DecisionResult.Outcome outcome = rules.evaluate(request.input());
        long micros = (System.nanoTime() - started) / 1_000;
        DecisionResult result = new DecisionResult(Ulid.next(now), version.ruleSet(), version.version(), now, outcome);
        ArrayNode matched = EngineJson.NODES.arrayNode();
        result.matchedRuleIds().forEach(matched::add);
        String errorCode = outcome instanceof DecisionResult.Failed failed ? failed.code() : null;
        log.append(new DecisionRecord(tenant, result.decisionId(), now, version.ruleSet(), version.version(),
                version.hitPolicy(), errorCode == null ? "OK" : errorCode, errorCode,
                EngineValues.write(EngineValues.loggedInputs(request.input(), rules.inputs())),
                errorCode == null ? EngineValues.write(EngineValues.decision(result).get("outputs")) : null,
                EngineValues.write(matched), caller, request.correlationId(), now, micros));
        return result;
    }

    private CompiledRuleSet compile(StoredVersion version) {
        // What runs is what was approved: the stored canonical JSON must still hash to canonical_hash (ADR-0026).
        String rehashed = Hashes.sha256(CanonicalJson.write(EngineJson.JSON.readTree(version.canonicalJson())));
        if (!rehashed.equals(version.canonicalHash())) {
            throw new IntegrityFailure("Integrity check failed: the stored canonical document of " + version.ruleSet()
                    + " v" + version.version() + " does not match its canonical_hash; the version is not served");
        }
        return CompiledRuleSet.compile(RuleSetReader.readCanonical(version.canonicalJson()),
                context.settings().maxOutputBytes(), context.settings().maxStringLength());
    }

    /**
     * Records the refused evaluation of a tampered version as a decision with {@code EVALUATION_ERROR} (the audit
     * entry) and raises an alert in the log. The inputs are not logged: the version's masking cannot be trusted.
     */
    private DecisionResult refuseTampered(String tenant, String caller, DecisionRequest request, StoredVersion version,
            Instant now, String detail) {
        System.getLogger(DefaultRuleEngine.class.getName()).log(System.Logger.Level.ERROR,
                "ALERT rule set integrity: tenant " + tenant + ", " + detail);
        DecisionResult result = new DecisionResult(Ulid.next(now), version.ruleSet(), version.version(), now,
                new DecisionResult.Failed("EVALUATION_ERROR", detail));
        log.append(new DecisionRecord(tenant, result.decisionId(), now, version.ruleSet(), version.version(),
                version.hitPolicy(), "EVALUATION_ERROR", "EVALUATION_ERROR", "{}", null, "[]", caller,
                request.correlationId(), now, 0));
        return result;
    }

    /** The stored canonical JSON of a version no longer hashes to its canonical_hash. */
    private static final class IntegrityFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        IntegrityFailure(String message) {
            super(message);
        }
    }
}
