package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.api.DecisionNotFoundException;
import io.github.dhluong90.bpm.rules.api.EvidenceExport;
import io.github.dhluong90.bpm.rules.api.EvidenceFormat;
import io.github.dhluong90.bpm.rules.api.EvidenceRequest;
import io.github.dhluong90.bpm.rules.api.EvidenceTooLargeException;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.LifecycleAction;
import io.github.dhluong90.bpm.rules.api.LoggedDecision;
import io.github.dhluong90.bpm.rules.api.PageRequest;
import io.github.dhluong90.bpm.rules.api.QueryInvalidException;
import io.github.dhluong90.bpm.rules.api.RuleSetHistory;
import io.github.dhluong90.bpm.rules.api.RuleSetHistoryService;
import io.github.dhluong90.bpm.rules.api.RuleSetNotFoundException;
import io.github.dhluong90.bpm.rules.api.VersionHistoryEntry;
import io.github.dhluong90.bpm.rules.api.VersionNotFoundException;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import io.github.dhluong90.bpm.rules.internal.json.EngineValues;
import io.github.dhluong90.bpm.rules.internal.json.Hashes;
import io.github.dhluong90.bpm.rules.internal.json.Ulid;
import io.github.dhluong90.bpm.rules.internal.read.RuleSetReader;
import io.github.dhluong90.bpm.rules.internal.store.DecisionLog;
import io.github.dhluong90.bpm.rules.internal.store.EventOutbox;
import io.github.dhluong90.bpm.rules.internal.store.HistoryRow;
import io.github.dhluong90.bpm.rules.internal.store.HistoryVersion;
import io.github.dhluong90.bpm.rules.internal.store.RuleSetEventRow;
import io.github.dhluong90.bpm.rules.internal.store.RuleStore;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A rule set's history across its versions and the evidence export (PD-116). Every read and write is scoped to the
 * resolved tenant; permissions are checked here, so the Java API has the same guard as REST. An export reads, writes
 * the file, appends the rule set's {@code EVIDENCE_EXPORTED} history row and writes {@code rules.evidence.exported.v1}
 * in one transaction, and every refusal happens before anything is written. Thread-safe and stateless.
 */
final class DefaultRuleSetHistoryService implements RuleSetHistoryService {

    static final String PERMISSION_READ = DefaultVersionService.PERMISSION_READ;
    static final String PERMISSION_LOG_READ = DefaultDecisionLogService.PERMISSION_LOG_READ;
    static final String EVENT_EVIDENCE_EXPORTED = "rules.evidence.exported.v1";
    /** The idempotency operation of an export; keys are scoped to tenant and operation. */
    static final String OPERATION_EVIDENCE = "rule-set-evidence";
    private static final String CURSOR_HISTORY = "rule-set-history";
    private static final Pattern DECISION_ID = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{26}$");

    private final EngineContext context;
    private final RuleStore store;
    private final DecisionLog log;
    private final EventOutbox outbox;
    private final CursorKeys cursorKeys;
    private final int maxBytes;

    DefaultRuleSetHistoryService(EngineContext context, RuleStore store, DecisionLog log, EventOutbox outbox,
            CursorKeys cursorKeys) {
        this(context, store, log, outbox, cursorKeys, EngineDefaults.EVIDENCE_MAX_BYTES);
    }

    /** With another file size limit, for tests of the limit. */
    DefaultRuleSetHistoryService(EngineContext context, RuleStore store, DecisionLog log, EventOutbox outbox,
            CursorKeys cursorKeys, int maxBytes) {
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.log = Objects.requireNonNull(log, "log must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.cursorKeys = Objects.requireNonNull(cursorKeys, "cursorKeys must not be null");
        this.maxBytes = maxBytes;
    }

    @Override
    public RuleSetHistory history(String ruleSet, PageRequest page) {
        String tenant = context.tenant();
        context.callerWith(PERMISSION_READ);
        PageRequest request = page == null ? PageRequest.first() : page;
        int limit = limit(request);
        requireRuleSetName(ruleSet);
        // Verified in memory before any transaction: a forged cursor never reaches the database (RLE-039).
        String before = cursorKeys.verify(tenant, ruleSet, CURSOR_HISTORY, request.cursor());
        Integer beforeVersion = before == null ? null : Cursors.positiveInt(before);
        return context.withoutDooming(() -> {
            String ruleSetId = store.findRuleSetId(tenant, ruleSet).orElseThrow(() -> new RuleSetNotFoundException(ruleSet));
            List<HistoryVersion> rows = store.historyVersions(tenant, ruleSetId, beforeVersion, limit + 1);
            boolean more = rows.size() > limit;
            List<HistoryVersion> versions = more ? rows.subList(0, limit) : rows;
            Map<String, List<HistoryRow>> events = eventsByVersion(tenant, versions,
                    RuleSetHistory.MAX_EVENTS_PER_VERSION + 1);
            List<RuleSetHistory.VersionLine> lines = new ArrayList<>();
            for (HistoryVersion v : versions) {
                List<HistoryRow> own = events.getOrDefault(v.id(), List.of());
                boolean truncated = own.size() > RuleSetHistory.MAX_EVENTS_PER_VERSION;
                List<VersionHistoryEntry> entries = (truncated ? own.subList(0, RuleSetHistory.MAX_EVENTS_PER_VERSION)
                        : own).stream().map(HistoryRow::toEntry).toList();
                VersionHistoryEntry rejected = entries.stream()
                        .filter(e -> e.action() == LifecycleAction.REJECTED).reduce((a, b) -> b).orElse(null);
                lines.add(new RuleSetHistory.VersionLine(v.version(), v.status(), v.effectiveFrom(), v.retiredAt(),
                        v.canonicalHash(), v.createdBy(), v.createdAt(), v.requestedBy(), v.requestedAt(),
                        v.approvedBy(), v.approvedAt(), v.approvalComment(),
                        rejected == null ? null : rejected.actor(), rejected == null ? null : rejected.occurredAt(),
                        rejected == null ? null : rejected.reason(), entries, truncated));
            }
            List<RuleSetHistory.RuleSetEvent> ruleSetEvents = new ArrayList<>();
            boolean eventsTruncated = false;
            if (request.cursor() == null) {
                List<RuleSetEventRow> own = store.ruleSetEvents(tenant, ruleSetId, RuleSetHistory.MAX_RULE_SET_EVENTS + 1);
                eventsTruncated = own.size() > RuleSetHistory.MAX_RULE_SET_EVENTS;
                (eventsTruncated ? own.subList(0, RuleSetHistory.MAX_RULE_SET_EVENTS) : own)
                        .forEach(row -> ruleSetEvents.add(event(row)));
            }
            String next = more ? cursorKeys.sign(tenant, ruleSet, CURSOR_HISTORY,
                    Integer.toString(versions.getLast().version())) : null;
            return new RuleSetHistory(ruleSet, lines, ruleSetEvents, eventsTruncated, next);
        });
    }

    @Override
    public EvidenceExport exportEvidence(String ruleSet, EvidenceRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String tenant = context.tenant();
        String caller = context.callerWith(PERMISSION_READ);
        if (!request.decisionIds().isEmpty()) {
            // Decision data is the log's; checked before anything is read.
            context.callerWith(PERMISSION_LOG_READ);
        }
        requireRuleSetName(ruleSet);
        List<Integer> versions = versions(request.versions());
        List<String> decisionIds = decisionIds(request.decisionIds());
        if (versions.isEmpty() && decisionIds.isEmpty()) {
            throw new InputInvalidException("Name at least one version or one decision");
        }
        String correlationId = EventEnvelope.requireValidCorrelationId(request.correlationId());
        String requestHash = Hashes.sha256Parts(OPERATION_EVIDENCE, ruleSet, request.format().name(),
                versions.stream().map(String::valueOf).collect(Collectors.joining(",")), String.join(",", decisionIds));
        return context.idempotent(tenant, OPERATION_EVIDENCE, request.idempotencyKey(), requestHash, 200,
                () -> export(tenant, caller, ruleSet, request.format(), versions, decisionIds, correlationId),
                DefaultRuleSetHistoryService::toJson, DefaultRuleSetHistoryService::fromJson);
    }

    private EvidenceExport export(String tenant, String caller, String ruleSet, EvidenceFormat format,
            List<Integer> numbers, List<String> decisionIds, String correlationId) {
        String ruleSetId = store.findRuleSetId(tenant, ruleSet).orElseThrow(() -> new RuleSetNotFoundException(ruleSet));
        List<HistoryVersion> found = numbers.isEmpty() ? List.of() : store.versionsByNumber(tenant, ruleSetId, numbers);
        Set<Integer> present = found.stream().map(HistoryVersion::version).collect(Collectors.toSet());
        for (int number : numbers) {
            if (!present.contains(number)) {
                throw new VersionNotFoundException(ruleSet, number);
            }
        }
        Map<String, List<HistoryRow>> events = eventsByVersion(tenant, found,
                EngineDefaults.EVIDENCE_MAX_EVENTS_PER_VERSION + 1);
        Map<HistoryVersion, List<VersionHistoryEntry>> versions = new LinkedHashMap<>();
        for (HistoryVersion v : found) {
            List<HistoryRow> own = events.getOrDefault(v.id(), List.of());
            if (own.size() > EngineDefaults.EVIDENCE_MAX_EVENTS_PER_VERSION) {
                throw new EvidenceTooLargeException(maxBytes);
            }
            versions.put(v, own.stream().map(HistoryRow::toEntry).toList());
        }
        List<LoggedDecision> decisions = new ArrayList<>();
        for (String id : decisionIds) {
            LoggedDecision d = log.find(tenant, id).map(DefaultDecisionLogService::view)
                    .orElseThrow(() -> new DecisionNotFoundException(id));
            if (!d.ruleSet().equals(ruleSet)) {
                throw new InputInvalidException("Decision " + id + " belongs to rule set " + d.ruleSet() + ", not "
                        + ruleSet);
            }
            decisions.add(d);
        }
        Instant now = context.clock().instant();
        EvidenceFiles.File file = EvidenceFiles.write(format, new EvidenceFiles.Header(ruleSet, tenant, caller, now),
                versions, decisions);
        long bytes = file.content().getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maxBytes) {
            throw new EvidenceTooLargeException(maxBytes);
        }
        // Recorded only now, after every check: a refused export leaves no trace but its absence.
        ObjectNode evidence = evidence(format, numbers, decisionIds, file.contentSha256(), bytes);
        store.appendRuleSetEvent(new RuleSetEventRow(tenant, Ulid.next(now), ruleSetId,
                RuleSetHistory.Action.EVIDENCE_EXPORTED, caller, now, EngineValues.write(evidence)));
        String eventId = Ulid.next(now);
        ObjectNode payload = EventEnvelope.start(eventId, EVENT_EVIDENCE_EXPORTED, tenant, now, correlationId, null)
                .put("actor", caller)
                .put("ruleSet", ruleSet);
        evidence.properties().forEach(member -> payload.set(member.getKey(), member.getValue()));
        outbox.append(tenant, eventId, EngineValues.write(payload), now);
        return new EvidenceExport(ruleSet, format, file.content(), file.contentSha256(), caller, now, numbers,
                decisionIds);
    }

    private Map<String, List<HistoryRow>> eventsByVersion(String tenant, List<HistoryVersion> versions, int perVersion) {
        Map<String, List<HistoryRow>> byVersion = new LinkedHashMap<>();
        if (versions.isEmpty()) {
            return byVersion;
        }
        for (HistoryRow row : store.historyOf(tenant, versions.stream().map(HistoryVersion::id).toList(), perVersion)) {
            byVersion.computeIfAbsent(row.versionId(), ignored -> new ArrayList<>()).add(row);
        }
        return byVersion;
    }

    private static ObjectNode evidence(EvidenceFormat format, List<Integer> versions, List<String> decisionIds,
            String sha256, long bytes) {
        ObjectNode node = EngineJson.NODES.objectNode().put("format", format.extension());
        ArrayNode v = node.putArray("versions");
        versions.forEach(v::add);
        ArrayNode d = node.putArray("decisionIds");
        decisionIds.forEach(d::add);
        node.put("contentSha256", sha256);
        node.put("bytes", bytes);
        return node;
    }

    private static RuleSetHistory.RuleSetEvent event(RuleSetEventRow row) {
        JsonNode e = EngineValues.read(row.evidenceJson());
        List<Integer> versions = new ArrayList<>();
        e.path("versions").forEach(n -> versions.add(n.intValue()));
        List<String> ids = new ArrayList<>();
        e.path("decisionIds").forEach(n -> ids.add(n.stringValue()));
        return new RuleSetHistory.RuleSetEvent(row.action(), row.actor(), row.occurredAt(),
                new RuleSetHistory.EvidenceSummary(EvidenceFormat.of(e.path("format").stringValue()), versions, ids,
                        e.path("contentSha256").stringValue(), e.path("bytes").longValue()));
    }

    /** Version numbers, at most 100, each at least 1, no repeats; returned ascending, the order of the file. */
    private static List<Integer> versions(List<Integer> requested) {
        if (requested.size() > EvidenceRequest.MAX_VERSIONS) {
            throw new InputInvalidException("versions holds at most " + EvidenceRequest.MAX_VERSIONS + " entries, was "
                    + requested.size());
        }
        TreeSet<Integer> sorted = new TreeSet<>();
        for (Integer number : requested) {
            if (number == null || number < 1) {
                throw new InputInvalidException("versions holds version numbers of 1 or more");
            }
            if (!sorted.add(number)) {
                throw new InputInvalidException("versions names version " + number + " twice");
            }
        }
        return List.copyOf(sorted);
    }

    /** Decision ids, at most 100, each a ULID, no repeats; kept in the order sent. */
    private static List<String> decisionIds(List<String> requested) {
        if (requested.size() > EvidenceRequest.MAX_DECISIONS) {
            throw new InputInvalidException("Choose at most " + EvidenceRequest.MAX_DECISIONS + " decisions; was "
                    + requested.size() + ". Larger sets are found with the decision search");
        }
        Set<String> seen = new HashSet<>();
        for (String id : requested) {
            if (id == null || !DECISION_ID.matcher(id).matches()) {
                throw new InputInvalidException("decisionIds holds decision ids (26-character ULIDs)");
            }
            if (!seen.add(id)) {
                throw new InputInvalidException("decisionIds names decision " + id + " twice");
            }
        }
        return List.copyOf(requested);
    }

    private int limit(PageRequest request) {
        int max = context.settings().queryMaxPageSize();
        if (request.limit() == null) {
            return Math.min(EngineDefaults.QUERY_DEFAULT_PAGE_SIZE, max);
        }
        if (request.limit() < 1 || request.limit() > max) {
            throw new QueryInvalidException("limit must be 1 to " + max + ", was " + request.limit());
        }
        return request.limit();
    }

    private static void requireRuleSetName(String ruleSet) {
        if (!RuleSetReader.isRuleSetName(ruleSet)) {
            throw new RuleSetNotFoundException("(invalid name)");
        }
    }

    /** The idempotency record of an export: the whole file, so a replay returns the same bytes. */
    static String toJson(EvidenceExport export) {
        ObjectNode node = EngineJson.NODES.objectNode()
                .put("ruleSet", export.ruleSet())
                .put("format", export.format().extension())
                .put("content", export.content())
                .put("contentSha256", export.contentSha256())
                .put("exportedBy", export.exportedBy())
                .put("exportedAt", export.exportedAt().toString());
        ArrayNode versions = node.putArray("versions");
        export.versions().forEach(versions::add);
        ArrayNode ids = node.putArray("decisionIds");
        export.decisionIds().forEach(ids::add);
        return EngineValues.write(node);
    }

    static EvidenceExport fromJson(String json) {
        JsonNode node = EngineValues.read(json);
        List<Integer> versions = new ArrayList<>();
        node.path("versions").forEach(n -> versions.add(n.intValue()));
        List<String> ids = new ArrayList<>();
        node.path("decisionIds").forEach(n -> ids.add(n.stringValue()));
        return new EvidenceExport(node.path("ruleSet").stringValue(), EvidenceFormat.of(node.path("format").stringValue()),
                node.path("content").stringValue(), node.path("contentSha256").stringValue(),
                node.path("exportedBy").stringValue(), Instant.parse(node.path("exportedAt").stringValue()), versions,
                ids);
    }
}
