package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.api.ClockNotAllowedException;
import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionNotFoundException;
import io.github.dhluong90.bpm.rules.api.DecisionRequest;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.InvalidStateException;
import io.github.dhluong90.bpm.rules.api.MaskingKey;
import io.github.dhluong90.bpm.rules.api.NoEffectiveVersionException;
import io.github.dhluong90.bpm.rules.api.ReplayNotPossibleException;
import io.github.dhluong90.bpm.rules.api.RuleEngine;
import io.github.dhluong90.bpm.rules.api.RuleSetNotFoundException;
import io.github.dhluong90.bpm.rules.api.SimulationResult;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

/**
 * Evaluates the version in force at the decision's clock, or a pinned approved version, and logs one decision row in
 * the caller's transaction (RLE-013); replays a logged decision without writing; simulates a stored version over
 * sample inputs without logging anything (RLE-016). Compiled versions are cached by version id and canonical hash, so
 * a cached entry is never served for other content and that map needs no invalidation; its size is bounded by
 * {@code cache.max-entries} and a full map is cleared. The selection of the version in force is served from the
 * {@link CompiledCache} while the evaluation instant lies inside the entry's window and its epoch holds, so a cached
 * evaluation reads nothing and writes one row; a miss reads the version, its epoch and its window in one statement
 * (ADR-0009). Approve and retire move the epoch, so a newly effective or retired version reaches every replica
 * through the notification or, at the latest, the epoch check. A pinned evaluation, a replay and a simulation never
 * use the selection cache. Thread-safe.
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

    /** {@code error_code} of a decision refused because its tenant has no masking key. */
    static final String MASKING_KEY_MISSING = "MASKING_KEY_MISSING";

    /** {@code error_code} of a decision refused because an output reads an input that is masked for the tenant. */
    static final String MASKED_INPUT_IN_OUTPUT = "MASKED_INPUT_IN_OUTPUT";

    private final EngineContext context;
    private final RuleStore store;
    private final DecisionLog log;
    private final Map<String, CompiledRuleSet> compiled = new ConcurrentHashMap<>();
    private final CompiledCache cache;

    /**
     * Creates the engine with a selection cache of its own.
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
        // A pinned evaluation is its own permission (DESIGN 4.2): rules:pin, never rules:evaluate alone.
        String caller = context.callerWith(request.version() == null ? PERMISSION_EVALUATE : PERMISSION_PIN);
        if (request.clock() != null && !context.holds(PERMISSION_SIMULATE)) {
            // A chosen instant reaches versions and dates the caller would not get at the host clock (DESIGN 4.2).
            throw new ClockNotAllowedException();
        }
        return evaluateAs(tenant, caller, request);
    }

    /**
     * Evaluates in an explicit tenant as an explicit caller; only {@link EngineScopedRuleEngine} calls it, with a
     * registered engine identity. The pin and clock checks above belong to a caller; the engine-scoped entry point
     * refuses both.
     */
    DecisionResult evaluateAs(String tenant, String caller, DecisionRequest request) {
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

    @Override
    public SimulationResult simulate(String ruleSet, int version, List<DecisionInput> samples) {
        String tenant = context.tenant();
        // Permission first: a caller without rules:simulate never makes the engine read or evaluate anything.
        context.callerWith("rules:simulate");
        int limit = context.settings().simulationMaxInputs();
        if (samples == null || samples.isEmpty() || samples.size() > limit) {
            throw new InputInvalidException("samples must hold 1 to " + limit + " input sets (simulation.max-inputs)");
        }
        for (int i = 0; i < samples.size(); i++) {
            DecisionInput sample = samples.get(i);
            if (sample == null) {
                throw new InputInvalidException("samples[" + i + "] must be an input set");
            }
            int index = i;
            atSample(index, () -> InputLimits.check(sample, EngineDefaults.DECIMAL_PRECISION,
                    context.settings().maxInputScale(), context.settings().maxStringLength()));
        }
        return context.withoutDooming(() -> doSimulate(tenant, ruleSet, version, samples));
    }

    private SimulationResult doSimulate(String tenant, String ruleSet, int number, List<DecisionInput> samples) {
        Instant now = context.clock().instant();
        if (!RuleSetReader.isRuleSetName(ruleSet)) {
            throw new VersionNotFoundException("(invalid name)", number);
        }
        // Any tenant but the caller's has no such version: the same answer whether or not the rule set exists.
        StoredVersion simulated = store.findVersion(tenant, ruleSet, number, false)
                .orElseThrow(() -> new VersionNotFoundException(ruleSet, number));
        CompiledRuleSet draftRules = validated(simulated);
        StoredVersion effective = store.findEffective(tenant, ruleSet, now).orElse(null);
        CompiledRuleSet baselineRules = effective == null ? null : validated(effective);
        Set<String> baselineInputs = new HashSet<>();
        if (baselineRules != null) {
            baselineRules.inputs().forEach(clause -> baselineInputs.add(clause.name()));
        }
        List<SimulationResult.Sample> results = new ArrayList<>(samples.size());
        for (int i = 0; i < samples.size(); i++) {
            DecisionInput sample = samples.get(i);
            if (context.settings().rejectUndeclaredInputs()) {
                atSample(i, () -> draftRules.requireOnlyDeclared(sample));
            }
            DecisionResult.Outcome draft = draftRules.evaluate(sample);
            DecisionResult.Outcome baseline = baselineRules == null ? null
                    : baselineRules.evaluate(only(sample, baselineInputs));
            results.add(new SimulationResult.Sample(i, draft, baseline, baseline == null || changed(draft, baseline)));
        }
        return new SimulationResult(simulated.ruleSet(), simulated.version(), simulated.status(),
                effective == null ? null : effective.version(), now, results);
    }

    /** Validation runs first: the stored version must still match its hash and compile, else VALIDATION_FAILED. */
    private CompiledRuleSet validated(StoredVersion version) {
        try {
            return compiled(version);
        } catch (IntegrityFailure failure) {
            System.getLogger(DefaultRuleEngine.class.getName()).log(System.Logger.Level.ERROR,
                    "ALERT rule set integrity: tenant " + version.tenantId() + ", " + failure.getMessage());
            throw new ValidationFailedException(failure.getMessage());
        }
    }

    /** Runs a check of one sample, naming the sample in the refusal. */
    private static void atSample(int index, Runnable check) {
        try {
            check.run();
        } catch (InputInvalidException e) {
            throw new InputInvalidException("samples[" + index + "]: " + e.getMessage());
        }
    }

    private static DecisionInput only(DecisionInput sample, Set<String> declared) {
        Map<String, Object> kept = new LinkedHashMap<>();
        sample.values().forEach((name, value) -> {
            if (declared.contains(name)) {
                kept.put(name, value);
            }
        });
        return DecisionInput.of(kept);
    }

    /**
     * Compares what a customer gets: the outcome kind, the failure code and the outputs by exact value, so
     * {@code 30000} and {@code 30000.00} are equal. Matched rule ids are not compared.
     */
    static boolean changed(DecisionResult.Outcome draft, DecisionResult.Outcome baseline) {
        return switch (draft) {
            case DecisionResult.Matched d when baseline instanceof DecisionResult.Matched b ->
                    !sameValues(d.outputs(), b.outputs());
            case DecisionResult.NoMatch d when baseline instanceof DecisionResult.NoMatch b ->
                    !sameValues(d.outputs(), b.outputs());
            case DecisionResult.Failed d when baseline instanceof DecisionResult.Failed b -> !d.code().equals(b.code());
            default -> true;
        };
    }

    private static boolean sameValues(Map<String, Object> a, Map<String, Object> b) {
        return ExactForm.of(DecisionInput.of(a)).equals(ExactForm.of(DecisionInput.of(b)));
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
                stored.evaluatedAt(), stored.pinned(), outcome);
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
        StoredVersion version;
        CompiledRuleSet rules;
        if (request.version() == null) {
            CompiledCache.Entry entry = cache.get(tenant, request.ruleSet(), at);
            if (entry == null) {
                RuleStore.EffectiveVersion effective = effective(tenant, request.ruleSet(), at);
                try {
                    entry = new CompiledCache.Entry(effective.version(), compiled(effective.version()),
                            effective.cacheEpoch(), effective.validFrom(), effective.nextEffectiveFrom());
                } catch (IntegrityFailure failure) {
                    return refuseTampered(tenant, caller, request, effective.version(), now, at, failure);
                }
                cache.put(entry);
            }
            version = entry.version();
            rules = entry.compiled();
        } else {
            version = pinned(tenant, request.ruleSet(), request.version());
            try {
                rules = compiled(version);
            } catch (IntegrityFailure failure) {
                return refuseTampered(tenant, caller, request, version, now, at, failure);
            }
        }
        if (context.settings().rejectUndeclaredInputs()) {
            rules.requireOnlyDeclared(request.input());
        }
        boolean maskByDefault = context.masking().maskByDefault(tenant);
        MaskingKey key = context.masking().hmacKey(tenant).orElse(null);
        if (key == null && rules.inputs().stream().anyMatch(clause -> clause.masked(maskByDefault))) {
            // Fail closed: without a key the inputs could only be logged in plain text (RLE-012, TR-SEC-10).
            System.getLogger(DefaultRuleEngine.class.getName()).log(System.Logger.Level.ERROR,
                    "Rule set " + version.ruleSet() + " of tenant " + tenant + " masks an input, but the tenant has no"
                            + " masking key (" + EngineDefaults.MASKING_KEY_PROPERTY + "); the decision is refused");
            return refuse(tenant, caller, request, version, now, at, MASKING_KEY_MISSING, "No masking key is configured"
                    + " for this tenant, so the inputs of " + version.ruleSet() + " cannot be logged; set "
                    + EngineDefaults.MASKING_KEY_PROPERTY);
        }
        String echoed = rules.inputs().stream()
                .filter(clause -> clause.masked(maskByDefault) && rules.inputsInOutputs().contains(clause.name()))
                .map(clause -> clause.name()).findFirst().orElse(null);
        if (echoed != null) {
            // Import refuses an output that reads a declared mask: true input; this covers the tenant's mask default.
            return refuse(tenant, caller, request, version, now, at, MASKED_INPUT_IN_OUTPUT, "Input " + echoed + " of "
                    + version.ruleSet() + " is masked for this tenant, but an output reads it; the decision is refused"
                    + " so the plain value is not logged");
        }
        long started = System.nanoTime();
        CompiledRuleSet.Evaluation evaluation = rules.evaluateWithReason(request.input(), at);
        DecisionResult.Outcome outcome = evaluation.outcome();
        long micros = (System.nanoTime() - started) / 1_000;
        boolean pinned = request.version() != null;
        DecisionResult result = new DecisionResult(Ulid.next(now), version.ruleSet(), version.version(), at, now,
                pinned, outcome);
        ArrayNode matched = EngineJson.NODES.arrayNode();
        result.matchedRuleIds().forEach(matched::add);
        String errorCode = outcome instanceof DecisionResult.Failed failed ? failed.code() : null;
        // The row's error_code is the precise reason when there is one, for example REGEX_TIMEOUT (RLE-007).
        String reason = evaluation.reason() != null ? evaluation.reason() : errorCode;
        // created_at is when it ran (the partition key); eval_clock is the instant it reasoned about, used by replay.
        log.append(new DecisionRecord(tenant, result.decisionId(), now, version.ruleSet(), version.version(),
                version.hitPolicy(), errorCode == null ? "OK" : errorCode, reason,
                EngineValues.write(EngineValues.loggedInputs(request.input(), rules.inputs(), maskByDefault, key)),
                errorCode == null ? EngineValues.write(EngineValues.decision(result).get("outputs")) : null,
                EngineValues.write(matched), caller, request.correlationId(), at, micros, pinned));
        return result;
    }

    /** Logs a decision on a version whose stored hash no longer matches its content, and raises the alert. */
    private DecisionResult refuseTampered(String tenant, String caller, DecisionRequest request, StoredVersion version,
            Instant now, Instant at, IntegrityFailure failure) {
        System.getLogger(DefaultRuleEngine.class.getName()).log(System.Logger.Level.ERROR,
                "ALERT rule set integrity: tenant " + tenant + ", " + failure.getMessage());
        return refuse(tenant, caller, request, version, now, at, "EVALUATION_ERROR", failure.getMessage());
    }

    /**
     * The version in force at an instant (DESIGN 3.3.1), with the window around the instant over which that selection
     * holds and the rule set's epoch; not found or no effective version otherwise.
     */
    private RuleStore.EffectiveVersion effective(String tenant, String ruleSet, Instant at) {
        return store.findEffectiveForCache(tenant, ruleSet, at).orElseThrow(() ->
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
                    logged.createdAt(), logged.pinned(), outcome);
        });
    }

    /**
     * Reads the inputs a decision logged. A masked input with a value was stored only as its fingerprint, so the
     * decision cannot be evaluated again; a masked null was stored as null and replays. An input masked by the
     * tenant's default, which the version does not declare, is recognised by its fingerprint form.
     */
    private static DecisionInput loggedInput(String decisionId, DecisionRecord logged, List<InputClause> declared) {
        JsonNode inputs = EngineValues.read(logged.maskedInputsJson());
        List<String> masked = new ArrayList<>();
        for (InputClause clause : declared) {
            JsonNode value = inputs.get(clause.name());
            boolean fingerprint = value != null && value.isString() && MaskingKey.isFingerprint(value.stringValue());
            if ((clause.masked() && value != null && !value.isNull()) || fingerprint) {
                masked.add(clause.name());
            }
        }
        if (!masked.isEmpty()) {
            throw new ReplayNotPossibleException(decisionId, masked);
        }
        return EngineValues.inputsFromJson(inputs);
    }

    private CompiledRuleSet compiled(StoredVersion version) {
        String key = version.tenantId() + "/" + version.id() + "/" + version.canonicalHash();
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
                context.settings().maxOutputBytes(), context.settings().maxStringLength(),
                context.settings().regexLimits());
        compiled.put(key, fresh);
        return fresh;
    }

    /**
     * Records a refused evaluation as a decision with outcome {@code EVALUATION_ERROR} (the audit entry) and no logged
     * input: for a tampered version its masking cannot be trusted, and without a masking key the inputs could only
     * be logged in plain text. The rule set is not evaluated.
     */
    private DecisionResult refuse(String tenant, String caller, DecisionRequest request, StoredVersion version,
            Instant now, Instant at, String errorCode, String detail) {
        DecisionResult result = new DecisionResult(Ulid.next(now), version.ruleSet(), version.version(), at, now,
                request.version() != null, new DecisionResult.Failed("EVALUATION_ERROR", detail));
        log.append(new DecisionRecord(tenant, result.decisionId(), now, version.ruleSet(), version.version(),
                version.hitPolicy(), "EVALUATION_ERROR", errorCode, "{}", null, "[]", caller,
                request.correlationId(), at, 0, result.pinned()));
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
