package com.horus.bpm.rules.internal.service;

import com.horus.bpm.rules.api.RuleSetExistsException;
import com.horus.bpm.rules.api.RuleSetSummary;
import com.horus.bpm.rules.api.VersionSummary;
import com.horus.bpm.rules.api.VersionConflictException;
import com.horus.bpm.rules.api.VersionStatus;
import com.horus.bpm.rules.internal.store.DecisionLog;
import com.horus.bpm.rules.internal.store.DecisionRecord;
import com.horus.bpm.rules.internal.store.EventOutbox;
import com.horus.bpm.rules.internal.store.HistoryRow;
import com.horus.bpm.rules.internal.store.IdempotencyStore;
import com.horus.bpm.rules.internal.store.RuleStore;
import com.horus.bpm.rules.internal.store.StoredVersion;
import com.horus.bpm.rules.internal.store.TransactionRunner;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** In-memory adapters of the storage ports, for service tests without a database. Not thread-safe across tenants. */
final class InMemoryStores implements RuleStore, DecisionLog, EventOutbox, IdempotencyStore, TransactionRunner {

    record RuleSetRow(String tenantId, String id, String name) {
    }

    record OutboxRow(String tenantId, String id, String payload, Instant createdAt) {
    }

    record IdempotencyRow(String requestHash, int status, String body, Instant expiresAt) {
    }

    final List<RuleSetRow> ruleSets = new ArrayList<>();
    final List<StoredVersion> versions = new ArrayList<>();
    final List<DecisionRecord> decisions = new ArrayList<>();
    final List<OutboxRow> outbox = new ArrayList<>();
    final List<HistoryRow> history = new ArrayList<>();
    final Map<String, IdempotencyRow> idempotency = new ConcurrentHashMap<>();
    /** Keys a concurrent transaction holds; a claim on them is in flight. */
    final Set<String> heldKeys = new HashSet<>();

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        return work.get();
    }

    @Override
    public Optional<String> findRuleSetId(String tenantId, String name) {
        return ruleSets.stream().filter(r -> r.tenantId().equals(tenantId) && r.name().equals(name))
                .map(RuleSetRow::id).findFirst();
    }

    @Override
    public void createRuleSet(String tenantId, String id, String name, Instant createdAt) {
        if (findRuleSetId(tenantId, name).isPresent()) {
            throw new RuleSetExistsException(name);
        }
        ruleSets.add(new RuleSetRow(tenantId, id, name));
    }

    @Override
    public int lockAndNextVersionNumber(String tenantId, String ruleSetId) {
        return versions.stream().filter(v -> v.tenantId().equals(tenantId) && v.ruleSetId().equals(ruleSetId))
                .mapToInt(StoredVersion::version).max().orElse(0) + 1;
    }

    @Override
    public void insertVersion(StoredVersion version) {
        versions.add(version);
    }

    @Override
    public Optional<StoredVersion> findVersion(String tenantId, String ruleSet, int version, boolean forUpdate) {
        return versions.stream().filter(v -> v.tenantId().equals(tenantId) && v.ruleSet().equals(ruleSet)
                && v.version() == version).findFirst();
    }

    @Override
    public void updateVersion(StoredVersion updated, long expectedLockVersion) {
        for (int i = 0; i < versions.size(); i++) {
            StoredVersion v = versions.get(i);
            if (v.tenantId().equals(updated.tenantId()) && v.id().equals(updated.id())) {
                if (v.lockVersion() != expectedLockVersion) {
                    throw new VersionConflictException("stale");
                }
                versions.set(i, updated);
                return;
            }
        }
        throw new IllegalStateException("no such version");
    }

    @Override
    public Optional<StoredVersion> findEffective(String tenantId, String ruleSet, Instant at) {
        return versions.stream()
                .filter(v -> v.tenantId().equals(tenantId) && v.ruleSet().equals(ruleSet)
                        && v.status() == VersionStatus.PUBLISHED && !v.effectiveFrom().isAfter(at))
                .max(Comparator.comparing(StoredVersion::effectiveFrom).thenComparing(StoredVersion::version));
    }

    @Override
    public void append(DecisionRecord record) {
        decisions.add(record);
    }

    @Override
    public void append(String tenantId, String id, String payloadJson, Instant createdAt) {
        outbox.add(new OutboxRow(tenantId, id, payloadJson, createdAt));
    }

    @Override
    public Claim claim(String tenantId, String operation, String key, String requestHash, Instant now) {
        String id = tenantId + "|" + operation + "|" + key;
        if (heldKeys.contains(id)) {
            return new InFlight();
        }
        IdempotencyRow row = idempotency.get(id);
        if (row == null || row.expiresAt().isBefore(now)) {
            return new Fresh();
        }
        return row.requestHash().equals(requestHash) ? new Replay(row.status(), row.body()) : new Mismatch();
    }

    @Override
    public void record(String tenantId, String operation, String key, String requestHash, int statusCode,
            String responseJson, Instant expiresAt) {
        idempotency.put(tenantId + "|" + operation + "|" + key, new IdempotencyRow(requestHash, statusCode, responseJson, expiresAt));
    }

    @Override
    public List<RuleSetSummary> listRuleSets(String tenantId, String afterName, int limit, Instant now) {
        return ruleSets.stream().filter(r -> r.tenantId().equals(tenantId))
                .filter(r -> afterName == null || r.name().compareTo(afterName) > 0)
                .sorted(Comparator.comparing(RuleSetRow::name)).limit(limit)
                .map(r -> summary(r, now)).toList();
    }

    @Override
    public Optional<RuleSetSummary> findRuleSetSummary(String tenantId, String name, Instant now) {
        return ruleSets.stream().filter(r -> r.tenantId().equals(tenantId) && r.name().equals(name)).findFirst()
                .map(r -> summary(r, now));
    }

    private RuleSetSummary summary(RuleSetRow row, Instant now) {
        List<StoredVersion> own = versions.stream()
                .filter(v -> v.tenantId().equals(row.tenantId()) && v.ruleSetId().equals(row.id())).toList();
        StoredVersion latest = own.stream().max(Comparator.comparingInt(StoredVersion::version)).orElseThrow();
        Integer published = findEffective(row.tenantId(), row.name(), now).map(StoredVersion::version).orElse(null);
        Set<String> ids = new HashSet<>();
        own.forEach(v -> ids.add(v.id()));
        Instant changed = own.stream().flatMap(v -> java.util.stream.Stream.of(v.createdAt(), v.requestedAt(), v.approvedAt()))
                .filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElseThrow();
        for (HistoryRow h : history) {
            if (h.tenantId().equals(row.tenantId()) && ids.contains(h.versionId()) && h.occurredAt().isAfter(changed)) {
                changed = h.occurredAt();
            }
        }
        return new RuleSetSummary(row.name(), latest.version(), latest.status(), published, changed);
    }

    @Override
    public List<VersionSummary> listVersions(String tenantId, String ruleSetId, Integer beforeVersion, int limit) {
        return versions.stream().filter(v -> v.tenantId().equals(tenantId) && v.ruleSetId().equals(ruleSetId))
                .filter(v -> beforeVersion == null || v.version() < beforeVersion)
                .sorted(Comparator.comparingInt(StoredVersion::version).reversed()).limit(limit)
                .map(StoredVersion::toSummary).toList();
    }

    @Override
    public void appendHistory(HistoryRow row) {
        history.add(row);
    }

    @Override
    public List<HistoryRow> history(String tenantId, String versionId) {
        return history.stream().filter(h -> h.tenantId().equals(tenantId) && h.versionId().equals(versionId)).toList();
    }

    final Map<String, byte[]> engineKeys = new ConcurrentHashMap<>();

    @Override
    public byte[] engineKey(String name, byte[] candidate, Instant now) {
        return engineKeys.computeIfAbsent(name, ignored -> candidate.clone()).clone();
    }

    @Override
    public Optional<byte[]> findEngineKey(String name) {
        return Optional.ofNullable(engineKeys.get(name)).map(byte[]::clone);
    }
}
