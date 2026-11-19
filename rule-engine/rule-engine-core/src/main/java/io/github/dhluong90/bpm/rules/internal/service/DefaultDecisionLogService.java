package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.api.DecisionLogService;
import io.github.dhluong90.bpm.rules.api.DecisionNotFoundException;
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

    private static final Pattern DECISION_ID = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{26}$");
    private static final Pattern INPUT_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");

    /** Event type of the audit entry every masked-input search writes. */
    static final String EVENT_MASKED_SEARCH = "rules.decision-log.masked-input-searched.v1";

    private final EngineContext context;
    private final DecisionLog log;
    private final EventOutbox outbox;

    /**
     * Creates the service.
     *
     * @param context shared engine context
     * @param log the decision log
     * @param outbox where the audit entry of each masked-input search is written
     */
    public DefaultDecisionLogService(EngineContext context, DecisionLog log, EventOutbox outbox) {
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.log = Objects.requireNonNull(log, "log must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
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
        String caller = context.callerWith(PERMISSION_LOG_READ);
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
        return context.withoutDooming(() -> {
            List<DecisionRecord> rows = log.findByLoggedInput(tenant, query.ruleSet(), query.input(), fingerprints,
                    query.from(), query.to(), query.limit() + 1);
            boolean truncated = rows.size() > query.limit();
            List<LoggedDecision> items = new ArrayList<>();
            for (DecisionRecord row : truncated ? rows.subList(0, query.limit()) : rows) {
                items.add(view(row));
            }
            audit(tenant, caller, query, keys, items.size(), truncated, correlation);
            return new DecisionSearchResult(items, truncated);
        });
    }

    /**
     * Writes the audit entry of one search to the outbox, in the search's transaction: who searched which input of
     * which rule set over which period, and how many decisions were found. Never the value or its fingerprint, so the
     * audit trail is not a second place to look the person up.
     */
    private void audit(String tenant, String caller, MaskedInputQuery query, List<MaskingKey> keys, int matches,
            boolean truncated, String correlationId) {
        Instant now = context.clock().instant();
        ObjectNode payload = EngineJson.NODES.objectNode()
                .put("eventType", EVENT_MASKED_SEARCH)
                .put("tenantId", tenant)
                .put("actor", caller)
                .put("occurredAt", now.toString())
                .put("ruleSet", query.ruleSet())
                .put("input", query.input())
                .put("from", query.from().toString())
                .put("to", query.to().toString())
                .put("matches", matches)
                .put("truncated", truncated);
        ArrayNode keyIds = payload.putArray("keyIds");
        keys.forEach(key -> keyIds.add(key.keyId()));
        payload.put("correlationId", correlationId);
        outbox.append(tenant, Ulid.next(now), EngineValues.write(payload), now);
    }

    private static String fingerprint(MaskingKey key, Object value) {
        try {
            return EngineValues.fingerprint(key, value);
        } catch (RuntimeException e) {
            // An unsupported value type; the message names the type only, never the value.
            throw new QueryInvalidException("value must be a string, number, boolean, money, date or datetime");
        }
    }

    private static LoggedDecision view(DecisionRecord row) {
        List<String> matched = new ArrayList<>();
        JsonNode ids = EngineValues.read(row.matchedRuleIdsJson());
        ids.forEach(id -> matched.add(id.stringValue()));
        return new LoggedDecision(row.id(), row.ruleSet(), row.version(), row.hitPolicy(), row.outcome(),
                row.errorCode(), row.maskedInputsJson(), row.outputsJson(), matched, row.callerId(),
                row.correlationId(), row.createdAt(), row.durationMicros());
    }
}
