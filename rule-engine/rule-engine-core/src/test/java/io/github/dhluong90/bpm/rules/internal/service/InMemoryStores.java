package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.rules.api.RuleSetExistsException;
import io.github.dhluong90.bpm.rules.api.RuleSetSummary;
import io.github.dhluong90.bpm.rules.api.VersionSummary;
import io.github.dhluong90.bpm.rules.api.VersionConflictException;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import io.github.dhluong90.bpm.rules.internal.store.DecisionLog;
import io.github.dhluong90.bpm.rules.internal.store.DecisionRecord;
import io.github.dhluong90.bpm.rules.internal.store.EventOutbox;
import io.github.dhluong90.bpm.rules.internal.store.HistoryRow;
import io.github.dhluong90.bpm.rules.internal.store.IdempotencyStore;
import io.github.dhluong90.bpm.rules.internal.store.RuleStore;
import io.github.dhluong90.bpm.rules.internal.store.StoredVersion;
import io.github.dhluong90.bpm.rules.internal.store.TransactionRunner;
import io.github.dhluong90.bpm.rules.internal.store.VersionInputs;
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
import tools.jackson.databind.JsonNode;

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

    /** {@code cache_epoch} by tenant and rule set id; 1 for a new rule set, as in the migration. */
    final Map<String, Long> epochs = new ConcurrentHashMap<>();
    /** Every epoch notification, as {@code tenant/ruleSet:epoch}, in order. */
    final List<String> notifications = new ArrayList<>();

    @Override
    public Optional<EffectiveVersion> findEffectiveForCache(String tenantId, String ruleSet, Instant at) {
        // The same window as JdbcRuleStore: the nearest start or retirement of any approved version on each side.
        return findEffective(tenantId, ruleSet, at).map(v -> {
            List<Instant> bounds = versions.stream()
                    .filter(n -> n.tenantId().equals(tenantId) && n.ruleSetId().equals(v.ruleSetId())
                            && n.approvedAt() != null)
                    .flatMap(n -> java.util.stream.Stream.of(n.effectiveFrom(), n.retiredAt()))
                    .filter(java.util.Objects::nonNull).toList();
            Instant from = bounds.stream().filter(b -> !b.isAfter(at)).max(Comparator.naturalOrder()).orElseThrow();
            Instant next = bounds.stream().filter(b -> b.isAfter(at)).min(Comparator.naturalOrder()).orElse(null);
            return new EffectiveVersion(v, epochs.getOrDefault(tenantId + "/" + v.ruleSetId(), 1L), from, next);
        });
    }

    @Override
    public long advanceCacheEpoch(String tenantId, String ruleSetId, String ruleSet) {
        long epoch = epochs.merge(tenantId + "/" + ruleSetId, 2L, (old, ignored) -> old + 1);
        notifications.add(tenantId + "/" + ruleSet + ":" + epoch);
        return epoch;
    }

    @Override
    public Map<String, Long> cacheEpochs(String tenantId, java.util.Collection<String> names) {
        Map<String, Long> result = new java.util.HashMap<>();
        for (RuleSetRow row : ruleSets) {
            if (row.tenantId().equals(tenantId) && names.contains(row.name())) {
                result.put(row.name(), epochs.getOrDefault(tenantId + "/" + row.id(), 1L));
            }
        }
        return result;
    }

    @Override
    public void replaceDraft(StoredVersion updated, long expectedLockVersion) {
        for (int i = 0; i < versions.size(); i++) {
            StoredVersion v = versions.get(i);
            if (v.tenantId().equals(updated.tenantId()) && v.id().equals(updated.id())) {
                if (v.lockVersion() != expectedLockVersion || v.status() != io.github.dhluong90.bpm.rules.api.VersionStatus.DRAFT) {
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
                        && v.approvedAt() != null && !v.effectiveFrom().isAfter(at)
                        && (v.retiredAt() == null || v.retiredAt().isAfter(at)))
                .max(Comparator.comparing(StoredVersion::version));
    }

    @Override
    public void lockRuleSet(String tenantId, String ruleSetId) {
        // single-threaded tests: nothing to lock
    }

    @Override
    public Optional<StoredVersion> nextScheduled(String tenantId, String ruleSetId, Instant after,
            String excludingVersionId) {
        return versions.stream().filter(v -> v.tenantId().equals(tenantId) && v.ruleSetId().equals(ruleSetId)
                        && !v.id().equals(excludingVersionId) && v.approvedAt() != null && v.retiredAt() == null
                        && v.effectiveFrom().isAfter(after))
                .min(Comparator.comparing(StoredVersion::effectiveFrom));
    }

    @Override
    public synchronized List<Superseded> retireSuperseded(String tenantId, String ruleSetId, Instant now) {
        List<Superseded> retired = new ArrayList<>();
        for (int i = 0; i < versions.size(); i++) {
            StoredVersion old = versions.get(i);
            if (old.status() != VersionStatus.PUBLISHED || (tenantId != null && !old.tenantId().equals(tenantId))
                    || (ruleSetId != null && !old.ruleSetId().equals(ruleSetId))) {
                continue;
            }
            java.util.OptionalInt newer = versions.stream()
                    .filter(n -> n.tenantId().equals(old.tenantId()) && n.ruleSetId().equals(old.ruleSetId())
                            && n.status() == VersionStatus.PUBLISHED && n.version() > old.version()
                            && !n.effectiveFrom().isAfter(now))
                    .mapToInt(StoredVersion::version).max();
            if (newer.isPresent()) {
                StoredVersion done = old.retired(now);
                versions.set(i, done);
                retired.add(new Superseded(done.tenantId(), done.id(), done.ruleSet(), done.version(),
                        done.lockVersion(), newer.getAsInt()));
            }
        }
        return retired;
    }

    @Override
    public void append(DecisionRecord record) {
        decisions.add(record);
    }

    @Override
    public synchronized List<DecisionRecord> search(String tenantId, String ruleSet, Instant from, Instant to,
            DecisionLog.Position after, int limit, java.time.Duration statementTimeout) {
        Comparator<DecisionRecord> newestFirst = Comparator.comparing(DecisionRecord::createdAt)
                .thenComparing(DecisionRecord::id).reversed();
        return decisions.stream()
                .filter(r -> r.tenantId().equals(tenantId) && r.ruleSet().equals(ruleSet))
                .filter(r -> !r.createdAt().isBefore(from) && r.createdAt().isBefore(to))
                .filter(r -> after == null || r.createdAt().isBefore(after.createdAt())
                        || (r.createdAt().equals(after.createdAt()) && r.id().compareTo(after.id()) < 0))
                .sorted(newestFirst)
                .limit(limit)
                .toList();
    }

    @Override
    public Optional<DecisionRecord> find(String tenantId, String id) {
        return decisions.stream().filter(d -> d.tenantId().equals(tenantId) && d.id().equals(id)).findFirst();
    }

    @Override
    public List<DecisionRecord> findByLoggedInput(String tenantId, String ruleSet, String input, List<String> texts,
            Instant from, Instant to, int limit) {
        return decisions.stream()
                .filter(d -> d.tenantId().equals(tenantId) && d.ruleSet().equals(ruleSet)
                        && !d.createdAt().isBefore(from) && d.createdAt().isBefore(to))
                .filter(d -> {
                    var value = io.github.dhluong90.bpm.rules.internal.json.EngineValues.read(d.maskedInputsJson()).get(input);
                    return value != null && value.isString() && texts.contains(value.stringValue());
                })
                .sorted(Comparator.comparing(DecisionRecord::createdAt).thenComparing(DecisionRecord::id).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public List<VersionInputs> liveVersionInputs() {
        return versions.stream().filter(v -> v.status() != VersionStatus.RETIRED)
                .map(v -> new VersionInputs(v.tenantId(), v.ruleSet(), v.version(),
                        io.github.dhluong90.bpm.rules.internal.json.EngineValues.read(v.canonicalJson()).get("inputs").toString()))
                .toList();
    }

    @Override
    public void append(String tenantId, String id, String payloadJson, Instant createdAt) {
        // Every event any core test writes is checked against its schema file and its envelope (#241).
        JsonNode payload = EngineJson.JSON.readTree(payloadJson);
        EventSchemas.conforms(payload);
        assertThat(payload.path("eventId").asString()).as("eventId is the row id")
                .isEqualTo(id);
        assertThat(payload.path("tenantId").asString()).as("tenantId is the row's")
                .isEqualTo(tenantId);
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
    /** Setting name to tenant to value, as {@code rle_setting_seen}. */
    final Map<String, Map<String, String>> settingsSeen = new ConcurrentHashMap<>();

    @Override
    public Map<String, String> settingsSeen(String setting) {
        return Map.copyOf(settingsSeen.getOrDefault(setting, Map.of()));
    }

    @Override
    public void recordSettingSeen(String tenantId, String setting, String value, Instant at) {
        settingsSeen.computeIfAbsent(setting, ignored -> new ConcurrentHashMap<>()).put(tenantId, value);
    }

    @Override
    public byte[] engineKey(String name, byte[] candidate, Instant now) {
        return engineKeys.computeIfAbsent(name, ignored -> candidate.clone()).clone();
    }

    @Override
    public Optional<byte[]> findEngineKey(String name) {
        return Optional.ofNullable(engineKeys.get(name)).map(byte[]::clone);
    }
}
