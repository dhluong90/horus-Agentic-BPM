package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionRequest;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.NoEffectiveVersionException;
import io.github.dhluong90.bpm.rules.api.RuleEngine;
import io.github.dhluong90.bpm.rules.api.RuleSetNotFoundException;
import io.github.dhluong90.bpm.rules.api.SimulationResult;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.api.VersionNotFoundException;
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
import tools.jackson.databind.node.ArrayNode;

/**
 * Evaluates the effective version and logs one decision row in the caller's transaction (RLE-013), and simulates a
 * stored version over sample inputs without logging anything (RLE-016). Compiled versions are cached by version id
 * and canonical hash, so a cached entry is never served for other content and the cache needs no invalidation. Its size is bounded by {@code cache.max-entries}; a full cache is cleared. Thread-safe.
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

    private DecisionResult doEvaluate(String tenant, String caller, DecisionRequest request) {
        Instant now = context.clock().instant();
        if (!io.github.dhluong90.bpm.rules.internal.read.RuleSetReader.isRuleSetName(request.ruleSet())) {
            // Not a possible name (a NUL byte, upper case): not found, without a query the driver would refuse.
            throw new RuleSetNotFoundException("(invalid name)");
        }
        StoredVersion version = store.findEffective(tenant, request.ruleSet(), now).orElseThrow(() ->
                store.findRuleSetId(tenant, request.ruleSet()).isPresent()
                        ? new NoEffectiveVersionException(request.ruleSet())
                        : new RuleSetNotFoundException(request.ruleSet()));
        CompiledRuleSet rules;
        try {
            rules = compiled(version);
        } catch (IntegrityFailure failure) {
            return refuseTampered(tenant, caller, request, version, now, failure.getMessage());
        }
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
                context.settings().maxOutputBytes(), context.settings().maxStringLength());
        compiled.put(key, fresh);
        return fresh;
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
