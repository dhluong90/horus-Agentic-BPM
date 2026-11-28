package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.api.DecisionLogService;
import io.github.dhluong90.bpm.rules.api.DecisionNotFoundException;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.SelfApprovalForbiddenException;
import io.github.dhluong90.bpm.rules.api.InvalidStateException;
import io.github.dhluong90.bpm.rules.internal.store.DecisionQueueAdmin;
import io.github.dhluong90.bpm.rules.api.DecisionSearchResult;
import io.github.dhluong90.bpm.rules.api.LoggedDecision;
import io.github.dhluong90.bpm.rules.api.MaskedInputQuery;
import io.github.dhluong90.bpm.rules.api.MaskingKey;
import io.github.dhluong90.bpm.rules.api.QueryInvalidException;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import io.github.dhluong90.bpm.rules.internal.json.EngineValues;
import io.github.dhluong90.bpm.rules.internal.json.Ulid;
import io.github.dhluong90.bpm.rules.internal.read.RuleSetReader;
import io.github.dhluong90.bpm.rules.internal.store.DecisionLog;
import io.github.dhluong90.bpm.rules.internal.store.DecisionRecord;
import io.github.dhluong90.bpm.rules.internal.store.EventOutbox;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads the decision log of the caller's tenant (RLE-012). The plain value of a masked-input search is fingerprinted
 * here, with every key the caller's tenant holds, before any query, and every search leaves one audit entry in the
 * outbox: the database only ever compares fingerprints, and a tenant's
 * fingerprint never matches another tenant's rows, because every query is filtered by the resolved tenant and
 * tenants may hold different keys. Thread-safe.
 */
public final class DefaultDecisionLogService implements DecisionLogService {

    /** Permission of every decision-log read (ADR-0010). */
    static final String PERMISSION_LOG_READ = "rules:log:read";

    /**
     * Permission of the masked-input search, separate from {@link #PERMISSION_LOG_READ} and held by no role unless
     * the host grants it: the search tests whether a person was decided on (architect condition (a) on #255).
     */
    static final String PERMISSION_MASKED_SEARCH = "rules:masked:search";

    /** Permission to ask for a retry or a resolve of a dead-lettered decision (RLE-231). */
    static final String PERMISSION_LOG_OPERATE = "rules:log:operate";

    /** Permission to approve or reject that request; never the requester's own (RLE-231). */
    static final String PERMISSION_LOG_APPROVE = "rules:log:approve";

    /** Event type of the audit entry of every request, approval and rejection on a dead letter. */
    static final String EVENT_DEAD_LETTER = "rules.decision-log.dead-letter-action.v1";

    /** The window of {@code masking.search.max-per-minute}. */
    private static final java.time.Duration SEARCH_WINDOW = java.time.Duration.ofMinutes(1);

    /** Callers remembered before expired windows are swept; bounds the memory of the limiter. */
    private static final int SEARCH_CALLERS_SWEEP_AT = 10_000;

    private static final Pattern DECISION_ID = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{26}$");
    private static final Pattern INPUT_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");

    /** Event type of the audit entry every masked-input search writes. */
    static final String EVENT_MASKED_SEARCH = "rules.decision-log.masked-input-searched.v1";

    private final EngineContext context;
    private final DecisionLog log;
    private final EventOutbox outbox;
    /** Reads the stored version a decision names, for its explanation; null when explanations are not offered. */
    private final io.github.dhluong90.bpm.rules.internal.store.RuleStore store;
    private final io.github.dhluong90.bpm.rules.spi.DecisionExplainer explainer;
    /** Recent search times per tenant and caller, on this replica only. */
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.ArrayDeque<Instant>> searches =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Creates the service.
     *
     * @param context shared engine context
     * @param log the decision log
     * @param outbox where the audit entry of each masked-input search is written
     */
    public DefaultDecisionLogService(EngineContext context, DecisionLog log, EventOutbox outbox) {
        this(context, log, outbox, null, null);
    }

    /**
     * Creates the service with decision explanations (RLE-030).
     *
     * @param context shared engine context
     * @param log the decision log
     * @param outbox where the audit entry of each masked-input search is written
     * @param store reads the stored version a decision names
     * @param explainer writes the text of an explanation
     */
    public DefaultDecisionLogService(EngineContext context, DecisionLog log, EventOutbox outbox,
            io.github.dhluong90.bpm.rules.internal.store.RuleStore store,
            io.github.dhluong90.bpm.rules.spi.DecisionExplainer explainer) {
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.log = Objects.requireNonNull(log, "log must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.store = store;
        this.explainer = explainer;
    }

    @Override
    public io.github.dhluong90.bpm.rules.api.DecisionExplanation explain(String decisionId, List<String> languages) {
        if (store == null || explainer == null) {
            throw new UnsupportedOperationException("This decision log service was built without explanations");
        }
        String tenant = context.tenant();
        context.callerWith(PERMISSION_LOG_READ);
        if (decisionId == null || !DECISION_ID.matcher(decisionId).matches()) {
            throw new DecisionNotFoundException("(invalid id)");
        }
        io.github.dhluong90.bpm.rules.api.DecisionExplanation explanation = context.withoutDooming(() -> {
            DecisionRecord row = log.find(tenant, decisionId).orElseThrow(() -> new DecisionNotFoundException(decisionId));
            // The version is read in the decision's tenant by name and number; versions are never deleted, so a logged
            // decision always finds the version it used, however many versions came after it.
            var version = store.findVersion(tenant, row.ruleSet(), row.version(), false).orElseThrow(() ->
                    new IllegalStateException("Decision " + decisionId + " names a version that is not stored"));
            return DecisionExplanations.explain(row, version);
        });
        java.util.Set<String> available = explainer.languagesFor(explanation);
        if (languages != null) {
            for (String language : languages) {
                if (language != null && available.contains(language)) {
                    var rendered = explainer.render(explanation, language);
                    return explanation.withText(language, rendered.text(), rendered.source());
                }
            }
        }
        // A language nobody asked for is never an author's: a template of another language is not shown (RLE-072).
        String fallback = explainer.defaultLanguage();
        return explanation.withText(fallback, explainer.text(explanation, fallback),
                io.github.dhluong90.bpm.rules.api.DecisionExplanation.GENERATED);
    }

    @Override
    public LoggedDecision get(String decisionId) {
        String tenant = context.tenant();
        context.callerWith(PERMISSION_LOG_READ);
        if (decisionId == null || !DECISION_ID.matcher(decisionId).matches()) {
            // Not a possible id: not found, without a query.
            throw new DecisionNotFoundException("(invalid id)");
        }
        return context.withoutDooming(() -> log.find(tenant, decisionId)
                .map(DefaultDecisionLogService::view)
                .orElseThrow(() -> new DecisionNotFoundException(decisionId)));
    }

    @Override
    public DecisionSearchResult findByMaskedInput(MaskedInputQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        String tenant = context.tenant();
        String caller = context.callerWith(PERMISSION_MASKED_SEARCH);
        if (!RuleSetReader.isRuleSetName(query.ruleSet())) {
            throw new QueryInvalidException("ruleSet must be a rule set name");
        }
        if (!INPUT_NAME.matcher(query.input()).matches()) {
            throw new QueryInvalidException("input must be an input name");
        }
        if (!query.from().isBefore(query.to())) {
            throw new QueryInvalidException("from must be before to");
        }
        if (Duration.between(query.from(), query.to()).compareTo(EngineDefaults.QUERY_MAX_RANGE) > 0) {
            throw new QueryInvalidException("The period must be at most " + EngineDefaults.QUERY_MAX_RANGE.toDays()
                    + " days");
        }
        int maxPage = context.settings().queryMaxPageSize();
        if (query.limit() < 1 || query.limit() > maxPage) {
            throw new QueryInvalidException("limit must be 1 to " + maxPage);
        }
        if (query.value() instanceof String text && text.length() > context.settings().maxStringLength()) {
            throw new QueryInvalidException("value is longer than " + context.settings().maxStringLength()
                    + " characters");
        }
        List<MaskingKey> keys = context.masking().searchKeys(tenant);
        if (keys.isEmpty()) {
            throw new QueryInvalidException("No masking key is configured for this tenant, so masked inputs cannot be"
                    + " searched; set " + EngineDefaults.MASKING_KEY_PROPERTY);
        }
        // One fingerprint per key the tenant holds, so rows written under an earlier key are found too.
        List<String> fingerprints = new ArrayList<>();
        for (MaskingKey key : keys) {
            fingerprints.add(fingerprint(key, query.value()));
        }
        String correlation = query.correlationId() != null
                && io.github.dhluong90.bpm.rules.internal.json.CorrelationIds.isValid(query.correlationId())
                ? query.correlationId() : null;
        int perMinute = Math.max(1, context.masking().searchesPerMinute(tenant));
        return context.withoutDooming(() -> {
            long retryAfter = admit(tenant, caller, perMinute);
            if (retryAfter > 0) {
                // The refusal is audited too: a burst of refused searches is exactly what an auditor must see.
                audit(tenant, caller, query, keys, 0, false, correlation, "RATE_LIMITED");
                throw new io.github.dhluong90.bpm.rules.api.RateLimitedException("At most " + perMinute
                        + " masked-input searches per minute per caller (masking.search.max-per-minute)", retryAfter);
            }
            List<DecisionRecord> rows = log.findByLoggedInput(tenant, query.ruleSet(), query.input(), fingerprints,
                    query.from(), query.to(), query.limit() + 1);
            boolean truncated = rows.size() > query.limit();
            List<LoggedDecision> items = new ArrayList<>();
            for (DecisionRecord row : truncated ? rows.subList(0, query.limit()) : rows) {
                items.add(view(row));
            }
            audit(tenant, caller, query, keys, items.size(), truncated, correlation, "SEARCHED");
            return new DecisionSearchResult(items, truncated);
        });
    }

    /**
     * Writes the audit entry of one search to the outbox, in the search's transaction: who searched which input of
     * which rule set over which period, and how many decisions were found. Never the value or its fingerprint, so the
     * audit trail is not a second place to look the person up.
     */
    /**
     * Counts one search of a caller in a sliding one-minute window on the engine clock.
     *
     * @return 0 when the search may run, else the seconds until it may
     */
    private long admit(String tenant, String caller, int perMinute) {
        Instant now = context.clock().instant();
        Instant windowStart = now.minus(SEARCH_WINDOW);
        if (searches.size() > SEARCH_CALLERS_SWEEP_AT) {
            searches.entrySet().removeIf(entry -> {
                synchronized (entry.getValue()) {
                    return entry.getValue().isEmpty() || !entry.getValue().peekLast().isAfter(windowStart);
                }
            });
        }
        java.util.ArrayDeque<Instant> times = searches.computeIfAbsent(tenant + "\u0000" + caller,
                ignored -> new java.util.ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && !times.peekFirst().isAfter(windowStart)) {
                times.pollFirst();
            }
            if (times.size() >= perMinute) {
                long millis = java.time.Duration.between(now, times.peekFirst().plus(SEARCH_WINDOW)).toMillis();
                return Math.max(1, (millis + 999) / 1000);
            }
            times.addLast(now);
            return 0;
        }
    }

    private void audit(String tenant, String caller, MaskedInputQuery query, List<MaskingKey> keys, int matches,
            boolean truncated, String correlationId, String outcome) {
        Instant now = context.clock().instant();
        String eventId = Ulid.next(now);
        // The full event envelope (DESIGN section 3.3); without a caller's correlation id the event starts its chain.
        ObjectNode payload = EventEnvelope.start(eventId, EVENT_MASKED_SEARCH, tenant, now, correlationId, null)
                .put("outcome", outcome)
                .put("actor", caller)
                .put("ruleSet", query.ruleSet())
                .put("input", query.input())
                .put("from", query.from().toString())
                .put("to", query.to().toString())
                .put("matches", matches)
                .put("truncated", truncated);
        ArrayNode keyIds = payload.putArray("keyIds");
        keys.forEach(key -> keyIds.add(key.keyId()));
        outbox.append(tenant, eventId, EngineValues.write(payload), now);
    }

    private static String fingerprint(MaskingKey key, Object value) {
        try {
            return EngineValues.fingerprint(key, value);
        } catch (RuntimeException e) {
            // An unsupported value type; the message names the type only, never the value.
            throw new QueryInvalidException("value must be a string, number, boolean, money, date or datetime");
        }
    }

    static LoggedDecision view(DecisionRecord row) {
        List<String> matched = new ArrayList<>();
        JsonNode ids = EngineValues.read(row.matchedRuleIdsJson());
        ids.forEach(id -> matched.add(id.stringValue()));
        return new LoggedDecision(row.id(), row.ruleSet(), row.version(), row.hitPolicy(), row.outcome(),
                row.errorCode(), row.maskedInputsJson(), row.outputsJson(), matched, row.callerId(),
                row.correlationId(), row.evalClock(), row.createdAt(), row.durationMicros(), row.pinned(),
                row.onBehalfOf(), row.budgetLimit());
    }

    @Override
    public List<io.github.dhluong90.bpm.rules.api.DeadLetter> deadLetters(int limit) {
        String tenant = context.tenant();
        context.callerWith(PERMISSION_LOG_READ);
        if (limit < 1 || limit > 1_000) {
            throw new QueryInvalidException("limit must be 1 to 1000");
        }
        DecisionQueueAdmin admin = queueAdmin();
        return context.withoutDooming(() -> admin.deadLetters(tenant, limit));
    }

    @Override
    public io.github.dhluong90.bpm.rules.api.DeadLetter requestDeadLetterAction(String decisionId,
            io.github.dhluong90.bpm.rules.api.DeadLetterAction action, String comment, String idempotencyKey) {
        String tenant = context.tenant();
        String caller = context.callerWith(PERMISSION_LOG_OPERATE);
        Objects.requireNonNull(action, "action must not be null");
        String reason = requireReason(comment);
        DecisionQueueAdmin admin = queueAdmin();
        String operation = "dead-letter-request";
        String requestHash = io.github.dhluong90.bpm.rules.internal.json.Hashes.sha256Parts(operation, caller,
                String.valueOf(decisionId), action.name(), reason);
        return context.idempotent(tenant, operation, idempotencyKey, requestHash, 200, () -> {
            var row = admin.deadLetter(tenant, String.valueOf(decisionId))
                    .orElseThrow(() -> new DecisionNotFoundException(String.valueOf(decisionId)));
            String command = action == io.github.dhluong90.bpm.rules.api.DeadLetterAction.RETRY ? "retry" : "resolve";
            Instant now = context.clock().instant();
            boolean done = admin.transition(tenant, row.decisionId(),
                    java.util.Set.of(io.github.dhluong90.bpm.rules.api.DeadLetterState.DEAD),
                    action == io.github.dhluong90.bpm.rules.api.DeadLetterAction.RETRY
                            ? DecisionQueueAdmin.RETRY_REQUESTED : DecisionQueueAdmin.RESOLVE_REQUESTED,
                    caller, reason, now);
            if (!done) {
                throw InvalidStateException.deadLetter(row.decisionId(), row.state(), command);
            }
            auditDeadLetter(tenant, caller, row.decisionId(), command + "-requested", reason, now);
            return admin.deadLetter(tenant, row.decisionId())
                    .orElseThrow(() -> new DecisionNotFoundException(row.decisionId()));
        }, DefaultDecisionLogService::deadLetterJson, DefaultDecisionLogService::deadLetterFrom);
    }

    @Override
    public String decideDeadLetterRequest(String decisionId, boolean approve, String comment, String idempotencyKey) {
        String tenant = context.tenant();
        String caller = context.callerWith(PERMISSION_LOG_APPROVE);
        String reason = approve && (comment == null || comment.isBlank()) ? null : requireReason(comment);
        DecisionQueueAdmin admin = queueAdmin();
        String operation = approve ? "dead-letter-approve" : "dead-letter-reject";
        String requestHash = io.github.dhluong90.bpm.rules.internal.json.Hashes.sha256Parts(operation, caller,
                String.valueOf(decisionId), String.valueOf(reason));
        return context.idempotent(tenant, operation, idempotencyKey, requestHash, 200, () -> {
            var row = admin.deadLetter(tenant, String.valueOf(decisionId))
                    .orElseThrow(() -> new DecisionNotFoundException(String.valueOf(decisionId)));
            var state = row.state();
            if (state == io.github.dhluong90.bpm.rules.api.DeadLetterState.DEAD) {
                throw InvalidStateException.deadLetter(row.decisionId(), state, approve ? "approve" : "reject");
            }
            // The four-eyes rule: whoever asked cannot decide (TR-SEC-04).
            if (caller.equals(row.requestedBy())) {
                throw SelfApprovalForbiddenException.deadLetter(row.decisionId());
            }
            boolean retry = state == io.github.dhluong90.bpm.rules.api.DeadLetterState.RETRY_REQUESTED;
            String kind = !approve ? DecisionQueueAdmin.REQUEST_REJECTED
                    : retry ? DecisionQueueAdmin.RETRY_APPROVED : DecisionQueueAdmin.RESOLVE_APPROVED;
            Instant now = context.clock().instant();
            if (!admin.transition(tenant, row.decisionId(), java.util.Set.of(state), kind, caller, reason, now)) {
                throw InvalidStateException.deadLetter(row.decisionId(), state, approve ? "approve" : "reject");
            }
            auditDeadLetter(tenant, caller, row.decisionId(), kind.toLowerCase(java.util.Locale.ROOT)
                    .replace('_', '-'), reason, now);
            return kind;
        }, kind -> kind, kind -> kind);
    }

    private static String deadLetterJson(io.github.dhluong90.bpm.rules.api.DeadLetter row) {
        ObjectNode json = EngineJson.NODES.objectNode();
        json.put("decisionId", row.decisionId()).put("ruleSet", row.ruleSet()).put("version", row.version())
                .put("queuedAt", row.queuedAt().toString()).put("attempts", row.attempts())
                .put("lastError", row.lastError()).put("state", row.state().name())
                .put("requestedBy", row.requestedBy()).put("requestComment", row.requestComment())
                .put("requestedAt", row.requestedAt() == null ? null : row.requestedAt().toString());
        return EngineValues.write(json);
    }

    private static io.github.dhluong90.bpm.rules.api.DeadLetter deadLetterFrom(String text) {
        JsonNode json = EngineValues.read(text);
        String requestedAt = json.path("requestedAt").isNull() ? null : json.path("requestedAt").asString();
        return new io.github.dhluong90.bpm.rules.api.DeadLetter(json.path("decisionId").asString(),
                json.path("ruleSet").asString(), json.path("version").asInt(),
                Instant.parse(json.path("queuedAt").asString()), json.path("attempts").asInt(),
                json.path("lastError").isNull() ? null : json.path("lastError").asString(),
                io.github.dhluong90.bpm.rules.api.DeadLetterState.valueOf(json.path("state").asString()),
                json.path("requestedBy").isNull() ? null : json.path("requestedBy").asString(),
                json.path("requestComment").isNull() ? null : json.path("requestComment").asString(),
                requestedAt == null ? null : Instant.parse(requestedAt));
    }

    private DecisionQueueAdmin queueAdmin() {
        if (log instanceof DecisionQueueAdmin admin) {
            return admin;
        }
        throw new UnsupportedOperationException("The decision log has no queue: dead letters exist only with "
                + "decision-log.mode: async");
    }

    private static String requireReason(String comment) {
        String text = comment == null ? "" : comment.strip();
        if (text.length() < 3 || text.length() > 500) {
            throw new InputInvalidException("comment is required and must be 3 to 500 characters");
        }
        return text;
    }

    /** One audit entry for every step on a dead letter, in the step's transaction: who, what, which decision, why. */
    private void auditDeadLetter(String tenant, String actor, String decisionId, String step, String reason,
            Instant now) {
        String eventId = Ulid.next(now);
        ObjectNode payload = EventEnvelope.start(eventId, EVENT_DEAD_LETTER, tenant, now, eventId, null)
                .put("step", step)
                .put("actor", actor)
                .put("decisionId", decisionId)
                .put("reason", reason);
        outbox.append(tenant, eventId, EngineValues.write(payload), now);
    }
}
