package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionRequest;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.MaskingKey;
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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.node.ArrayNode;

/**
 * Evaluates the effective version and logs one decision row in the caller's transaction (RLE-013).
 * Compiled versions are cached by version id: a stored version never changes, so the cache needs no
 * invalidation. Its size is bounded by {@code cache.max-entries}; a full cache is cleared. Thread-safe.
 */
public final class DefaultRuleEngine implements RuleEngine {

    /** {@code error_code} of a decision refused because its tenant has no masking key. */
    static final String MASKING_KEY_MISSING = "MASKING_KEY_MISSING";

    /** {@code error_code} of a decision refused because an output reads an input that is masked for the tenant. */
    static final String MASKED_INPUT_IN_OUTPUT = "MASKED_INPUT_IN_OUTPUT";

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
            System.getLogger(DefaultRuleEngine.class.getName()).log(System.Logger.Level.ERROR,
                    "ALERT rule set integrity: tenant " + tenant + ", " + failure.getMessage());
            return refuse(tenant, caller, request, version, now, "EVALUATION_ERROR", failure.getMessage());
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
            return refuse(tenant, caller, request, version, now, MASKING_KEY_MISSING, "No masking key is configured"
                    + " for this tenant, so the inputs of " + version.ruleSet() + " cannot be logged; set "
                    + EngineDefaults.MASKING_KEY_PROPERTY);
        }
        String echoed = rules.inputs().stream()
                .filter(clause -> clause.masked(maskByDefault) && rules.inputsInOutputs().contains(clause.name()))
                .map(clause -> clause.name()).findFirst().orElse(null);
        if (echoed != null) {
            // Import refuses an output that reads a declared mask: true input; this covers the tenant's mask default.
            return refuse(tenant, caller, request, version, now, MASKED_INPUT_IN_OUTPUT, "Input " + echoed + " of "
                    + version.ruleSet() + " is masked for this tenant, but an output reads it; the decision is refused"
                    + " so the plain value is not logged");
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
                EngineValues.write(EngineValues.loggedInputs(request.input(), rules.inputs(), maskByDefault, key)),
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
     * Records a refused evaluation as a decision with outcome {@code EVALUATION_ERROR} (the audit entry) and no logged
     * input: for a tampered version its masking cannot be trusted, and without a masking key the inputs could only
     * be logged in plain text. The rule set is not evaluated.
     */
    private DecisionResult refuse(String tenant, String caller, DecisionRequest request, StoredVersion version,
            Instant now, String errorCode, String detail) {
        DecisionResult result = new DecisionResult(Ulid.next(now), version.ruleSet(), version.version(), now,
                new DecisionResult.Failed("EVALUATION_ERROR", detail));
        log.append(new DecisionRecord(tenant, result.decisionId(), now, version.ruleSet(), version.version(),
                version.hitPolicy(), "EVALUATION_ERROR", errorCode, "{}", null, "[]", caller,
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
