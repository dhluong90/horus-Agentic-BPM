package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.api.DecisionLogEntry;
import io.github.dhluong90.bpm.rules.api.DecisionLogQuery;
import io.github.dhluong90.bpm.rules.api.DecisionSearch;
import io.github.dhluong90.bpm.rules.api.Page;
import io.github.dhluong90.bpm.rules.api.PageRequest;
import io.github.dhluong90.bpm.rules.api.QueryInvalidException;
import io.github.dhluong90.bpm.rules.internal.json.EngineValues;
import io.github.dhluong90.bpm.rules.internal.read.RuleSetReader;
import io.github.dhluong90.bpm.rules.internal.store.DecisionLog;
import io.github.dhluong90.bpm.rules.internal.store.DecisionRecord;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Searches the decision log by rule set and period (RLE-029, ADR-0015). Read-only: one bounded statement per page,
 * filtered by the caller's tenant first. The cursor is signed for the tenant, the rule set and the period, so it can
 * neither be edited nor reused for another search. Thread-safe.
 */
final class DefaultDecisionLogQuery implements DecisionLogQuery {

    static final String PERMISSION_LOG_READ = "rules:log:read";
    static final String CURSOR_DECISIONS = "decisions";

    private final EngineContext context;
    private final DecisionLog log;
    private final CursorKeys cursorKeys;
    private final DecisionQuerySettings settings;

    DefaultDecisionLogQuery(EngineContext context, DecisionLog log, CursorKeys cursorKeys,
            DecisionQuerySettings settings) {
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.log = Objects.requireNonNull(log, "log must not be null");
        this.cursorKeys = Objects.requireNonNull(cursorKeys, "cursorKeys must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
    }

    @Override
    public Page<DecisionLogEntry> search(DecisionSearch search, PageRequest page) {
        String tenant = context.tenant();
        context.callerWith(PERMISSION_LOG_READ);
        DecisionSearch query = requireValid(search);
        PageRequest request = page == null ? PageRequest.first() : page;
        int limit = limit(request);
        String scope = query.ruleSet() + "|" + query.from() + "|" + query.to();
        // Verified in memory before any transaction: a forged cursor never reaches the database (RLE-039).
        DecisionLog.Position after = Cursors.decisionPosition(
                cursorKeys.verify(tenant, scope, CURSOR_DECISIONS, request.cursor()));
        List<DecisionRecord> rows = context.transactions().inReadOnlyTransaction(() -> log.search(tenant,
                query.ruleSet(), query.from(), query.to(), after, limit + 1, settings.statementTimeout()));
        List<DecisionLogEntry> entries = new ArrayList<>(Math.min(rows.size(), limit));
        for (DecisionRecord row : rows.subList(0, Math.min(rows.size(), limit))) {
            entries.add(entry(row));
        }
        if (rows.size() <= limit) {
            return new Page<>(entries, null);
        }
        DecisionRecord last = rows.get(limit - 1);
        return new Page<>(entries, cursorKeys.sign(tenant, scope, CURSOR_DECISIONS,
                Cursors.decisionKey(last.createdAt(), last.id())));
    }

    private DecisionSearch requireValid(DecisionSearch search) {
        if (search == null || search.ruleSet() == null || search.ruleSet().isBlank()) {
            throw new QueryInvalidException("ruleSet is required");
        }
        if (!RuleSetReader.isRuleSetName(search.ruleSet())) {
            throw new QueryInvalidException("ruleSet is not a rule set name");
        }
        if (search.from() == null) {
            throw new QueryInvalidException("from is required");
        }
        if (search.to() == null) {
            throw new QueryInvalidException("to is required");
        }
        if (!search.to().isAfter(search.from())) {
            throw new QueryInvalidException("to must be after from");
        }
        Duration range = Duration.between(search.from(), search.to());
        if (range.compareTo(settings.maxRange()) > 0) {
            throw new QueryInvalidException("from and to may be at most " + settings.maxRange().toDays()
                    + " days apart (query.max-range " + settings.maxRange() + "), were " + range);
        }
        return search;
    }

    private int limit(PageRequest request) {
        int max = context.settings().queryMaxPageSize();
        if (request.limit() == null) {
            return max;
        }
        if (request.limit() < 1 || request.limit() > max) {
            throw new QueryInvalidException("limit must be 1 to " + max + ", was " + request.limit());
        }
        return request.limit();
    }

    private static DecisionLogEntry entry(DecisionRecord row) {
        List<String> matched = new ArrayList<>();
        EngineValues.read(row.matchedRuleIdsJson()).forEach(id -> matched.add(id.stringValue()));
        DecisionLogEntry.Outcome outcome = switch (row.outcome()) {
            case "OK" -> matched.isEmpty() ? DecisionLogEntry.Outcome.NO_MATCH : DecisionLogEntry.Outcome.MATCHED;
            case "UNIQUE_HIT_VIOLATION" -> DecisionLogEntry.Outcome.UNIQUE_HIT_VIOLATION;
            default -> DecisionLogEntry.Outcome.EVALUATION_ERROR;
        };
        return new DecisionLogEntry(row.id(), row.ruleSet(), row.version(), row.hitPolicy(), outcome, matched,
                EngineValues.loggedValues(row.maskedInputsJson()), EngineValues.loggedValues(row.outputsJson()),
                row.callerId(), row.correlationId(), row.evalClock(), row.createdAt(), row.createdAt(),
                row.durationMicros(), row.pinned(), row.onBehalfOf());
    }
}
