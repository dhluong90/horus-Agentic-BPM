package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.rules.api.CorrelationSearch;
import io.github.dhluong90.bpm.rules.api.DecisionLogEntry;
import io.github.dhluong90.bpm.rules.api.DecisionLogQuery;
import io.github.dhluong90.bpm.rules.api.DecisionSearch;
import io.github.dhluong90.bpm.rules.api.InputSearch;
import io.github.dhluong90.bpm.rules.api.Page;
import io.github.dhluong90.bpm.rules.api.PageRequest;
import io.github.dhluong90.bpm.rules.api.QueryInvalidException;
import io.github.dhluong90.bpm.rules.internal.json.CorrelationIds;
import io.github.dhluong90.bpm.rules.internal.json.EngineValues;
import io.github.dhluong90.bpm.rules.internal.json.Hashes;
import io.github.dhluong90.bpm.rules.internal.read.RuleSetReader;
import io.github.dhluong90.bpm.rules.internal.store.DecisionLog;
import io.github.dhluong90.bpm.rules.internal.store.DecisionRecord;
import io.github.dhluong90.bpm.rules.internal.store.RuleStore;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.function.Function;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Searches the decision log by rule set and period (RLE-029, ADR-0015), by the host's correlation id across rule sets
 * (RLE-070) and by the exact value of a plain input (RLE-071). Read-only: one bounded statement per page,
 * filtered by the caller's tenant first. The cursor is signed for the tenant, the rule set and the period, so it can
 * neither be edited nor reused for another search. Thread-safe.
 */
final class DefaultDecisionLogQuery implements DecisionLogQuery {

    static final String PERMISSION_LOG_READ = "rules:log:read";
    static final String CURSOR_DECISIONS = "decisions";
    static final String CURSOR_BY_CORRELATION = "decisions-by-correlation";
    static final String CURSOR_BY_INPUT = "decisions-by-input";
    private static final Pattern INPUT_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");

    private final EngineContext context;
    private final DecisionLog log;
    private final RuleStore store;
    private final CursorKeys cursorKeys;
    private final DecisionQuerySettings settings;

    DefaultDecisionLogQuery(EngineContext context, DecisionLog log, RuleStore store, CursorKeys cursorKeys,
            DecisionQuerySettings settings) {
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.log = Objects.requireNonNull(log, "log must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.cursorKeys = Objects.requireNonNull(cursorKeys, "cursorKeys must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
    }

    @Override
    public Page<DecisionLogEntry> search(DecisionSearch search, PageRequest page) {
        String tenant = context.tenant();
        context.callerWith(PERMISSION_LOG_READ);
        DecisionSearch query = requireValid(search);
        String scope = query.ruleSet() + "|" + query.from() + "|" + query.to();
        return paged(tenant, scope, CURSOR_DECISIONS, page, (after, limit) -> context.transactions()
                .inReadOnlyTransaction(() -> log.search(tenant, query.ruleSet(), query.from(), query.to(), after,
                        limit, settings.statementTimeout())));
    }

    @Override
    public Page<DecisionLogEntry> searchByCorrelationId(CorrelationSearch search, PageRequest page) {
        String tenant = context.tenant();
        context.callerWith(PERMISSION_LOG_READ);
        if (search == null || search.correlationId() == null || search.correlationId().isEmpty()) {
            throw new QueryInvalidException("correlationId is required");
        }
        if (!CorrelationIds.isValid(search.correlationId())) {
            throw new QueryInvalidException("correlationId must be 1 to " + EngineDefaults.CORRELATION_ID_MAX_LENGTH
                    + " characters of A-Z, a-z, 0-9, '.', '_', ':' or '-'");
        }
        if (search.ruleSet() != null && !RuleSetReader.isRuleSetName(search.ruleSet())) {
            throw new QueryInvalidException("ruleSet is not a rule set name");
        }
        requirePeriod(search.from(), search.to());
        String scope = "correlation|" + search.correlationId() + "|" + search.ruleSet() + "|" + search.from() + "|"
                + search.to();
        return paged(tenant, scope, CURSOR_BY_CORRELATION, page, (after, limit) -> context.transactions()
                .inReadOnlyTransaction(() -> log.searchByCorrelationId(tenant, search.correlationId(),
                        search.ruleSet(), search.from(), search.to(), after, limit, settings.statementTimeout())));
    }

    @Override
    public Page<DecisionLogEntry> searchByInput(InputSearch search, PageRequest page) {
        String tenant = context.tenant();
        context.callerWith(PERMISSION_LOG_READ);
        if (search == null || search.ruleSet() == null || search.ruleSet().isBlank()
                || !RuleSetReader.isRuleSetName(search.ruleSet())) {
            throw new QueryInvalidException("ruleSet is required and must be a rule set name");
        }
        if (search.input() == null || !INPUT_NAME.matcher(search.input()).matches()) {
            throw new QueryInvalidException("input is required and must be an input name");
        }
        if (search.value() == null || search.value() instanceof List<?>) {
            throw new QueryInvalidException("value is required and must be a single value");
        }
        if (search.value() instanceof String text && text.length() > context.settings().maxStringLength()) {
            throw new QueryInvalidException("value is longer than " + context.settings().maxStringLength()
                    + " characters");
        }
        requirePeriod(search.from(), search.to());
        // The cursor is bound to the search value by a hash, so it cannot be reused for another value; the value is
        // never part of the cursor itself.
        String valueText;
        try {
            valueText = EngineValues.write(EngineValues.value(search.value()));
        } catch (RuntimeException e) {
            throw new QueryInvalidException("value must be a string, number, boolean, money, date or datetime");
        }
        String scope = "input|" + search.ruleSet() + "|" + search.input() + "|" + Hashes.sha256(valueText) + "|"
                + search.from() + "|" + search.to();
        return paged(tenant, scope, CURSOR_BY_INPUT, page, (after, limit) -> context.transactions()
                .inReadOnlyTransaction(() -> {
                    // Declared, plain and of this type, read inside the tenant: refused, never answered with no rows,
                    // so the search cannot be used to test a masked value (RLE-071).
                    String valueJson = EngineValues.write(coerced(search, declarations(tenant, search)));
                    return log.searchByPlainInput(tenant, search.ruleSet(), search.input(), valueJson, search.from(),
                            search.to(), after, limit, settings.statementTimeout());
                }));
    }

    /** One declaration of the searched input in one version of the rule set. */
    private record Declaration(String type, Boolean mask) {
    }

    private List<Declaration> declarations(String tenant, InputSearch search) {
        boolean maskByDefault = context.masking().maskByDefault(tenant);
        List<Declaration> found = new ArrayList<>();
        for (String inputs : store.declaredInputs(tenant, search.ruleSet())) {
            for (JsonNode input : EngineValues.read(inputs)) {
                if (!search.input().equals(input.path("name").asString())) {
                    continue;
                }
                JsonNode mask = input.get("mask");
                boolean declaredMask = mask != null && mask.isBoolean();
                boolean masked = declaredMask ? mask.booleanValue() : maskByDefault;
                if (masked) {
                    throw new QueryInvalidException("input " + search.input() + " is masked; use POST"
                            + " /decisions:find-by-masked-input (permission rules:masked:search) to search it");
                }
                found.add(new Declaration(input.path("type").asString(), declaredMask ? mask.booleanValue() : null));
            }
        }
        if (found.isEmpty()) {
            throw new QueryInvalidException("input " + search.input() + " is not declared by any version of "
                    + search.ruleSet());
        }
        return found;
    }

    /** The value in the form the input is logged in, or {@code QUERY_INVALID} naming {@code value}. */
    private static JsonNode coerced(InputSearch search, List<Declaration> declarations) {
        Set<String> types = new LinkedHashSet<>();
        for (Declaration declaration : declarations) {
            types.add(declaration.type());
        }
        for (String type : types) {
            JsonNode node = coerce(type, search.value());
            if (node != null) {
                return node;
            }
        }
        boolean onlyLists = types.stream().allMatch(type -> type.startsWith("list<"));
        throw new QueryInvalidException(onlyLists
                ? "value: input " + search.input() + " is a list and cannot be searched by value"
                : "value must be of the type input " + search.input() + " declares: " + String.join(" or ", types));
    }

    /** The logged JSON form of a value of a declared type, or null when the value has another type. */
    private static JsonNode coerce(String type, Object value) {
        try {
            return switch (type) {
                case "string" -> value instanceof String text ? EngineValues.value(text) : null;
                case "boolean" -> value instanceof Boolean flag ? EngineValues.value(flag) : null;
                case "integer" -> switch (value) {
                    case Long number -> EngineValues.value(number);
                    case Integer number -> EngineValues.value(number);
                    case BigInteger number -> EngineValues.value(number);
                    case BigDecimal number when number.stripTrailingZeros().scale() <= 0 ->
                            EngineValues.value(number.toBigIntegerExact());
                    default -> null;
                };
                case "decimal" -> value instanceof Long || value instanceof Integer || value instanceof BigInteger
                        || value instanceof BigDecimal ? EngineValues.value(value) : null;
                case "money" -> value instanceof Money ? EngineValues.value(value) : null;
                case "date" -> switch (value) {
                    case LocalDate date -> EngineValues.value(date);
                    case String text -> EngineValues.value(LocalDate.parse(text));
                    default -> null;
                };
                case "datetime" -> switch (value) {
                    case Instant instant -> EngineValues.value(instant);
                    case String text -> EngineValues.value(OffsetDateTime.parse(text).toInstant());
                    default -> null;
                };
                default -> null;
            };
        } catch (DateTimeParseException | ArithmeticException e) {
            return null;
        }
    }

    private Page<DecisionLogEntry> paged(String tenant, String scope, String list, PageRequest page,
            java.util.function.BiFunction<DecisionLog.Position, Integer, List<DecisionRecord>> read) {
        PageRequest request = page == null ? PageRequest.first() : page;
        int limit = limit(request);
        // Verified in memory before any transaction: a forged cursor never reaches the database (RLE-039).
        DecisionLog.Position after = Cursors.decisionPosition(cursorKeys.verify(tenant, scope, list, request.cursor()));
        List<DecisionRecord> rows = read.apply(after, limit + 1);
        List<DecisionLogEntry> entries = new ArrayList<>(Math.min(rows.size(), limit));
        for (DecisionRecord row : rows.subList(0, Math.min(rows.size(), limit))) {
            entries.add(entry(row));
        }
        if (rows.size() <= limit) {
            return new Page<>(entries, null);
        }
        DecisionRecord last = rows.get(limit - 1);
        return new Page<>(entries, cursorKeys.sign(tenant, scope, list,
                Cursors.decisionKey(last.createdAt(), last.id())));
    }

    private DecisionSearch requireValid(DecisionSearch search) {
        if (search == null || search.ruleSet() == null || search.ruleSet().isBlank()) {
            throw new QueryInvalidException("ruleSet is required");
        }
        if (!RuleSetReader.isRuleSetName(search.ruleSet())) {
            throw new QueryInvalidException("ruleSet is not a rule set name");
        }
        requirePeriod(search.from(), search.to());
        return search;
    }

    private void requirePeriod(Instant from, Instant to) {
        if (from == null) {
            throw new QueryInvalidException("from is required");
        }
        if (to == null) {
            throw new QueryInvalidException("to is required");
        }
        if (!to.isAfter(from)) {
            throw new QueryInvalidException("to must be after from");
        }
        Duration range = Duration.between(from, to);
        if (range.compareTo(settings.maxRange()) > 0) {
            throw new QueryInvalidException("from and to may be at most " + settings.maxRange().toDays()
                    + " days apart (query.max-range " + settings.maxRange() + "), were " + range);
        }
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
                row.durationMicros(), row.pinned(), row.onBehalfOf(), row.budgetLimit());
    }
}
