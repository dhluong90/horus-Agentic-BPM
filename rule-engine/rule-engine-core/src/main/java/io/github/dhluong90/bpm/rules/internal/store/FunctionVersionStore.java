package io.github.dhluong90.bpm.rules.internal.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The version labels of the host functions each rule set version was approved with (RLE-058, PD-119). It is the
 * baseline against which a changed function is noticed at start-up and, under {@code function-version-change:
 * refuse}, at evaluation. Every row belongs to a tenant; a read names its tenant.
 */
public interface FunctionVersionStore {

    /**
     * Records the function versions a rule set version is approved with, replacing an earlier record of it.
     *
     * @param tenantId the tenant
     * @param ruleSet the rule set name
     * @param version the version number
     * @param functionVersions function name to version label; empty when the version calls no function
     * @param at when it was approved
     */
    void record(String tenantId, String ruleSet, int version, Map<String, String> functionVersions,
            java.time.Instant at);

    /**
     * Reads the function versions a version was approved with.
     *
     * @param tenantId the tenant
     * @param ruleSet the rule set name
     * @param version the version number
     * @return function name to version label; empty when none were recorded
     */
    Map<String, String> approvedWith(String tenantId, String ruleSet, int version);

    /**
     * One use of a function by an approved version.
     *
     * @param tenantId the tenant
     * @param ruleSet the rule set name
     * @param version the version number
     * @param function the function name
     * @param functionVersion the version label it was approved with
     */
    record Use(String tenantId, String ruleSet, int version, String function, String functionVersion) {
    }

    /**
     * Lists the uses of functions by versions that are still published, across tenants, for the start-up check. Not
     * tenant-scoped on purpose: functions belong to the host.
     *
     * @return the uses, ordered by function, tenant, rule set and version
     */
    List<Use> publishedUses();

    /** @return a store that keeps the records in memory, for tests and hosts with no database */
    static FunctionVersionStore inMemory() {
        return new FunctionVersionStore() {
            private final Map<String, Map<String, String>> records = new ConcurrentHashMap<>();

            @Override
            public void record(String tenantId, String ruleSet, int version, Map<String, String> functionVersions,
                    java.time.Instant at) {
                records.put(key(tenantId, ruleSet, version), Map.copyOf(functionVersions));
            }

            @Override
            public Map<String, String> approvedWith(String tenantId, String ruleSet, int version) {
                return records.getOrDefault(key(tenantId, ruleSet, version), Map.of());
            }

            @Override
            public List<Use> publishedUses() {
                List<Use> uses = new ArrayList<>();
                records.forEach((key, functions) -> {
                    String[] parts = key.split("\u0000");
                    functions.forEach((function, label) -> uses.add(
                            new Use(parts[0], parts[1], Integer.parseInt(parts[2]), function, label)));
                });
                uses.sort(java.util.Comparator.comparing(Use::function).thenComparing(Use::tenantId)
                        .thenComparing(Use::ruleSet).thenComparingInt(Use::version));
                return uses;
            }

            private String key(String tenantId, String ruleSet, int version) {
                return tenantId + "\u0000" + ruleSet + "\u0000" + version;
            }
        };
    }
}
