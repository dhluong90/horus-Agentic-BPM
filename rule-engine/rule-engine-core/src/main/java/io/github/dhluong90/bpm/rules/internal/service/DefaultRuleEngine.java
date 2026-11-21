package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.api.BatchRequest;
import io.github.dhluong90.bpm.rules.api.BatchResult;
import io.github.dhluong90.bpm.rules.api.BatchTooLargeException;
import io.github.dhluong90.bpm.rules.api.ClockNotAllowedException;
import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionNotFoundException;
import io.github.dhluong90.bpm.rules.api.DecisionRequest;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.HistorySimulation;
import io.github.dhluong90.bpm.rules.api.HistorySimulationResult;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.InvalidStateException;
import io.github.dhluong90.bpm.rules.api.MaskingKey;
import io.github.dhluong90.bpm.rules.api.NoEffectiveVersionException;
import io.github.dhluong90.bpm.rules.api.QueryInvalidException;
import io.github.dhluong90.bpm.rules.api.ReplayNotPossibleException;
import io.github.dhluong90.bpm.rules.api.RuleEngine;
import io.github.dhluong90.bpm.rules.api.RuleSetNotFoundException;
import io.github.dhluong90.bpm.rules.api.SimulationResult;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.api.VersionNotFoundException;
import io.github.dhluong90.bpm.rules.internal.model.InputClause;
import io.github.dhluong90.bpm.rules.internal.eval.CompiledRuleSet;
import io.github.dhluong90.bpm.expr.internal.RequestReads;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Evaluates the version in force at the decision's clock, or a pinned approved version, and logs one decision row in
 * the caller's transaction (RLE-013); replays a logged decision without writing; simulates a stored version over
 * sample inputs (RLE-016) or over the logged decisions of a period (RLE-015) without logging anything. Compiled versions are cached by version id and canonical hash, so
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
    private final DecisionQuerySettings querySettings;
    private final EngineMeters meters;
    private final AtomicBoolean grantOpened = new AtomicBoolean();

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
        this(context, store, log, cache, DecisionQuerySettings.defaults(), new EngineMeters());
    }

    DefaultRuleEngine(EngineContext context, RuleStore store, DecisionLog log, CompiledCache cache,
            DecisionQuerySettings querySettings) {
        this(context, store, log, cache, querySettings, new EngineMeters());
    }

    DefaultRuleEngine(EngineContext context, RuleStore store, DecisionLog log, CompiledCache cache,
            EngineMeters meters) {
        this(context, store, log, cache, DecisionQuerySettings.defaults(), meters);
    }

    DefaultRuleEngine(EngineContext context, RuleStore store, DecisionLog log, CompiledCache cache,
            DecisionQuerySettings querySettings, EngineMeters meters) {
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.log = Objects.requireNonNull(log, "log must not be null");
        this.cache = Objects.requireNonNull(cache, "cache must not be null");
        this.querySettings = Objects.requireNonNull(querySettings, "querySettings must not be null");
        this.meters = Objects.requireNonNull(meters, "meters must not be null");
    }

    /** @return true the first time only: at most one grant of the tenant-scoped entry point per engine (#336) */
    boolean markGrantOpened() {
        return grantOpened.compareAndSet(false, true);
    }

    @Override
    public DecisionResult evaluate(String ruleSet, DecisionInput input) {
        return evaluate(DecisionRequest.of(ruleSet, input));
    }

    @Override
    public DecisionResult evaluate(DecisionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return measured(() -> {
            String tenant = context.tenant();
            // A pinned evaluation is its own permission (DESIGN 4.2): rules:pin, never rules:evaluate alone.
            String caller = context.callerWith(request.version() == null ? PERMISSION_EVALUATE : PERMISSION_PIN);
            if (request.clock() != null && !context.holds(PERMISSION_SIMULATE)) {
                // A chosen instant reaches versions and dates the caller would not get at the host clock (DESIGN 4.2).
                throw new ClockNotAllowedException();
            }
            return evaluateUnmeasured(tenant, caller, null, null, request);
        });
    }

    /**
     * Times one evaluation call and records how it ended (DESIGN 4.8): a refusal is recorded with its error code,
     * then thrown on unchanged.
     */
    private DecisionResult measured(java.util.function.Supplier<DecisionResult> call) {
        long started = System.nanoTime();
        DecisionResult result;
        try {
            result = call.get();
        } catch (RuntimeException failure) {
            meters.refused(failure, System.nanoTime() - started);
            throw failure;
        }
        meters.evaluated(result, System.nanoTime() - started);
        return result;
    }

    /**
     * Evaluates in an explicit tenant as an explicit caller; only {@link EngineScopedRuleEngine} calls it, with a
     * registered engine identity. The pin and clock checks above belong to a caller; the engine-scoped entry point
     * refuses both.
     */
    DecisionResult evaluateAs(String tenant, String caller, DecisionRequest request) {
        return evaluateAs(tenant, caller, null, null, request);
    }

    /**
     * Evaluates for an embedded engine (ADR-0027): as {@code caller}, on behalf of {@code onBehalfOf}, at the calling
     * engine's clock {@code engineTime}. Neither the time nor {@code onBehalfOf} is part of the request hash: a retried
     * step comes later on the clock and must replay the logged decision, with its original evaluation time. Timed and
     * recorded as one evaluation call (DESIGN 4.8).
     *
     * @param tenant the tenant
     * @param caller the caller identity written to the log
     * @param onBehalfOf the human the caller acts for, or null
     * @param engineTime the calling engine's clock at the step, or null for this engine's clock
     * @param request the request, with no clock and no pinned version
     * @return the logged result
     */
    DecisionResult evaluateAs(String tenant, String caller, String onBehalfOf, Instant engineTime,
            DecisionRequest request) {
        return measured(() -> evaluateUnmeasured(tenant, caller, onBehalfOf, engineTime, request));
    }

    private DecisionResult evaluateUnmeasured(String tenant, String caller, String onBehalfOf, Instant engineTime,
            DecisionRequest request) {
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
                () -> doEvaluate(tenant, caller, onBehalfOf, engineTime == null ? request : request.withClock(engineTime)),
                result -> EngineValues.write(EngineValues.decision(result)),
                json -> typed(tenant, EngineValues.decisionFromJson(json)));
    }

    @Override
    public BatchResult evaluateBatch(BatchRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String tenant = context.tenant();
        String caller = context.callerWith(PERMISSION_EVALUATE);
        if (request.clock() != null && !context.holds(PERMISSION_SIMULATE)) {
            throw new ClockNotAllowedException();
        }
        // Whole-batch bounds first: nothing is read, evaluated or written for a batch that is refused (RLE-068).
        int limit = context.settings().maxBatch();
        int count = request.items().size();
        if (count == 0) {
            throw new InputInvalidException("items must hold 1 to " + limit + " items (evaluation.max-batch)");
        }
        if (count > limit) {
            throw new BatchTooLargeException(count, limit);
        }
        List<String> parts = new ArrayList<>(count + 5);
        parts.add("evaluate-batch");
        parts.add(caller);
        parts.add(request.ruleSet());
        parts.add(String.valueOf(request.clock()));
        for (BatchRequest.Item item : request.items()) {
            // A correlation id is not part of the idempotent request, as on :evaluate.
            parts.add(item.input() == null ? null : ExactForm.of(item.input()));
        }
        String requestHash = Hashes.sha256Parts(parts.toArray(String[]::new));
        return context.idempotent(tenant, "evaluate-batch", request.idempotencyKey(), requestHash, 200,
                () -> doBatch(tenant, caller, request),
                result -> EngineValues.write(batchJson(result)),
                json -> typedBatch(tenant, batchFromJson(json)));
    }

    private BatchResult doBatch(String tenant, String caller, BatchRequest request) {
        Instant now = context.clock().instant();
        Instant at = request.clock() == null ? now : request.clock();
        // One version for the whole batch, selected once at the request clock (RLE-067).
        Selected selected = select(tenant, request.ruleSet(), null, at);
        // One regex read budget for every decision of the batch (evaluation.regex-max-reads-per-request, RLE-007):
        // once it stops a match, no later item is evaluated, and the same batch stops at the same item on replay.
        RequestReads reads = context.settings().regexLimits().newRequest();
        List<BatchResult.Item> items = new ArrayList<>(request.items().size());
        int stoppedAt = -1;
        for (int i = 0; i < request.items().size(); i++) {
            BatchRequest.Item item = request.items().get(i);
            if (reads.spent()) {
                DecisionResult.Failed notRun = notRun(reads);
                items.add(BatchResult.Item.refused(i, notRun.code(), notRun.detail()));
                continue;
            }
            try {
                if (item.correlationId() != null && !CorrelationIds.isValid(item.correlationId())) {
                    throw new InputInvalidException("correlationId must be 1 to "
                            + EngineDefaults.CORRELATION_ID_MAX_LENGTH
                            + " characters of A-Z, a-z, 0-9, '.', '_', ':' or '-'");
                }
                InputLimits.check(item.input(), EngineDefaults.DECIMAL_PRECISION, context.settings().maxInputScale(),
                        context.settings().maxStringLength());
                DecisionRequest single = DecisionRequest.of(request.ruleSet(), item.input())
                        .withCorrelationId(item.correlationId());
                items.add(BatchResult.Item.decided(i,
                        decide(tenant, caller, null, single, selected, now, at, reads)));
            } catch (InputInvalidException e) {
                // An input that fails validation is not a decision: no row, and the other items go on.
                items.add(BatchResult.Item.refused(i, e.code(), e.getMessage()));
            }
            if (reads.spent() && stoppedAt < 0) {
                stoppedAt = i;
                System.getLogger(DefaultRuleEngine.class.getName()).log(System.Logger.Level.WARNING,
                        "Batch of " + request.ruleSet() + " for tenant " + tenant + " stopped at item " + i + " of "
                                + request.items().size() + ": evaluation.regex-max-reads-per-request ("
                                + reads.budget() + ") spent");
            }
        }
        return new BatchResult(selected.version().ruleSet(), selected.version().version(), at, items);
    }

    /** The stored form of a batch: the version and clock, and per item a decision as on {@code :evaluate} or a problem. */
    private static ObjectNode batchJson(BatchResult result) {
        ObjectNode node = EngineJson.NODES.objectNode();
        node.put("ruleSet", result.ruleSet());
        node.put("version", result.version());
        node.put("clock", result.clock().toString());
        ArrayNode items = node.putArray("items");
        for (BatchResult.Item item : result.items()) {
            ObjectNode entry = items.addObject().put("index", item.index());
            if (item.decision() != null) {
                entry.set("decision", EngineValues.decision(item.decision()));
            } else {
                entry.put("code", item.code()).put("detail", item.detail());
            }
        }
        return node;
    }

    private static BatchResult batchFromJson(String json) {
        JsonNode node = EngineValues.read(json);
        List<BatchResult.Item> items = new ArrayList<>();
        for (JsonNode entry : node.get("items")) {
            int index = entry.get("index").asInt();
            items.add(entry.has("decision")
                    ? BatchResult.Item.decided(index, EngineValues.decisionFromJson(EngineValues.write(entry.get("decision"))))
                    : BatchResult.Item.refused(index, entry.get("code").stringValue(), entry.get("detail").stringValue()));
        }
        return new BatchResult(node.get("ruleSet").stringValue(), node.get("version").asInt(),
                Instant.parse(node.get("clock").stringValue()), items);
    }

    /** Gives a replayed batch the output types of a fresh one, item by item, as a replayed {@code :evaluate} gets. */
    private BatchResult typedBatch(String tenant, BatchResult stored) {
        List<BatchResult.Item> items = new ArrayList<>(stored.items().size());
        // One version for the whole batch, so one read, not one per item.
        CompiledRuleSet rules = versionRules(tenant, stored.ruleSet(), stored.version());
        for (BatchResult.Item item : stored.items()) {
            items.add(item.decision() == null ? item
                    : BatchResult.Item.decided(item.index(), typed(item.decision(), rules)));
        }
        return new BatchResult(stored.ruleSet(), stored.version(), stored.clock(), items);
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
        int stoppedAt = -1;
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
        // One regex read budget for every decision of this request (evaluation.regex-max-reads-per-request, RLE-007):
        // once it stops a match, no later item is evaluated, and the same samples stop at the same item on replay.
        RequestReads request = context.settings().regexLimits().newRequest();
        for (int i = 0; i < samples.size(); i++) {
            DecisionInput sample = samples.get(i);
            if (context.settings().rejectUndeclaredInputs()) {
                atSample(i, () -> draftRules.requireOnlyDeclared(sample));
            }
            DecisionResult.Outcome draft = request.spent() ? notRun(request)
                    : draftRules.evaluateWithReason(sample, null, request).outcome();
            DecisionResult.Outcome baseline = baselineRules == null ? null
                    : request.spent() ? notRun(request)
                    : baselineRules.evaluateWithReason(only(sample, baselineInputs), null, request).outcome();
            if (request.spent() && stoppedAt < 0) {
                stoppedAt = i;
                System.getLogger(DefaultRuleEngine.class.getName()).log(System.Logger.Level.WARNING,
                        "Simulation of " + ruleSet + " v" + number + " for tenant " + tenant + " stopped at sample "
                                + i + " of " + samples.size() + ": evaluation.regex-max-reads-per-request ("
                                + request.budget() + ") spent");
            }
            results.add(new SimulationResult.Sample(i, draft, baseline, baseline == null || changed(draft, baseline)));
        }
        return new SimulationResult(simulated.ruleSet(), simulated.version(), simulated.status(),
                effective == null ? null : effective.version(), now, results);
    }

    @Override
    public HistorySimulationResult simulateHistory(String ruleSet, int version, HistorySimulation request) {
        String tenant = context.tenant();
        // Permissions first: without both, the engine reads and evaluates nothing (DESIGN 4.2.2).
        context.callerWith(PERMISSION_SIMULATE);
        context.callerWith(PERMISSION_LOG_READ);
        int limit = historyLimit(request);
        return context.withoutDooming(() -> doSimulateHistory(tenant, ruleSet, version, request, limit));
    }

    /** The period and the limit of a history simulation, checked before any read (RLE-015). */
    private int historyLimit(HistorySimulation request) {
        if (request == null || request.from() == null || request.to() == null) {
            throw new QueryInvalidException("from and to are required");
        }
        if (!request.to().isAfter(request.from())) {
            throw new QueryInvalidException("to must be after from");
        }
        Duration range = Duration.between(request.from(), request.to());
        if (range.compareTo(querySettings.maxRange()) > 0) {
            throw new QueryInvalidException("from and to may be at most " + querySettings.maxRange().toDays()
                    + " days apart (query.max-range " + querySettings.maxRange() + "), were " + range);
        }
        int max = context.settings().simulationMaxInputs();
        if (request.limit() == null) {
            return max;
        }
        if (request.limit() < 1 || request.limit() > max) {
            throw new QueryInvalidException("limit must be 1 to " + max + " (simulation.max-inputs), was "
                    + request.limit());
        }
        return request.limit();
    }

    private HistorySimulationResult doSimulateHistory(String tenant, String ruleSet, int number,
            HistorySimulation period, int limit) {
        if (!RuleSetReader.isRuleSetName(ruleSet)) {
            throw new VersionNotFoundException("(invalid name)", number);
        }
        // Any tenant but the caller's has no such version: the same answer whether or not the rule set exists, and
        // no decision is read.
        StoredVersion simulated = store.findVersion(tenant, ruleSet, number, false)
                .orElseThrow(() -> new VersionNotFoundException(ruleSet, number));
        CompiledRuleSet draftRules = validated(simulated);
        Set<String> draftInputs = new HashSet<>();
        draftRules.inputs().forEach(clause -> draftInputs.add(clause.name()));
        // One read, tenant and rule set first and the period on the partition key, newest first; one row more than
        // the limit tells whether the period held more.
        List<DecisionRecord> rows = log.search(tenant, ruleSet, period.from(), period.to(), null, limit + 1,
                querySettings.statementTimeout());
        boolean truncated = rows.size() > limit;
        Map<Integer, CompiledRuleSet> loggedVersions = new HashMap<>();
        RequestReads request = context.settings().regexLimits().newRequest();
        List<HistorySimulationResult.Item> items = new ArrayList<>(Math.min(rows.size(), limit));
        boolean warned = false;
        for (DecisionRecord row : rows.subList(0, Math.min(rows.size(), limit))) {
            CompiledRuleSet loggedRules = loggedVersions.computeIfAbsent(row.version(),
                    v -> loggedRules(tenant, ruleSet, v));
            if (loggedRules == null) {
                // The logged version no longer compiles or hashes: what was decided cannot be evaluated again.
                DecisionResult.Outcome failed = new DecisionResult.Failed("EVALUATION_ERROR", "Logged version "
                        + row.version() + " of " + ruleSet + " cannot be evaluated again");
                items.add(new HistorySimulationResult.Item(row.id(), row.version(), row.evalClock(), null, failed,
                        false, List.of()));
                continue;
            }
            List<String> masked = maskedWithValue(row, loggedRules.inputs());
            if (!masked.isEmpty()) {
                items.add(new HistorySimulationResult.Item(row.id(), row.version(), row.evalClock(), null, null,
                        false, masked));
                continue;
            }
            DecisionInput input = EngineValues.inputsFromJson(EngineValues.read(row.maskedInputsJson()));
            DecisionResult.Outcome logged = request.spent() ? notRun(request)
                    : loggedRules.evaluateWithReason(input, row.evalClock(), request).outcome();
            DecisionResult.Outcome draft = request.spent() ? notRun(request)
                    : draftRules.evaluateWithReason(only(input, draftInputs), row.evalClock(), request).outcome();
            if (request.spent() && !warned) {
                warned = true;
                System.getLogger(DefaultRuleEngine.class.getName()).log(System.Logger.Level.WARNING,
                        "History simulation of " + ruleSet + " v" + number + " for tenant " + tenant + " stopped at "
                                + "decision " + row.id() + ": evaluation.regex-max-reads-per-request ("
                                + request.budget() + ") spent");
            }
            items.add(new HistorySimulationResult.Item(row.id(), row.version(), row.evalClock(), draft, logged,
                    changed(draft, logged), List.of()));
        }
        return new HistorySimulationResult(simulated.ruleSet(), simulated.version(), simulated.status(),
                period.from(), period.to(), truncated, items);
    }

    /** A logged version by number, never selected, as replay loads it; null when it cannot be evaluated again. */
    private CompiledRuleSet loggedRules(String tenant, String ruleSet, int number) {
        StoredVersion version = store.findVersion(tenant, ruleSet, number, false).orElse(null);
        if (version == null) {
            return null;
        }
        try {
            return compiled(version);
        } catch (IntegrityFailure failure) {
            System.getLogger(DefaultRuleEngine.class.getName()).log(System.Logger.Level.ERROR,
                    "ALERT rule set integrity: tenant " + tenant + ", " + failure.getMessage());
            return null;
        }
    }

    /**
     * The outcome of an item a request did not evaluate because an earlier item spent the request's regex read budget
     * (RLE-007): the same code as a decision stopped by a regex budget, naming the setting.
     */
    private static DecisionResult.Failed notRun(RequestReads request) {
        return new DecisionResult.Failed("EVALUATION_ERROR", "REGEX_TIMEOUT: not evaluated, an earlier item spent the"
                + " request's regular expression budget of " + request.budget()
                + " characters (evaluation.regex-max-reads-per-request)");
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

    /**
     * The signature of a stored version by number, in any status; only {@link EngineScopedRuleEngine} calls it, for an
     * embedded engine checking a candidate guardrail before a replay. Reads only.
     *
     * @param tenant the tenant
     * @param ruleSet the rule set name
     * @param number the version number
     * @return the signature
     */
    io.github.dhluong90.bpm.rules.embedded.RuleSetSignature signatureOfVersionAs(String tenant, String ruleSet,
            int number) {
        if (!RuleSetReader.isRuleSetName(ruleSet)) {
            throw new VersionNotFoundException("(invalid name)", number);
        }
        return context.withoutDooming(() -> {
            StoredVersion version = store.findVersion(tenant, ruleSet, number, false)
                    .orElseThrow(() -> new VersionNotFoundException(ruleSet, number));
            CompiledRuleSet rules = validated(version);
            Map<String, String> inputs = new LinkedHashMap<>();
            rules.inputs().forEach(clause -> inputs.put(clause.name(), clause.type().toString()));
            Map<String, String> outputs = new LinkedHashMap<>();
            rules.outputs().forEach(clause -> outputs.put(clause.name(), clause.type().toString()));
            return new io.github.dhluong90.bpm.rules.embedded.RuleSetSignature(version.ruleSet(), version.version(),
                    rules.hitPolicy().name(), inputs, outputs, context.settings().rejectUndeclaredInputs(),
                    rules.inputsRead());
        });
    }

    /**
     * Simulates a stored version over recorded cases, each at its own evaluation time, for an embedded engine's replay
     * (RLE-015); only {@link EngineScopedRuleEngine} calls it. Writes nothing. A case that cannot be evaluated is a
     * failed outcome at its position; one regex read budget covers the call, as for {@link #simulate}.
     *
     * @param tenant the tenant
     * @param ruleSet the rule set name
     * @param number the version number
     * @param cases 1 to {@code simulation.max-inputs} cases
     * @return one outcome per case, in order
     */
    List<DecisionResult.Outcome> simulateAs(String tenant, String ruleSet, int number,
            List<io.github.dhluong90.bpm.rules.embedded.TimedInput> cases) {
        int limit = context.settings().simulationMaxInputs();
        if (cases.isEmpty() || cases.size() > limit) {
            throw new IllegalArgumentException("cases must hold 1 to " + limit + " items (simulation.max-inputs)");
        }
        if (!RuleSetReader.isRuleSetName(ruleSet)) {
            throw new VersionNotFoundException("(invalid name)", number);
        }
        return context.withoutDooming(() -> {
            StoredVersion stored = store.findVersion(tenant, ruleSet, number, false)
                    .orElseThrow(() -> new VersionNotFoundException(ruleSet, number));
            CompiledRuleSet rules = validated(stored);
            RequestReads request = context.settings().regexLimits().newRequest();
            List<DecisionResult.Outcome> out = new ArrayList<>(cases.size());
            for (io.github.dhluong90.bpm.rules.embedded.TimedInput item : cases) {
                if (request.spent()) {
                    out.add(notRun(request));
                    continue;
                }
                try {
                    InputLimits.check(item.input(), EngineDefaults.DECIMAL_PRECISION,
                            context.settings().maxInputScale(), context.settings().maxStringLength());
                    if (context.settings().rejectUndeclaredInputs()) {
                        rules.requireOnlyDeclared(item.input());
                    }
                    out.add(rules.evaluateWithReason(item.input(), item.evaluationTime(), request).outcome());
                } catch (io.github.dhluong90.bpm.rules.api.HorusBpmException e) {
                    out.add(new DecisionResult.Failed(e.code(), e.getMessage()));
                }
            }
            return out;
        });
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
        return typed(stored, versionRules(tenant, stored.ruleSet(), stored.version()));
    }

    /** The compiled version a stored decision was made with, or null when it cannot be read back. */
    private CompiledRuleSet versionRules(String tenant, String ruleSet, int version) {
        StoredVersion stored = store.findVersion(tenant, ruleSet, version, false).orElse(null);
        if (stored == null) {
            return null;
        }
        try {
            return compiled(stored);
        } catch (IntegrityFailure failure) {
            return null;
        }
    }

    private static DecisionResult typed(DecisionResult stored, CompiledRuleSet rules) {
        if (rules == null || stored.outcome() instanceof DecisionResult.Failed) {
            return stored;
        }
        Map<String, Object> typed = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> output : stored.outputs().entrySet()) {
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

    /**
     * The hit policy and declared inputs and outputs of the version effective at {@code at}; only
     * {@link EngineScopedRuleEngine} calls it. Reads only.
     *
     * @param tenant the tenant
     * @param ruleSet the rule set name
     * @param at the instant that selects the version
     * @return the signature
     */
    io.github.dhluong90.bpm.rules.embedded.RuleSetSignature signatureAs(String tenant, String ruleSet, Instant at) {
        if (!io.github.dhluong90.bpm.rules.internal.read.RuleSetReader.isRuleSetName(ruleSet)) {
            throw new RuleSetNotFoundException("(invalid name)");
        }
        return context.withoutDooming(() -> {
            StoredVersion version = effective(tenant, ruleSet, at).version();
            CompiledRuleSet rules = validated(version);
            Map<String, String> inputs = new java.util.LinkedHashMap<>();
            rules.inputs().forEach(clause -> inputs.put(clause.name(), clause.type().toString()));
            Map<String, String> outputs = new java.util.LinkedHashMap<>();
            rules.outputs().forEach(clause -> outputs.put(clause.name(), clause.type().toString()));
            return new io.github.dhluong90.bpm.rules.embedded.RuleSetSignature(version.ruleSet(), version.version(),
                    rules.hitPolicy().name(), inputs, outputs, context.settings().rejectUndeclaredInputs(),
                    rules.inputsRead());
        });
    }

    private DecisionResult doEvaluate(String tenant, String caller, String onBehalfOf, DecisionRequest request) {
        Instant now = context.clock().instant();
        Instant at = request.clock() == null ? now : request.clock();
        Selected selected = select(tenant, request.ruleSet(), request.version(), at);
        return decide(tenant, caller, onBehalfOf, request, selected, now, at, null);
    }

    /** The version an evaluation uses, compiled, or the integrity failure that stops it. */
    private record Selected(StoredVersion version, CompiledRuleSet rules, IntegrityFailure failure) {
    }

    /**
     * Selects the version in force at {@code at}, or the pinned one, and compiles it. A selection that cannot be
     * served is not found or has no effective version; a version whose stored hash no longer matches is returned
     * with its failure, so each decision on it is refused and logged.
     */
    private Selected select(String tenant, String ruleSet, Integer pin, Instant at) {
        if (!io.github.dhluong90.bpm.rules.internal.read.RuleSetReader.isRuleSetName(ruleSet)) {
            // Not a possible name (a NUL byte, upper case): not found, without a query the driver would refuse.
            throw new RuleSetNotFoundException("(invalid name)");
        }
        if (pin == null) {
            CompiledCache.Entry entry = cache.get(tenant, ruleSet, at);
            if (entry == null) {
                RuleStore.EffectiveVersion effective = effective(tenant, ruleSet, at);
                try {
                    entry = new CompiledCache.Entry(effective.version(), compiled(effective.version()),
                            effective.cacheEpoch(), effective.validFrom(), effective.nextEffectiveFrom());
                } catch (IntegrityFailure failure) {
                    return new Selected(effective.version(), null, failure);
                }
                cache.put(entry);
            }
            return new Selected(entry.version(), entry.compiled(), null);
        }
        StoredVersion version = pinned(tenant, ruleSet, pin);
        try {
            return new Selected(version, compiled(version), null);
        } catch (IntegrityFailure failure) {
            return new Selected(version, null, failure);
        }
    }

    /**
     * Evaluates one request on a selected version and logs exactly one decision row. {@code requestReads}, when not
     * null, is the regex budget of the batch the item belongs to (RLE-007).
     */
    private DecisionResult decide(String tenant, String caller, String onBehalfOf, DecisionRequest request,
            Selected selected, Instant now, Instant at, RequestReads requestReads) {
        StoredVersion version = selected.version();
        if (selected.failure() != null) {
            return refuseTampered(tenant, caller, onBehalfOf, request, version, now, at, selected.failure());
        }
        CompiledRuleSet rules = selected.rules();
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
            return refuse(tenant, caller, onBehalfOf, request, version, now, at, MASKING_KEY_MISSING, "No masking key is configured"
                    + " for this tenant, so the inputs of " + version.ruleSet() + " cannot be logged; set "
                    + EngineDefaults.MASKING_KEY_PROPERTY);
        }
        String echoed = rules.inputs().stream()
                .filter(clause -> clause.masked(maskByDefault) && rules.inputsInOutputs().contains(clause.name()))
                .map(clause -> clause.name()).findFirst().orElse(null);
        if (echoed != null) {
            // Import refuses an output that reads a declared mask: true input; this covers the tenant's mask default.
            return refuse(tenant, caller, onBehalfOf, request, version, now, at, MASKED_INPUT_IN_OUTPUT, "Input " + echoed + " of "
                    + version.ruleSet() + " is masked for this tenant, but an output reads it; the decision is refused"
                    + " so the plain value is not logged");
        }
        long started = System.nanoTime();
        CompiledRuleSet.Evaluation evaluation = rules.evaluateWithReason(request.input(), at, requestReads);
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
                EngineValues.write(matched), caller, request.correlationId(), at, micros, pinned, onBehalfOf));
        return result;
    }

    /** Logs a decision on a version whose stored hash no longer matches its content, and raises the alert. */
    private DecisionResult refuseTampered(String tenant, String caller, String onBehalfOf, DecisionRequest request, StoredVersion version,
            Instant now, Instant at, IntegrityFailure failure) {
        System.getLogger(DefaultRuleEngine.class.getName()).log(System.Logger.Level.ERROR,
                "ALERT rule set integrity: tenant " + tenant + ", " + failure.getMessage());
        return refuse(tenant, caller, onBehalfOf, request, version, now, at, "EVALUATION_ERROR", failure.getMessage());
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
        List<String> masked = maskedWithValue(logged, declared);
        if (!masked.isEmpty()) {
            throw new ReplayNotPossibleException(decisionId, masked);
        }
        return EngineValues.inputsFromJson(EngineValues.read(logged.maskedInputsJson()));
    }

    /** The declared inputs a decision logged only as a fingerprint, so it cannot be evaluated again; names only. */
    private static List<String> maskedWithValue(DecisionRecord logged, List<InputClause> declared) {
        JsonNode inputs = EngineValues.read(logged.maskedInputsJson());
        List<String> masked = new ArrayList<>();
        for (InputClause clause : declared) {
            JsonNode value = inputs.get(clause.name());
            boolean fingerprint = value != null && value.isString() && MaskingKey.isFingerprint(value.stringValue());
            if ((clause.masked() && value != null && !value.isNull()) || fingerprint) {
                masked.add(clause.name());
            }
        }
        return masked;
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
                context.settings().regexLimits(), context.settings().evaluationLimits());
        compiled.put(key, fresh);
        return fresh;
    }

    /**
     * Records a refused evaluation as a decision with outcome {@code EVALUATION_ERROR} (the audit entry) and no logged
     * input: for a tampered version its masking cannot be trusted, and without a masking key the inputs could only
     * be logged in plain text. The rule set is not evaluated.
     */
    private DecisionResult refuse(String tenant, String caller, String onBehalfOf, DecisionRequest request,
            StoredVersion version,
            Instant now, Instant at, String errorCode, String detail) {
        DecisionResult result = new DecisionResult(Ulid.next(now), version.ruleSet(), version.version(), at, now,
                request.version() != null, new DecisionResult.Failed("EVALUATION_ERROR", detail));
        log.append(new DecisionRecord(tenant, result.decisionId(), now, version.ruleSet(), version.version(),
                version.hitPolicy(), "EVALUATION_ERROR", errorCode, "{}", null, "[]", caller,
                request.correlationId(), at, 0, result.pinned(), onBehalfOf));
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
