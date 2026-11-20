package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.api.AgentReplayCase;
import io.github.dhluong90.bpm.process.api.AgentReplayRequest;
import io.github.dhluong90.bpm.process.api.AgentReplayRun;
import io.github.dhluong90.bpm.process.api.AgentReplayService;
import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.GuardrailContractInvalidException;
import io.github.dhluong90.bpm.process.api.GuardrailNotFoundException;
import io.github.dhluong90.bpm.process.api.IdempotencyKeyReuseException;
import io.github.dhluong90.bpm.process.api.InputInvalidException;
import io.github.dhluong90.bpm.process.api.Page;
import io.github.dhluong90.bpm.process.api.PageRequest;
import io.github.dhluong90.bpm.process.api.PermissionDeniedException;
import io.github.dhluong90.bpm.process.api.QueryInvalidException;
import io.github.dhluong90.bpm.process.api.ReplayNotFoundException;
import io.github.dhluong90.bpm.process.api.ReplayTooLargeException;
import io.github.dhluong90.bpm.process.api.UnauthenticatedException;
import io.github.dhluong90.bpm.process.internal.json.Hashes;
import io.github.dhluong90.bpm.process.internal.json.Ulid;
import io.github.dhluong90.bpm.process.internal.json.Values;
import io.github.dhluong90.bpm.process.internal.store.AgentReplayStore;
import io.github.dhluong90.bpm.process.internal.store.AgentReplayStore.RecordedAnswer;
import io.github.dhluong90.bpm.process.internal.store.AgentReplayStore.StoredRun;
import io.github.dhluong90.bpm.process.internal.store.TransactionRunner;
import io.github.dhluong90.bpm.process.spi.CallerResolver;
import io.github.dhluong90.bpm.process.spi.OutputSchemaValidator;
import io.github.dhluong90.bpm.process.spi.TenantResolver;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Offline replay of recorded agent answers against a candidate guardrail or output schema (PRE-092 to PRE-095,
 * PD-128, DESIGN section 4.1). Starting a run checks the request, the size of the period and the candidate's contract,
 * and stores a {@code QUEUED} run; {@link #pollOnce()}, called by the job poller, replays one page of
 * {@code replay.page-size} answers of one run per transaction, keyset-paged over {@code (occurred_at, id)} of
 * {@code pre_history}, and deletes runs past {@code replay.retention}.
 *
 * <p>Read-only towards live data: a replay calls no adapter and no model, never writes {@code pre_history} or the
 * decision log, and never moves an instance. It evaluates through {@link DecisionPort#simulate}, which writes
 * nothing. The tenant comes from the resolver; the worker uses the tenant stored on the run. Thread-safe.
 */
public final class AgentReplays implements AgentReplayService {

    /** The permission of every replay operation (ADR-0010 as amended for PD-128). */
    public static final String PERMISSION_REPLAY = "processes:agent:replay";

    static final String QUEUED = "QUEUED";
    static final String RUNNING = "RUNNING";
    static final String COMPLETED = "COMPLETED";
    static final String FAILED = "FAILED";
    /** Most runs deleted by one poll. */
    static final int PURGE_BATCH = 100;

    private static final System.Logger LOG = System.getLogger(AgentReplays.class.getName());
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,199}$");
    private static final Pattern RULE_SET = Pattern.compile("^[a-z][a-z0-9]*([.-][a-z0-9]+)*$");
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;

    private final TenantResolver tenants;
    private final CallerResolver callers;
    private final Clock clock;
    private final TransactionRunner transactions;
    private final AgentReplayStore store;
    private final Optional<DecisionPort> decisions;
    private final OutputSchemaValidator schemas;
    private final ReplaySettings settings;
    private final java.time.Duration maxRange;

    /**
     * @param tenants tenant resolver
     * @param callers caller resolver
     * @param clock engine clock
     * @param transactions transactions on the host's transaction manager
     * @param store replay storage
     * @param decisions the host's decision port, empty without a Rule Engine
     * @param schemas the output schema validator of the result transaction
     * @param settings replay settings
     * @param maxRange {@code query.max-range}: the longest period
     */
    public AgentReplays(TenantResolver tenants, CallerResolver callers, Clock clock, TransactionRunner transactions,
            AgentReplayStore store, Optional<DecisionPort> decisions, OutputSchemaValidator schemas,
            ReplaySettings settings, java.time.Duration maxRange) {
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.callers = Objects.requireNonNull(callers, "callers");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.store = Objects.requireNonNull(store, "store");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.schemas = Objects.requireNonNull(schemas, "schemas");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.maxRange = Objects.requireNonNull(maxRange, "maxRange");
    }

    @Override
    public AgentReplayRun start(AgentReplayRequest request) {
        String tenant = tenant();
        String caller = requirePermission();
        Objects.requireNonNull(request, "request must not be null");
        validate(request);
        Instant from = request.from().atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = request.to().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        String requestHash = request.idempotencyKey() == null ? null : Hashes.sha256Parts("agent-replay", caller,
                request.definitionKey(), request.nodeId(), request.from().toString(), request.to().toString(),
                request.candidateRuleSet(), Integer.toString(request.candidateVersion()),
                request.candidateOutputSchema() == null ? "" : Values.write(request.candidateOutputSchema()));
        return transactions.inTransaction(() -> {
            if (request.idempotencyKey() != null) {
                Optional<StoredRun> earlier = store.findRunByIdempotencyKey(tenant, request.idempotencyKey());
                if (earlier.isPresent()) {
                    if (!requestHash.equals(earlier.get().requestHash())) {
                        throw new IdempotencyKeyReuseException("Idempotency-Key was used with a different replay");
                    }
                    return earlier.get().view();
                }
            }
            long count = store.countAnswers(tenant, request.definitionKey(), request.nodeId(), from, to);
            if (count > settings.maxCases()) {
                throw new ReplayTooLargeException(count, settings.maxCases());
            }
            Map<String, Object> schema = request.candidateOutputSchema() != null ? request.candidateOutputSchema()
                    : store.latestRecordedSchema(tenant, request.definitionKey(), request.nodeId(), from, to)
                            .orElse(null);
            checkCandidate(tenant, request, schema);
            Instant now = clock.instant();
            String id = Ulid.next(now);
            AgentReplayRun view = new AgentReplayRun(id, QUEUED, request.definitionKey(), request.nodeId(),
                    request.from(), request.to(), request.candidateRuleSet(), request.candidateVersion(),
                    request.candidateOutputSchema() != null, AgentReplayRun.Summary.EMPTY, null, caller, now, null,
                    now.plus(settings.retention()));
            store.insertRun(new StoredRun(tenant, view, request.candidateOutputSchema(), request.idempotencyKey(),
                    requestHash, null, null));
            LOG.log(System.Logger.Level.INFO, "process.agent.replay.started tenant={0} run={1} definition={2} node={3}"
                    + " candidate={4} v{5} cases={6}", tenant, id, request.definitionKey(), request.nodeId(),
                    request.candidateRuleSet(), request.candidateVersion(), count);
            return view;
        });
    }

    private void validate(AgentReplayRequest request) {
        if (request.definitionKey() == null || !KEY.matcher(request.definitionKey()).matches()) {
            throw new InputInvalidException("definitionKey must be a definition key");
        }
        if (request.nodeId() == null || !KEY.matcher(request.nodeId()).matches()) {
            throw new InputInvalidException("nodeId must be a step id");
        }
        if (request.candidateRuleSet() == null || !RULE_SET.matcher(request.candidateRuleSet()).matches()) {
            throw new InputInvalidException("candidate.ruleSet must be a rule set name");
        }
        if (request.candidateVersion() < 1) {
            throw new InputInvalidException("candidate.version must be 1 or more");
        }
        if (request.from() == null || request.to() == null) {
            throw new QueryInvalidException("from and to are required");
        }
        if (request.from().isAfter(request.to())) {
            throw new QueryInvalidException("from must not be after to");
        }
        long days = ChronoUnit.DAYS.between(request.from(), request.to()) + 1;
        if (days > maxRange.toDays()) {
            throw new QueryInvalidException("The period is " + days + " days, longer than " + maxRange
                    + " (query.max-range)");
        }
    }

    /**
     * The candidate must exist in the caller's tenant and meet the guardrail contract (PRE-016) against the schema the
     * answers are checked with; without any schema (no recorded answer and no candidate schema) its inputs are not
     * checked. A candidate output schema must be one the validator supports.
     */
    private void checkCandidate(String tenant, AgentReplayRequest request, Map<String, Object> schema) {
        List<DefinitionProblem> problems = new ArrayList<>();
        if (request.candidateOutputSchema() != null) {
            schemas.checkSchema(request.candidateOutputSchema()).forEach(p -> problems.add(DefinitionProblem.of(
                    AgentResultGuard.SPEC_INVALID, "candidate outputSchema: " + p, request.nodeId())));
        }
        if (decisions.isEmpty()) {
            problems.add(DefinitionProblem.of(GuardrailContractInvalidException.CODE,
                    "the host has no Rule Engine to evaluate a guardrail", request.nodeId()));
            throw new GuardrailContractInvalidException(problems);
        }
        DecisionSignature signature = decisions.get().signature(tenant, request.candidateRuleSet(),
                request.candidateVersion());
        if (signature instanceof DecisionSignature.Unavailable u && ("VERSION_NOT_FOUND".equals(u.code())
                || "RULE_SET_NOT_FOUND".equals(u.code()))) {
            throw new GuardrailNotFoundException("No rule set " + request.candidateRuleSet() + " version "
                    + request.candidateVersion());
        }
        Map<String, Object> checked = schema != null ? schema : anySchema(signature);
        GuardrailContract.check(request.candidateRuleSet(), signature, checked).forEach(failure -> problems.add(
                DefinitionProblem.of(GuardrailContractInvalidException.CODE, failure, request.nodeId())));
        if (!problems.isEmpty()) {
            throw new GuardrailContractInvalidException(problems);
        }
    }

    /** A schema that admits every input the rule set declares, with any type: only hit policy and outputs count. */
    private static Map<String, Object> anySchema(DecisionSignature signature) {
        Map<String, Object> properties = new LinkedHashMap<>();
        if (signature instanceof DecisionSignature.Found found) {
            found.inputs().keySet().forEach(name -> properties.put(name, Map.of()));
        }
        return Map.of("type", "object", "additionalProperties", false, "properties", properties);
    }

    @Override
    public AgentReplayRun run(String runId) {
        String tenant = tenant();
        requirePermission();
        return transactions.inTransaction(() -> find(tenant, runId).view());
    }

    @Override
    public Page<AgentReplayCase> cases(String runId, PageRequest page) {
        String tenant = tenant();
        requirePermission();
        PageRequest request = page == null ? PageRequest.first() : page;
        int limit = request.limit() == null ? DEFAULT_LIMIT : request.limit();
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new QueryInvalidException("limit must be 1 to " + MAX_LIMIT);
        }
        return transactions.inTransaction(() -> {
            StoredRun run = find(tenant, runId);
            int after = afterSeq(run.view().id(), request.cursor());
            List<AgentReplayStore.CaseRow> rows = store.cases(tenant, run.view().id(), after, limit + 1);
            boolean more = rows.size() > limit;
            List<AgentReplayStore.CaseRow> shown = more ? rows.subList(0, limit) : rows;
            String next = more ? cursor(run.view().id(), shown.get(shown.size() - 1).seq()) : null;
            return new Page<>(shown.stream().map(AgentReplayStore.CaseRow::line).toList(), next);
        });
    }

    @Override
    public Map<String, Object> recordedAnswer(String runId, String attemptId) {
        String tenant = tenant();
        requirePermission();
        return transactions.inTransaction(() -> {
            StoredRun run = find(tenant, runId);
            AgentReplayCase line = store.findCase(tenant, run.view().id(), attemptId == null ? "" : attemptId)
                    .orElseThrow(() -> new ReplayNotFoundException("Replay " + runId + " has no case " + attemptId));
            Map<String, Object> details = store.recordedDetails(tenant, line.instanceId(), line.attemptId())
                    .orElseThrow(() -> new ReplayNotFoundException("The recorded answer of " + attemptId
                            + " is no longer kept"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("attemptId", line.attemptId());
            out.put("instanceId", line.instanceId());
            out.put("output", details.getOrDefault("output", Map.of()));
            out.put("inputs", details.getOrDefault("inputs", Map.of()));
            return out;
        });
    }

    /**
     * Replays one page of one queued or running run of any tenant, in one transaction holding the run row's lock, so
     * two replicas never replay the same page; then deletes up to {@value #PURGE_BATCH} expired runs. Called by the
     * job poller.
     *
     * @return pages replayed plus runs deleted; 0 when there was nothing to do
     */
    public int pollOnce() {
        java.util.concurrent.atomic.AtomicReference<StoredRun> current = new java.util.concurrent.atomic.AtomicReference<>();
        int done;
        try {
            done = transactions.inTransaction(() -> replayPage(current));
        } catch (RuntimeException e) {
            done = fail(current.get(), e);
        }
        Instant now = clock.instant();
        int purged = transactions.inTransaction(() -> store.deleteExpired(now, PURGE_BATCH));
        if (purged > 0) {
            LOG.log(System.Logger.Level.INFO, "process.agent.replay.purged runs={0}", purged);
        }
        return done + purged;
    }

    private int replayPage(java.util.concurrent.atomic.AtomicReference<StoredRun> current) {
        Optional<StoredRun> next = store.lockNextRun();
        if (next.isEmpty()) {
            return 0;
        }
        StoredRun run = next.get();
        current.set(run);
        AgentReplayRun view = run.view();
        String tenant = run.tenantId();
        Instant from = view.from().atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = view.to().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<RecordedAnswer> answers = store.answers(tenant, view.definitionKey(), view.nodeId(), from, to,
                run.cursorAt(), run.cursorId(), settings.pageSize());
        Set<String> instances = new LinkedHashSet<>();
        Set<String> attempts = new LinkedHashSet<>();
        answers.forEach(a -> {
            instances.add(a.instanceId());
            attempts.add(a.attemptId());
        });
        Set<String> overridden = answers.isEmpty() ? Set.of()
                : store.overriddenAttempts(tenant, instances, attempts);
        DecisionPort port = decisions.orElseGet(() -> (t, c, r, i, at, k, corr) ->
                new DecisionOutcome.Failed("RULE_ENGINE_MISSING", "no Rule Engine"));
        AgentReplayCheck check = new AgentReplayCheck(schemas, port, view.candidateRuleSet(), view.candidateVersion(),
                run.candidateSchema());
        List<AgentReplayCase> lines = new ArrayList<>(answers.size());
        for (RecordedAnswer answer : answers) {
            lines.add(check.check(tenant, answer, overridden.contains(answer.attemptId())));
        }
        AgentReplayRun.Summary summary = add(view.summary(), lines);
        store.insertCases(tenant, view.id(), view.summary().cases(), lines);
        boolean last = answers.size() < settings.pageSize();
        RecordedAnswer tail = answers.isEmpty() ? null : answers.get(answers.size() - 1);
        Instant now = clock.instant();
        store.advance(tenant, view.id(), last ? COMPLETED : RUNNING, tail == null ? run.cursorAt() : tail.occurredAt(),
                tail == null ? run.cursorId() : tail.historyId(), summary, last ? now : null);
        if (last) {
            LOG.log(System.Logger.Level.INFO, "process.agent.replay.completed tenant={0} run={1} cases={2} "
                    + "newlyAllowed={3} newlyRefused={4} newlyRoutedToReview={5}", tenant, view.id(), summary.cases(),
                    summary.newlyAllowed(), summary.newlyRefused(), summary.newlyRoutedToReview());
        }
        return 1;
    }

    /** A page that failed rolled back; its run is marked {@code FAILED} so it is not retried forever. */
    private int fail(StoredRun run, RuntimeException e) {
        LOG.log(System.Logger.Level.WARNING, "process.agent.replay.page.failed run={0} exception={1}",
                run == null ? "-" : run.view().id(), e.getClass().getName());
        if (run == null) {
            throw e;
        }
        transactions.inTransaction(() -> {
            store.fail(run.tenantId(), run.view().id(), e.getClass().getSimpleName(), clock.instant());
            return null;
        });
        return 1;
    }

    static AgentReplayRun.Summary add(AgentReplayRun.Summary s, List<AgentReplayCase> lines) {
        int cases = s.cases();
        int unchanged = s.unchanged();
        int allowed = s.newlyAllowed();
        int refused = s.newlyRefused();
        int review = s.newlyRoutedToReview();
        int overridden = s.overriddenByReviewer();
        int notReplayed = s.notReplayed();
        int masked = s.withMaskedFields();
        int maskedInput = s.maskedInputRequired();
        for (AgentReplayCase line : lines) {
            cases++;
            switch (line.change()) {
                case AgentReplayCheck.UNCHANGED -> unchanged++;
                case AgentReplayCheck.NEWLY_ALLOWED -> {
                    allowed++;
                    if (line.overriddenByReviewer()) {
                        overridden++;
                    }
                }
                case AgentReplayCheck.NEWLY_REFUSED -> refused++;
                case AgentReplayCheck.NEWLY_ROUTED_TO_REVIEW -> review++;
                default -> {
                    if (AgentReplayCheck.MASKED_INPUT_REQUIRED.equals(line.code())) {
                        maskedInput++;
                    } else {
                        notReplayed++;
                    }
                }
            }
            if (line.maskedFields() > 0) {
                masked++;
            }
        }
        return new AgentReplayRun.Summary(cases, unchanged, allowed, refused, review, overridden, notReplayed, masked,
                maskedInput);
    }

    private StoredRun find(String tenant, String runId) {
        if (runId == null || runId.isBlank() || runId.length() > 26) {
            throw new ReplayNotFoundException("No replay " + runId);
        }
        return store.findRun(tenant, runId).orElseThrow(() -> new ReplayNotFoundException("No replay " + runId));
    }

    /**
     * The report cursor: the run id and the last position. Every read filters by the caller's tenant and the run, so
     * a changed cursor can only move within the caller's own report.
     */
    static String cursor(String runId, int seq) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((runId + ":" + seq)
                .getBytes(StandardCharsets.UTF_8));
    }

    static int afterSeq(String runId, String cursor) {
        if (cursor == null) {
            return -1;
        }
        try {
            String text = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!text.startsWith(runId + ":")) {
                throw new QueryInvalidException("cursor was not issued by this report; pass nextCursor unchanged");
            }
            return Integer.parseInt(text.substring(runId.length() + 1));
        } catch (IllegalArgumentException e) {
            throw new QueryInvalidException("cursor was not issued by this report; pass nextCursor unchanged");
        }
    }

    private String tenant() {
        String tenant = tenants.currentTenantId();
        if (tenant == null || tenant.isBlank()) {
            throw new UnauthenticatedException("No tenant in the security context");
        }
        return tenant;
    }

    private String requirePermission() {
        String caller = callers.currentCallerId();
        if (caller == null || caller.isBlank()) {
            throw new UnauthenticatedException("No caller in the security context");
        }
        if (!callers.currentPermissions().contains(PERMISSION_REPLAY)) {
            throw new PermissionDeniedException("Needs " + PERMISSION_REPLAY);
        }
        return caller;
    }
}
