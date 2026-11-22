package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.HistorySimulation;
import io.github.dhluong90.bpm.rules.api.HistorySimulationResult;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.RuleReadings.OutcomeReading;
import io.github.dhluong90.bpm.rules.api.RuleSetNotFoundException;
import io.github.dhluong90.bpm.rules.api.SimulationReport;
import io.github.dhluong90.bpm.rules.api.SimulationReportNotFoundException;
import io.github.dhluong90.bpm.rules.api.SimulationReportRequest;
import io.github.dhluong90.bpm.rules.api.SimulationReportService;
import io.github.dhluong90.bpm.rules.api.SimulationResult;
import io.github.dhluong90.bpm.rules.api.VersionNotFoundException;
import io.github.dhluong90.bpm.rules.internal.json.EngineValues;
import io.github.dhluong90.bpm.rules.internal.json.Hashes;
import io.github.dhluong90.bpm.rules.internal.json.SimulationReportJson;
import io.github.dhluong90.bpm.rules.internal.json.Ulid;
import io.github.dhluong90.bpm.rules.internal.read.RuleSetReader;
import io.github.dhluong90.bpm.rules.internal.store.RuleStore;
import io.github.dhluong90.bpm.rules.internal.store.SimulationReportStore;
import io.github.dhluong90.bpm.rules.internal.store.StoredVersion;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Runs the simulation, reads the changed items in plain language and stores the result once, bound to the version's
 * canonical hash (PD-115, RLE-199, RLE-200). The report keeps counts and at most {@link SimulationReport#ROWS_MAX}
 * changed or failed rows; it never keeps an input, a sample label or a masked value, because it is built from the
 * outcomes only. Thread-safe and stateless.
 */
public final class DefaultSimulationReportService implements SimulationReportService {

    static final String PERMISSION_SIMULATE = "rules:simulate";
    static final String PERMISSION_READ = "rules:read";
    static final String PERMISSION_LOG_READ = "rules:log:read";

    private static final String CHANGED = "CHANGED";
    private static final String FAILED = "FAILED";
    private static final String UNCHANGED = "UNCHANGED";
    private static final String NOT_REPLAYABLE = "NOT_REPLAYABLE";

    private final EngineContext context;
    private final RuleStore store;
    private final DefaultRuleEngine engine;
    private final ReadingBuilder reader;

    /**
     * Creates the service.
     *
     * @param context shared engine context, holding the report store
     * @param store rule set storage
     * @param engine the engine that runs the simulations
     * @param catalogue the message catalogue the rows are read in
     */
    DefaultSimulationReportService(EngineContext context, RuleStore store, DefaultRuleEngine engine,
            CatalogueExplainer catalogue) {
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.reader = new ReadingBuilder(Objects.requireNonNull(catalogue, "catalogue must not be null"));
    }

    @Override
    public SimulationReport create(String ruleSet, int version, SimulationReportRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String tenant = context.tenant();
        // Permission first: a caller without rules:simulate makes the engine read and evaluate nothing.
        String actor = context.callerWith(PERMISSION_SIMULATE);
        String requestHash = Hashes.sha256Parts("simulation-report", actor, String.valueOf(ruleSet),
                Integer.toString(version), request.source().name(), String.valueOf(request.samples()),
                String.valueOf(request.from()), String.valueOf(request.to()), String.valueOf(request.limit()));
        return context.idempotent(tenant, "simulation-report", request.idempotencyKey(), requestHash, 201,
                () -> run(tenant, actor, ruleSet, version, request),
                report -> EngineValues.write(SimulationReportJson.write(report)),
                json -> visible(SimulationReportJson.read(json)));
    }

    private SimulationReport run(String tenant, String actor, String ruleSet, int number,
            SimulationReportRequest request) {
        StoredVersion version = existing(tenant, ruleSet, number);
        Instant now = context.clock().instant();
        ReadingBuilder.Table simulated = table(tenant, version);
        List<Item> items = new ArrayList<>();
        boolean truncated = false;
        switch (request.source()) {
            case SAMPLES -> {
                if (request.from() != null || request.to() != null || request.limit() != null) {
                    throw new InputInvalidException("A report over samples takes no from, to or limit");
                }
                SimulationResult result = engine.simulate(ruleSet, number, request.samples());
                ReadingBuilder.Table baseline = result.baselineVersion() == null ? null
                        : table(tenant, existing(tenant, ruleSet, result.baselineVersion()));
                for (SimulationResult.Sample sample : result.samples()) {
                    boolean failed = sample.simulated() instanceof DecisionResult.Failed;
                    String category = failed ? FAILED : sample.changed() ? CHANGED : UNCHANGED;
                    boolean read = !UNCHANGED.equals(category);
                    items.add(new Item(category, "S" + (sample.index() + 1), null,
                            read && sample.baseline() != null && baseline != null
                                    ? reader.outcome(sample.baseline(), baseline) : null,
                            read ? reader.outcome(sample.simulated(), simulated) : null));
                }
            }
            case REPLAY -> {
                if (!request.samples().isEmpty()) {
                    throw new InputInvalidException("A report over a replay takes no samples");
                }
                HistorySimulationResult result = engine.replayForReport(ruleSet, number,
                        new HistorySimulation(request.from(), request.to(), request.limit()));
                truncated = result.truncated();
                Map<Integer, Optional<ReadingBuilder.Table>> logged = new HashMap<>();
                for (HistorySimulationResult.Item item : result.items()) {
                    if (!item.replayable() || item.simulated() == null || item.logged() == null) {
                        items.add(new Item(NOT_REPLAYABLE, null, item.decisionId(), null, null));
                        continue;
                    }
                    boolean failed = item.simulated() instanceof DecisionResult.Failed;
                    String category = failed ? FAILED : item.changed() ? CHANGED : UNCHANGED;
                    OutcomeReading before = null;
                    OutcomeReading after = null;
                    if (!UNCHANGED.equals(category)) {
                        Optional<ReadingBuilder.Table> loggedTable = logged.computeIfAbsent(item.loggedVersion(),
                                v -> loggedTable(tenant, ruleSet, v));
                        before = loggedTable.map(t -> reader.outcome(item.logged(), t)).orElse(null);
                        after = reader.outcome(item.simulated(), simulated);
                    }
                    items.add(new Item(category, null, item.decisionId(), before, after));
                }
            }
            default -> throw new IllegalStateException("Unknown source " + request.source());
        }
        int changed = count(items, CHANGED);
        int failed = count(items, FAILED);
        int notReplayable = count(items, NOT_REPLAYABLE);
        int unchanged = count(items, UNCHANGED);
        List<SimulationReport.Row> shown = new ArrayList<>();
        boolean rowsTruncated = false;
        for (Item item : items) {
            if (!CHANGED.equals(item.category) && !FAILED.equals(item.category)) {
                continue;
            }
            if (shown.size() == SimulationReport.ROWS_MAX) {
                rowsTruncated = true;
                break;
            }
            shown.add(new SimulationReport.Row(item.reference, item.decisionId, item.category, item.before,
                    item.after));
        }
        String id = Ulid.next(now);
        List<String> languages = reader.languages();
        store(tenant, id, version, actor, now, request, truncated, items.size(), changed, unchanged, failed,
                notReplayable, languages, shown, rowsTruncated);
        SimulationReport.Scope scope = new SimulationReport.Scope(request.source(), request.from(), request.to(),
                request.limit(), truncated);
        return visible(new SimulationReport(id, ruleSet, number, version.canonicalHash(),
                SimulationReport.Status.CURRENT, false, actor, now, scope,
                new SimulationReport.Counts(items.size(), changed, unchanged, failed, notReplayable), languages, shown,
                rowsTruncated));
    }

    private void store(String tenant, String id, StoredVersion version, String actor, Instant now,
            SimulationReportRequest request, boolean truncated, int total, int changed, int unchanged, int failed,
            int notReplayable, List<String> languages, List<SimulationReport.Row> rows, boolean rowsTruncated) {
        context.reports().insert(new SimulationReportStore.Stored(tenant, id, version.ruleSet(), version.version(),
                version.canonicalHash(), actor, now, request.source().name(), request.from(), request.to(),
                request.limit(), truncated, total, changed, unchanged, failed, notReplayable,
                String.join(",", languages), EngineValues.write(SimulationReportJson.writeRows(rows)),
                rowsTruncated));
    }

    @Override
    public SimulationReport get(String ruleSet, int version, String reportId) {
        String tenant = context.tenant();
        context.callerWith(PERMISSION_READ);
        return context.withoutDooming(() -> {
            StoredVersion current = existing(tenant, ruleSet, version);
            SimulationReportStore.Stored stored = context.reports().find(tenant, ruleSet, version,
                    String.valueOf(reportId)).orElseThrow(() -> new SimulationReportNotFoundException(ruleSet,
                            version, String.valueOf(reportId)));
            return visible(report(stored, current, attachedId(tenant, ruleSet, version), true));
        });
    }

    @Override
    public List<SimulationReport> list(String ruleSet, int version) {
        String tenant = context.tenant();
        context.callerWith(PERMISSION_READ);
        return context.withoutDooming(() -> {
            StoredVersion current = existing(tenant, ruleSet, version);
            String attached = attachedId(tenant, ruleSet, version);
            return context.reports().list(tenant, ruleSet, version, SimulationReportStore.LIST_MAX).stream()
                    .map(stored -> report(stored, current, attached, false)).toList();
        });
    }

    private String attachedId(String tenant, String ruleSet, int version) {
        return context.reports().attachedReport(tenant, ruleSet, version).orElse(null);
    }

    private SimulationReport report(SimulationReportStore.Stored stored, StoredVersion current, String attachedId,
            boolean withRows) {
        SimulationReport.Status status = stored.canonicalHash().equals(current.canonicalHash())
                ? SimulationReport.Status.CURRENT : SimulationReport.Status.OUT_OF_DATE;
        return new SimulationReport(stored.id(), stored.ruleSet(), stored.version(), stored.canonicalHash(), status,
                stored.id().equals(attachedId), stored.createdBy(), stored.createdAt(),
                new SimulationReport.Scope(SimulationReportRequest.Source.valueOf(stored.source()),
                        stored.periodFrom(), stored.periodTo(), stored.periodLimit(), stored.truncated()),
                new SimulationReport.Counts(stored.total(), stored.changed(), stored.unchanged(), stored.failed(),
                        stored.notReplayable()),
                List.of(stored.languages().split(",")),
                withRows ? SimulationReportJson.readRows(stored.rowsJson()) : List.of(), stored.rowsTruncated());
    }

    /** Gives the decision ids only to a caller who may read the decision log (PD-115). */
    private SimulationReport visible(SimulationReport report) {
        if (context.holds(PERMISSION_LOG_READ)) {
            return report;
        }
        return new SimulationReport(report.id(), report.ruleSet(), report.version(), report.canonicalHash(),
                report.status(), report.attached(), report.createdBy(), report.createdAt(), report.scope(),
                report.counts(), report.languages(),
                report.rows().stream().map(SimulationReport.Row::withoutDecisionId).toList(),
                report.rowsTruncated());
    }

    private StoredVersion existing(String tenant, String ruleSet, int version) {
        if (!RuleSetReader.isRuleSetName(ruleSet)) {
            throw new VersionNotFoundException("(invalid name)", version);
        }
        return store.findVersion(tenant, ruleSet, version, false).orElseThrow(() ->
                store.findRuleSetId(tenant, ruleSet).isPresent()
                        ? new VersionNotFoundException(ruleSet, version)
                        : new RuleSetNotFoundException(ruleSet));
    }

    private ReadingBuilder.Table table(String tenant, StoredVersion version) {
        return reader.table(version.ruleSet(), version.version(), version.canonicalJson(),
                context.masking().maskByDefault(tenant));
    }

    private Optional<ReadingBuilder.Table> loggedTable(String tenant, String ruleSet, int version) {
        try {
            return Optional.of(table(tenant, existing(tenant, ruleSet, version)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static int count(List<Item> items, String category) {
        return (int) items.stream().filter(item -> item.category.equals(category)).count();
    }

    /** One item of the run before it is counted; the readings are present for changed and failed items only. */
    private record Item(String category, String reference, String decisionId, OutcomeReading before,
            OutcomeReading after) {
    }
}
