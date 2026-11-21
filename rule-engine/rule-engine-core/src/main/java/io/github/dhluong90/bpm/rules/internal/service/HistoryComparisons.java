package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.expr.internal.RequestReads;
import io.github.dhluong90.bpm.rules.api.ComparisonRequest;
import io.github.dhluong90.bpm.rules.api.ComparisonResult;
import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.EvaluationErrorException;
import io.github.dhluong90.bpm.rules.api.HistoryTooLargeException;
import io.github.dhluong90.bpm.rules.api.MaskingKey;
import io.github.dhluong90.bpm.rules.api.QueryInvalidException;
import io.github.dhluong90.bpm.rules.api.RuleSetNotFoundException;
import io.github.dhluong90.bpm.rules.api.VersionNotFoundException;
import io.github.dhluong90.bpm.rules.internal.eval.CompiledRuleSet;
import io.github.dhluong90.bpm.rules.internal.json.EngineValues;
import io.github.dhluong90.bpm.rules.internal.json.ExactForm;
import io.github.dhluong90.bpm.rules.internal.model.InputClause;
import io.github.dhluong90.bpm.rules.internal.read.RuleSetReader;
import io.github.dhluong90.bpm.rules.internal.store.DecisionLog;
import io.github.dhluong90.bpm.rules.internal.store.DecisionRecord;
import io.github.dhluong90.bpm.rules.internal.store.RuleStore;
import io.github.dhluong90.bpm.rules.internal.store.StoredVersion;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;

/**
 * What-if comparison of two versions of one rule set over the logged decisions of a past period (RLE-075 to RLE-077,
 * PD-124, DESIGN section 4.2.2). Each logged decision is evaluated again at its logged clock through both versions in
 * memory, page by page, so the period is never held whole. A period over {@code simulation.max-history-decisions} is
 * refused before any decision is read, and a decision whose logged input cannot be evaluated again is counted under its
 * reason and never guessed. Writes nothing. Stateless; one call builds its own totals.
 */
final class HistoryComparisons {

    /** Decisions read per page, so memory is bounded by a page and not by the period. */
    static final int PAGE = 1_000;

    private final EngineContext context;
    private final RuleStore store;
    private final DecisionLog log;
    private final DecisionQuerySettings query;
    private final Function<StoredVersion, CompiledRuleSet> validated;

    HistoryComparisons(EngineContext context, RuleStore store, DecisionLog log, DecisionQuerySettings query,
            Function<StoredVersion, CompiledRuleSet> validated) {
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.log = Objects.requireNonNull(log, "log must not be null");
        this.query = Objects.requireNonNull(query, "query must not be null");
        this.validated = Objects.requireNonNull(validated, "validated must not be null");
    }

    /** Checks the request before anything is read: a missing or reversed period, a long one, equal versions. */
    void requireValid(ComparisonRequest request) {
        if (request == null) {
            throw new QueryInvalidException("the comparison request is required");
        }
        if (request.from() == null) {
            throw new QueryInvalidException("from is required");
        }
        if (request.to() == null) {
            throw new QueryInvalidException("to is required");
        }
        if (!request.to().isAfter(request.from())) {
            throw new QueryInvalidException("to must be after from");
        }
        Duration range = Duration.between(request.from(), request.to());
        if (range.compareTo(query.maxRange()) > 0) {
            throw new QueryInvalidException("from and to may be at most " + query.maxRange().toDays()
                    + " days apart (query.max-range " + query.maxRange() + "), were " + range);
        }
        if (request.base() == request.candidate()) {
            throw new QueryInvalidException("candidate must differ from base: " + request.base()
                    + " is compared with itself");
        }
    }

    ComparisonResult compare(String tenant, String ruleSet, ComparisonRequest request) {
        if (!RuleSetReader.isRuleSetName(ruleSet) || store.findRuleSetId(tenant, ruleSet).isEmpty()) {
            // Another tenant's rule set is answered as one that does not exist.
            throw new RuleSetNotFoundException(ruleSet);
        }
        StoredVersion baseVersion = store.findVersion(tenant, ruleSet, request.base(), false)
                .orElseThrow(() -> new VersionNotFoundException(ruleSet, request.base()));
        StoredVersion candidateVersion = store.findVersion(tenant, ruleSet, request.candidate(), false)
                .orElseThrow(() -> new VersionNotFoundException(ruleSet, request.candidate()));
        // Each version is integrity-checked and compiled before any decision is read.
        CompiledRuleSet base = validated.apply(baseVersion);
        CompiledRuleSet candidate = validated.apply(candidateVersion);

        int cap = context.settings().simulationMaxHistoryDecisions();
        int ceiling = EngineDefaults.SIMULATION_MAX_HISTORY_DECISIONS_CEILING;
        long found = log.countInPeriod(tenant, ruleSet, request.from(), request.to(), ceiling + 1,
                query.statementTimeout());
        if (found > cap) {
            throw new HistoryTooLargeException(ruleSet, Math.min(found, ceiling), found > ceiling, cap);
        }

        Set<String> read = new TreeSet<>(base.inputsRead());
        read.addAll(candidate.inputsRead());
        Totals totals = new Totals();
        RequestReads reads = context.settings().regexLimits().newRequest();
        DecisionLog.Position after = null;
        long seen = 0;
        while (true) {
            List<DecisionRecord> page = log.search(tenant, ruleSet, request.from(), request.to(), after, PAGE,
                    query.statementTimeout());
            for (DecisionRecord row : page) {
                if (++seen > cap) {
                    // Rows arrived between the count and the read; still refused, never sampled.
                    throw new HistoryTooLargeException(ruleSet, seen, false, cap);
                }
                totals.add(row, base, candidate, read, reads, ruleSet);
            }
            if (page.size() < PAGE) {
                break;
            }
            DecisionRecord last = page.getLast();
            after = new DecisionLog.Position(last.createdAt(), last.id());
        }
        return totals.result(ruleSet, request, baseVersion, candidateVersion);
    }

    /** The running counts of one comparison. */
    private static final class Totals {
        private long compared;
        private long changed;
        private final Map<String, Long> baseOutcomes = outcomeCounts();
        private final Map<String, Long> candidateOutcomes = outcomeCounts();
        private final Map<String, Long> skipped = new TreeMap<>();
        private final Map<TotalKey, BigDecimal[]> sums = new TreeMap<>();
        private final Map<TransitionKey, Seen> transitions = new HashMap<>();

        private static Map<String, Long> outcomeCounts() {
            Map<String, Long> counts = new LinkedHashMap<>();
            counts.put("MATCHED", 0L);
            counts.put("NO_MATCH", 0L);
            counts.put("FAILED", 0L);
            return counts;
        }

        void add(DecisionRecord row, CompiledRuleSet base, CompiledRuleSet candidate, Set<String> read,
                RequestReads reads, String ruleSet) {
            JsonNode logged = EngineValues.read(row.maskedInputsJson());
            Set<String> maskedNames = new TreeSet<>();
            boolean missing = false;
            for (String name : read) {
                JsonNode value = logged.get(name);
                if (value == null) {
                    missing = true;
                } else if (masked(name, value, base, candidate)) {
                    maskedNames.add(name);
                }
            }
            // A masked value is never evaluated and never guessed; a logged null is the real input
            if (!maskedNames.isEmpty()) {
                skipped.merge(ComparisonResult.MASKED_INPUT, 1L, Long::sum);
                return;
            }
            if (missing) {
                skipped.merge(ComparisonResult.MISSING_INPUT, 1L, Long::sum);
                return;
            }
            DecisionResult.Outcome before = evaluate(base, logged, row, reads, ruleSet);
            DecisionResult.Outcome after = evaluate(candidate, logged, row, reads, ruleSet);
            compared++;
            baseOutcomes.merge(kind(before), 1L, Long::sum);
            candidateOutcomes.merge(kind(after), 1L, Long::sum);
            Map<String, Object> was = outputs(before);
            Map<String, Object> now = outputs(after);
            addSums(was, now);
            if (DefaultRuleEngine.changed(after, before)) {
                changed++;
                addTransitions(was, now);
            }
        }

        /**
         * A logged value is masked when it is a fingerprint, unless every version that declares the input says
         * {@code mask: false} (then it is plain text that merely looks like one), or when a version declares the input
         * masked and a value was logged for it (as replay of one decision does).
         */
        private static boolean masked(String name, JsonNode value, CompiledRuleSet base, CompiledRuleSet candidate) {
            if (value.isNull()) {
                return false;
            }
            List<InputClause> declared = new ArrayList<>();
            for (CompiledRuleSet rules : List.of(base, candidate)) {
                rules.inputs().stream().filter(clause -> clause.name().equals(name)).findFirst()
                        .ifPresent(declared::add);
            }
            if (declared.stream().anyMatch(InputClause::masked)) {
                return true;
            }
            boolean plainByDeclaration = !declared.isEmpty()
                    && declared.stream().allMatch(clause -> Boolean.FALSE.equals(clause.mask()));
            return value.isString() && MaskingKey.isFingerprint(value.stringValue()) && !plainByDeclaration;
        }

        private static DecisionResult.Outcome evaluate(CompiledRuleSet rules, JsonNode logged, DecisionRecord row,
                RequestReads reads, String ruleSet) {
            Map<String, Object> values = new LinkedHashMap<>();
            DecisionInput all = EngineValues.inputsFromJson(logged);
            for (InputClause clause : rules.inputs()) {
                if (rules.inputsRead().contains(clause.name()) && all.values().containsKey(clause.name())) {
                    values.put(clause.name(), all.get(clause.name()));
                }
            }
            DecisionResult.Outcome outcome = rules.evaluateWithReason(DecisionInput.of(values), row.evalClock(), reads)
                    .outcome();
            if (reads.spent()) {
                // The budget of the whole request is spent: refuse the run, so no total is partial (RLE-077)
                throw new EvaluationErrorException("REGEX_TIMEOUT: the comparison of " + ruleSet + " was refused"
                        + " whole, because it spent the regular expression budget of " + reads.budget()
                        + " characters of one request (evaluation.regex-max-reads-per-request)");
            }
            return outcome;
        }

        private static String kind(DecisionResult.Outcome outcome) {
            return switch (outcome) {
                case DecisionResult.Matched ignored -> "MATCHED";
                case DecisionResult.NoMatch ignored -> "NO_MATCH";
                case DecisionResult.Failed ignored -> "FAILED";
            };
        }

        private static Map<String, Object> outputs(DecisionResult.Outcome outcome) {
            return switch (outcome) {
                case DecisionResult.Matched matched -> matched.outputs();
                case DecisionResult.NoMatch noMatch -> noMatch.outputs();
                case DecisionResult.Failed ignored -> Map.of();
            };
        }

        private void addSums(Map<String, Object> was, Map<String, Object> now) {
            Set<String> names = new TreeSet<>(was.keySet());
            names.addAll(now.keySet());
            for (String name : names) {
                add(name, was.get(name), 0);
                add(name, now.get(name), 1);
            }
        }

        private void add(String name, Object value, int side) {
            switch (value) {
                case Money money -> accumulate(new TotalKey(name, money.currency()), BigDecimal.valueOf(money.minorUnits()), side);
                case BigDecimal number -> accumulate(new TotalKey(name, null), number, side);
                case BigInteger number -> accumulate(new TotalKey(name, null), new BigDecimal(number), side);
                case Long number -> accumulate(new TotalKey(name, null), BigDecimal.valueOf(number), side);
                case Integer number -> accumulate(new TotalKey(name, null), BigDecimal.valueOf(number), side);
                case null, default -> { }
            }
        }

        private void accumulate(TotalKey key, BigDecimal amount, int side) {
            BigDecimal[] pair = sums.computeIfAbsent(key, k -> zeros());
            pair[side] = pair[side].add(amount);
        }

        private static BigDecimal[] zeros() {
            return new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO};
        }

        private void addTransitions(Map<String, Object> was, Map<String, Object> now) {
            Set<String> names = new TreeSet<>(was.keySet());
            names.addAll(now.keySet());
            for (String name : names) {
                Object from = was.get(name);
                Object to = now.get(name);
                String fromForm = form(from);
                String toForm = form(to);
                if (!fromForm.equals(toForm)) {
                    transitions.computeIfAbsent(new TransitionKey(name, fromForm, toForm),
                            k -> new Seen(from, to)).count++;
                }
            }
        }

        private static String form(Object value) {
            return ExactForm.of(DecisionInput.of(Collections.singletonMap("v", value)));
        }

        ComparisonResult result(String ruleSet, ComparisonRequest request, StoredVersion base, StoredVersion candidate) {
            List<ComparisonResult.Total> totals = new ArrayList<>();
            sums.forEach((key, pair) -> totals.add(new ComparisonResult.Total(key.output(), key.currency(),
                    pair[0], pair[1], pair[1].subtract(pair[0]))));
            List<Map.Entry<TransitionKey, Seen>> ordered = new ArrayList<>(transitions.entrySet());
            ordered.sort(Comparator.<Map.Entry<TransitionKey, Seen>>comparingLong(e -> -e.getValue().count)
                    .thenComparing(e -> e.getKey().output())
                    .thenComparing(e -> e.getKey().fromForm())
                    .thenComparing(e -> e.getKey().toForm()));
            List<ComparisonResult.Transition> top = new ArrayList<>();
            for (Map.Entry<TransitionKey, Seen> entry : ordered) {
                if (top.size() == ComparisonResult.MAX_TRANSITIONS) {
                    break;
                }
                top.add(new ComparisonResult.Transition(entry.getKey().output(), entry.getValue().from,
                        entry.getValue().to, entry.getValue().count));
            }
            return new ComparisonResult(ruleSet, request.base(), request.candidate(), request.from(), request.to(),
                    base.canonicalHash(), candidate.canonicalHash(), compared, changed, baseOutcomes, candidateOutcomes,
                    totals, top, skipped);
        }
    }

    /** One output of one currency, or of none for a number. */
    private record TotalKey(String output, String currency) implements Comparable<TotalKey> {
        @Override
        public int compareTo(TotalKey other) {
            int byOutput = output.compareTo(other.output);
            if (byOutput != 0) {
                return byOutput;
            }
            return String.valueOf(currency).compareTo(String.valueOf(other.currency));
        }
    }

    private record TransitionKey(String output, String fromForm, String toForm) {
    }

    /** The first values seen for a transition, and how many decisions had it. */
    private static final class Seen {
        final Object from;
        final Object to;
        long count;

        Seen(Object from, Object to) {
            this.from = from;
            this.to = to;
        }
    }
}
