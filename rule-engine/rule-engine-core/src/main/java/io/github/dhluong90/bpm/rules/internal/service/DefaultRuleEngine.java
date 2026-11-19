package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.api.ClockNotAllowedException;
import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionNotFoundException;
import io.github.dhluong90.bpm.rules.api.DecisionRequest;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.InvalidStateException;
import io.github.dhluong90.bpm.rules.api.NoEffectiveVersionException;
import io.github.dhluong90.bpm.rules.api.ReplayNotPossibleException;
import io.github.dhluong90.bpm.rules.api.RuleEngine;
import io.github.dhluong90.bpm.rules.api.RuleSetNotFoundException;
import io.github.dhluong90.bpm.rules.api.VersionNotFoundException;
import io.github.dhluong90.bpm.rules.internal.model.InputClause;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

/**
 * Evaluates the version in force at the decision's clock, or a pinned approved version, and logs one decision row in
 * the caller's transaction (RLE-013); replays a logged decision without writing. Compiled versions are cached by
 * version id: a stored version never changes, so the cache needs no invalidation. Selection is not cached: every
 * evaluation selects in the database, so a newly effective or retired version is seen by every replica at once. The
 * cache size is bounded by {@code cache.max-entries}; a full cache is cleared. Thread-safe.
 */
public final class DefaultRuleEngine implements RuleEngine {

    static final String PERMISSION_EVALUATE = "rules:evaluate";
    static final String PERMISSION_SIMULATE = "rules:simulate";
    static final String PERMISSION_LOG_READ = "rules:log:read";
    /**
     * Evaluating a named version instead of the version in force: a separate permission, held by no role by default,
     * because a pin bypasses the selection of the four-eyes-approved version in force (ADR-0025).
     */
    static final String PERMISSION_PIN = "rules:pin";

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
        String caller = context.callerWith(PERMISSION_EVALUATE);
        if (request.version() != null && !context.holds(PERMISSION_PIN)) {
            throw new io.github.dhluong90.bpm.rules.api.PermissionDeniedException(PERMISSION_PIN);
        }
        if (request.clock() != null && !context.holds(PERMISSION_SIMULATE)) {
            // A chosen instant reaches versions and dates the caller would not get at the host clock (DESIGN 4.2).
            throw new ClockNotAllowedException();
        }
        if (request.correlationId() != null && !CorrelationIds.isValid(request.correlationId())) {
            throw new InputInvalidException("correlationId must be 1 to " + EngineDefaults.CORRELATION_ID_MAX_LENGTH
                    + " characters of A-Z, a-z, 0-9, '.', '_', ':' or '-'");
        }
        InputLimits.check(request.input(), EngineDefaults.DECIMAL_PRECISION, context.settings().maxInputScale(),
                context.settings().maxStringLength());
        // The clock and the pin are part of the request only when set, so a key used before they existed still replays.
        String requestHash = request.clock() == null && request.version() == null
                ? Hashes.sha256Parts("evaluate", caller, request.ruleSet(), ExactForm.of(request.input()))
                : Hashes.sha256Parts("evaluate", caller, request.ruleSet(), ExactForm.of(request.input()),
                        String.valueOf(request.version()), String.valueOf(request.clock()));
        return context.idempotent(tenant, "evaluate", request.idempotencyKey(), requestHash, 200,
                () -> doEvaluate(tenant, caller, request),
                result -> EngineValues.write(EngineValues.decision(result)),
                json -> typed(tenant, EngineValues.decisionFromJson(json)));
    }

    /**
     * Gives an idempotent replay the output types of a fresh evaluation. The stored JSON writes a {@code date} or
     * {@code datetime} output as text, as the REST body does, so the declared types of the logged version turn it
     * back into a {@code LocalDate} or an {@code Instant}; every other value reads back as it was.
     */
    private DecisionResult typed(String tenant, DecisionResult stored) {
        Map<String, Object> outputs = stored.outputs();
        boolean text = outputs.values().stream().anyMatch(v -> v instanceof String || v instanceof List<?>);
        if (!text || stored.outcome() instanceof DecisionResult.Failed) {
            return stored;
        }
        StoredVersion version = store.findVersion(tenant, stored.ruleSet(), stored.version(), false).orElse(null);
        if (version == null) {
            return stored;
        }
        CompiledRuleSet rules;
        try {
            rules = compiled(version);
        } catch (IntegrityFailure failure) {
            return stored;
        }
        Map<String, Object> typed = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> output : outputs.entrySet()) {
            io.github.dhluong90.bpm.rules.internal.model.ValueType.Kind kind = rules.outputs().stream()
                    .filter(clause -> clause.name().equals(output.getKey())).findFirst()
                    .map(clause -> clause.type().kind()).orElse(null);
            typed.put(output.getKey(), typedValue(output.getValue(), kind));
        }
        DecisionResult.Outcome outcome = stored.outcome() instanceof DecisionResult.Matched matched
                ? new DecisionResult.Matched(matched.ruleIds(), typed)
                : new DecisionResult.NoMatch(typed);
        return new DecisionResult(stored.decisionId(), stored.ruleSet(), stored.version(), stored.clock(),
                stored.evaluatedAt(), outcome);
    }

    private static Object typedValue(Object value, io.github.dhluong90.bpm.rules.internal.model.ValueType.Kind kind) {
        if (value instanceof List<?> list) {
            List<Object> elements = new ArrayList<>(list.size());
            list.forEach(element -> elements.add(typedValue(element, kind)));
            return java.util.Collections.unmodifiableList(elements);
        }
        if (value instanceof String text && kind == io.github.dhluong90.bpm.rules.internal.model.ValueType.Kind.DATE) {
            return java.time.LocalDate.parse(text);
        }
        if (value instanceof String text && kind == io.github.dhluong90.bpm.rules.internal.model.ValueType.Kind.DATETIME) {
            return Instant.parse(text);
        }
        return value;
    }

    private DecisionResult doEvaluate(String tenant, String caller, DecisionRequest request) {
        Instant now = context.clock().instant();
        Instant at = request.clock() == null ? now : request.clock();
        if (!io.github.dhluong90.bpm.rules.internal.read.RuleSetReader.isRuleSetName(request.ruleSet())) {
            // Not a possible name (a NUL byte, upper case): not found, without a query the driver would refuse.
            throw new RuleSetNotFoundException("(invalid name)");
        }
        StoredVersion version = request.version() == null ? effective(tenant, request.ruleSet(), at)
                : pinned(tenant, request.ruleSet(), request.version());
        CompiledRuleSet rules;
        try {
            rules = compiled(version);
        } catch (IntegrityFailure failure) {
            return refuseTampered(tenant, caller, request, version, now, at, failure.getMessage());
        }
        if (context.settings().rejectUndeclaredInputs()) {
            rules.requireOnlyDeclared(request.input());
        }
        long started = System.nanoTime();
        DecisionResult.Outcome outcome = rules.evaluate(request.input(), at);
        long micros = (System.nanoTime() - started) / 1_000;
        DecisionResult result = new DecisionResult(Ulid.next(now), version.ruleSet(), version.version(), at, now,
                outcome);
        ArrayNode matched = EngineJson.NODES.arrayNode();
        result.matchedRuleIds().forEach(matched::add);
        String errorCode = outcome instanceof DecisionResult.Failed failed ? failed.code() : null;
        // created_at is when it ran (the partition key); eval_clock is the instant it reasoned about, used by replay.
        log.append(new DecisionRecord(tenant, result.decisionId(), now, version.ruleSet(), version.version(),
                version.hitPolicy(), errorCode == null ? "OK" : errorCode, errorCode,
                EngineValues.write(EngineValues.loggedInputs(request.input(), rules.inputs())),
                errorCode == null ? EngineValues.write(EngineValues.decision(result).get("outputs")) : null,
                EngineValues.write(matched), caller, request.correlationId(), at, micros));
        return result;
    }

    /** The version in force at an instant (DESIGN 3.3.1); not found or no effective version otherwise. */
    private StoredVersion effective(String tenant, String ruleSet, Instant at) {
        return store.findEffective(tenant, ruleSet, at).orElseThrow(() ->
                store.findRuleSetId(tenant, ruleSet).isPresent()
                        ? new NoEffectiveVersionException(ruleSet)
                        : new RuleSetNotFoundException(ruleSet));
    }

    /**
     * A pinned version, which must have been approved: an unapproved rule never enters the decision log; a draft is
     * simulated instead, which writes no row (RLE-015, RLE-016).
     */
    private StoredVersion pinned(String tenant, String ruleSet, int number) {
        StoredVersion version = store.findVersion(tenant, ruleSet, number, false).orElseThrow(() ->
                store.findRuleSetId(tenant, ruleSet).isPresent()
                        ? new VersionNotFoundException(ruleSet, number)
                        : new RuleSetNotFoundException(ruleSet));
        if (version.approvedAt() == null) {
            throw new InvalidStateException(ruleSet, number, version.status(), "evaluate (simulate a draft instead)");
        }
        if (version.status() == io.github.dhluong90.bpm.rules.api.VersionStatus.RETIRED
                && !context.settings().pinRetired()) {
            // A rule taken out of service must not produce new logged decisions through the pin; replay still reads it.
            throw new InvalidStateException(ruleSet, number, version.status(),
                    "evaluate (pinning a retired version is off: horus.bpm.rules.evaluation.pin-retired)");
        }
        return version;
    }

    @Override
    public DecisionResult replay(String decisionId) {
        String tenant = context.tenant();
        context.callerWith(PERMISSION_LOG_READ);
        if (!Ulid.isValid(decisionId)) {
            // Not a possible id: not found, before any query.
            throw new DecisionNotFoundException(String.valueOf(decisionId));
        }
        return context.withoutDooming(() -> {
            DecisionRecord logged = log.find(tenant, decisionId)
                    .orElseThrow(() -> new DecisionNotFoundException(decisionId));
            // By number, never selected: a version superseded or retired since still replays (DESIGN 3.3.1).
            StoredVersion version = store.findVersion(tenant, logged.ruleSet(), logged.version(), false)
                    .orElseThrow(() -> new VersionNotFoundException(logged.ruleSet(), logged.version()));
            DecisionResult.Outcome outcome;
            try {
                CompiledRuleSet rules = compiled(version);
                DecisionInput input = loggedInput(decisionId, logged, rules.inputs());
                outcome = rules.evaluate(input, logged.evalClock());
            } catch (IntegrityFailure failure) {
                outcome = new DecisionResult.Failed("EVALUATION_ERROR", failure.getMessage());
            }
            return new DecisionResult(logged.id(), version.ruleSet(), version.version(), logged.evalClock(),
                    logged.createdAt(), outcome);
        });
    }

    /**
     * Reads the inputs a decision logged. A masked input with a value was stored only as {@code masked}, so the
     * decision cannot be evaluated again; a masked null was stored as null and replays.
     */
    private static DecisionInput loggedInput(String decisionId, DecisionRecord logged, List<InputClause> declared) {
        JsonNode inputs = EngineValues.read(logged.maskedInputsJson());
        List<String> masked = new ArrayList<>();
        for (InputClause clause : declared) {
            JsonNode value = inputs.get(clause.name());
            if (clause.masked() && value != null && !value.isNull()) {
                masked.add(clause.name());
            }
        }
        if (!masked.isEmpty()) {
            throw new ReplayNotPossibleException(decisionId, masked);
        }
        return EngineValues.inputsFromJson(inputs);
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
        // What runs is what was approved: the stored canonical JSON must still hash to canonical_hash (ADR-0026).
        String rehashed = Hashes.sha256(CanonicalJson.write(EngineJson.JSON.readTree(version.canonicalJson())));
        if (!rehashed.equals(version.canonicalHash())) {
            throw new IntegrityFailure("Integrity check failed: the stored canonical document of " + version.ruleSet()
                    + " v" + version.version() + " does not match its canonical_hash; the version is not served");
        }
        CompiledRuleSet fresh = CompiledRuleSet.compile(RuleSetReader.readCanonical(version.canonicalJson()),
                context.settings().maxOutputBytes(), context.settings().maxStringLength());
        compiled.put(key, fresh);
        return fresh;
    }

    /**
     * Records the refused evaluation of a tampered version as a decision with {@code EVALUATION_ERROR} (the audit
     * entry) and raises an alert in the log. The inputs are not logged: the version's masking cannot be trusted.
     */
    private DecisionResult refuseTampered(String tenant, String caller, DecisionRequest request, StoredVersion version,
            Instant now, Instant at, String detail) {
        System.getLogger(DefaultRuleEngine.class.getName()).log(System.Logger.Level.ERROR,
                "ALERT rule set integrity: tenant " + tenant + ", " + detail);
        DecisionResult result = new DecisionResult(Ulid.next(now), version.ruleSet(), version.version(), at, now,
                new DecisionResult.Failed("EVALUATION_ERROR", detail));
        log.append(new DecisionRecord(tenant, result.decisionId(), now, version.ruleSet(), version.version(),
                version.hitPolicy(), "EVALUATION_ERROR", "EVALUATION_ERROR", "{}", null, "[]", caller,
                request.correlationId(), at, 0));
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
