package io.github.dhluong90.bpm.rules.internal.store;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stored simulation reports and which one a publication request carries (PD-115, RLE-199 to RLE-201). A report is
 * inserted once and never updated or deleted. Every row belongs to a tenant; every read names its tenant.
 */
public interface SimulationReportStore {

    /** The most reports one list returns. */
    int LIST_MAX = 50;

    /**
     * One report as stored.
     *
     * @param tenantId the tenant
     * @param id the report id (a ULID)
     * @param ruleSet the rule set name
     * @param version the version the report was run on
     * @param canonicalHash the version's canonical hash when it was run
     * @param createdBy who ran it
     * @param createdAt when
     * @param source {@code SAMPLES} or {@code REPLAY}
     * @param periodFrom for a replay, the first instant, inclusive; otherwise null
     * @param periodTo for a replay, the end, exclusive; otherwise null
     * @param periodLimit for a replay, the limit used; otherwise null
     * @param truncated whether the replay period held more decisions than were replayed
     * @param total all items
     * @param changed items whose outcome differs
     * @param unchanged items whose outcome is the same
     * @param failed items the reported version failed on
     * @param notReplayable replayed decisions that could not be, because of a masked input
     * @param languages the language codes of the rows, comma-separated
     * @param rowsJson the changed or failed rows as a JSON array of read outcomes; never an input value
     * @param rowsTruncated whether more items changed or failed than the rows hold
     */
    record Stored(String tenantId, String id, String ruleSet, int version, String canonicalHash, String createdBy,
            Instant createdAt, String source, Instant periodFrom, Instant periodTo, Integer periodLimit,
            boolean truncated, int total, int changed, int unchanged, int failed, int notReplayable, String languages,
            String rowsJson, boolean rowsTruncated) {
    }

    /**
     * Stores a report.
     *
     * @param report the report
     */
    void insert(Stored report);

    /**
     * Finds a report of a version.
     *
     * @param tenantId the tenant
     * @param ruleSet the rule set name
     * @param version the version
     * @param id the report id
     * @return the report, or empty for another tenant's, another rule set's or another version's
     */
    Optional<Stored> find(String tenantId, String ruleSet, int version, String id);

    /**
     * Lists the reports of a version, newest first.
     *
     * @param tenantId the tenant
     * @param ruleSet the rule set name
     * @param version the version
     * @param limit the most to return, at most {@link #LIST_MAX}
     * @return the reports
     */
    List<Stored> list(String tenantId, String ruleSet, int version, int limit);

    /**
     * Records that a publication request of a version carries a report. Appended, never changed.
     *
     * @param tenantId the tenant
     * @param ruleSet the rule set name
     * @param version the version
     * @param reportId the report id
     * @param attachedBy who requested publication
     * @param at when
     */
    void attach(String tenantId, String ruleSet, int version, String reportId, String attachedBy, Instant at);

    /**
     * Finds the report the latest publication request of a version carries.
     *
     * @param tenantId the tenant
     * @param ruleSet the rule set name
     * @param version the version
     * @return the report id, or empty when no request carried one
     */
    Optional<String> attachedReport(String tenantId, String ruleSet, int version);

    /** @return a store that keeps the reports in memory, for tests and hosts with no database */
    static SimulationReportStore inMemory() {
        return new SimulationReportStore() {
            private final Map<String, Stored> reports = new ConcurrentHashMap<>();
            private final Map<String, String> attachments = new ConcurrentHashMap<>();

            @Override
            public void insert(Stored report) {
                if (reports.putIfAbsent(report.tenantId() + "\u0000" + report.id(), report) != null) {
                    throw new IllegalStateException("A simulation report is never replaced");
                }
            }

            @Override
            public Optional<Stored> find(String tenantId, String ruleSet, int version, String id) {
                return Optional.ofNullable(reports.get(tenantId + "\u0000" + id))
                        .filter(r -> r.ruleSet().equals(ruleSet) && r.version() == version);
            }

            @Override
            public List<Stored> list(String tenantId, String ruleSet, int version, int limit) {
                List<Stored> found = new ArrayList<>();
                reports.values().stream()
                        .filter(r -> r.tenantId().equals(tenantId) && r.ruleSet().equals(ruleSet)
                                && r.version() == version)
                        .sorted(Comparator.comparing(Stored::id).reversed())
                        .limit(Math.min(limit, LIST_MAX)).forEach(found::add);
                return found;
            }

            @Override
            public void attach(String tenantId, String ruleSet, int version, String reportId, String attachedBy,
                    Instant at) {
                attachments.put(tenantId + "\u0000" + ruleSet + "\u0000" + version, reportId);
            }

            @Override
            public Optional<String> attachedReport(String tenantId, String ruleSet, int version) {
                return Optional.ofNullable(attachments.get(tenantId + "\u0000" + ruleSet + "\u0000" + version));
            }
        };
    }
}
