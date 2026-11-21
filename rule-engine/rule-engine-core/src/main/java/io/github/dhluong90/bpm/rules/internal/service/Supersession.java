package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.api.LifecycleAction;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import io.github.dhluong90.bpm.rules.internal.json.EngineValues;
import io.github.dhluong90.bpm.rules.internal.json.Ulid;
import io.github.dhluong90.bpm.rules.internal.store.EventOutbox;
import io.github.dhluong90.bpm.rules.internal.store.HistoryRow;
import io.github.dhluong90.bpm.rules.internal.store.RuleStore;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Retires the versions a newer version in effect has superseded and records each one exactly once (ADR-0025 decision
 * 2, DESIGN section 3.3.2): the conditional update returns only the rows this transaction changed, and only for those
 * are a {@code RETIRED} history row (actor {@code lifecycle.system-actor}, reason {@code SUPERSEDED},
 * {@code supersededBy}, approval mode {@code system}) and a {@code rules.version.retired.v1} event written. Runs inside
 * the caller's transaction: the approve or retire command, or the lifecycle sweep. Stateless and thread-safe.
 */
final class Supersession {

    static final String EVENT_RETIRED = "rules.version.retired.v1";
    static final String MODE_SYSTEM = "system";

    private final RuleStore store;
    private final EventOutbox outbox;
    private final EngineSettings settings;
    private final io.github.dhluong90.bpm.rules.internal.store.TransactionRunner transactions;
    private final EngineMeters meters;

    Supersession(RuleStore store, EventOutbox outbox, EngineSettings settings,
            io.github.dhluong90.bpm.rules.internal.store.TransactionRunner transactions, EngineMeters meters) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.meters = Objects.requireNonNull(meters, "meters must not be null");
    }

    /**
     * Retires what is superseded at {@code now}, in one tenant and rule set or, with nulls, everywhere.
     *
     * @param tenantId the tenant, or null for the sweep
     * @param ruleSetId the rule set, or null
     * @param now the engine clock
     * @return the number of versions retired by this call
     */
    int retire(String tenantId, String ruleSetId, Instant now) {
        return retire(tenantId, ruleSetId, now, null);
    }

    /**
     * Retires what is superseded at {@code now} inside a caller's command, whose correlation id the events carry.
     *
     * @param tenantId the tenant, or null for the sweep
     * @param ruleSetId the rule set, or null
     * @param now the engine clock
     * @param correlationId the command's correlation id, or null to start a chain at each event
     * @return the number of versions retired by this call
     */
    int retire(String tenantId, String ruleSetId, Instant now, String correlationId) {
        List<RuleStore.Superseded> retired = store.retireSuperseded(tenantId, ruleSetId, now);
        String actor = settings.systemActor();
        for (RuleStore.Superseded row : retired) {
            store.appendHistory(new HistoryRow(row.tenantId(), Ulid.next(now), row.versionId(), LifecycleAction.RETIRED,
                    VersionStatus.PUBLISHED, VersionStatus.RETIRED, actor, EngineDefaults.REASON_SUPERSEDED,
                    row.lockVersion(), now, row.supersededBy(), MODE_SYSTEM, false));
            String eventId = Ulid.next(now);
            outbox.append(row.tenantId(), eventId, EngineValues.write(retiredEvent(eventId, correlationId,
                    row.tenantId(), row.ruleSet(), row.version(), actor, EngineDefaults.REASON_SUPERSEDED,
                    row.supersededBy(), now, MODE_SYSTEM, false)), now);
        }
        if (!retired.isEmpty()) {
            int count = retired.size();
            transactions.afterCommit(() -> meters.retired(EngineMeters.RetireReason.SUPERSEDED, count));
        }
        // ADR-0009: a retirement moves the rule set's epoch and every replica is notified after commit. The approve and
        // retire commands move it themselves, once per command; the sweep (no tenant) moves it here, once per rule set.
        if (tenantId != null) {
            return retired.size();
        }
        retired.stream().map(row -> java.util.List.of(row.tenantId(), row.ruleSet())).distinct().forEach(key ->
                store.findRuleSetId(key.get(0), key.get(1))
                        .ifPresent(id -> store.advanceCacheEpoch(key.get(0), id, key.get(1))));
        return retired.size();
    }

    /**
     * The payload of {@code rules.version.retired.v1} with the full envelope (DESIGN section 3.3, the event schema of
     * the same name). The event id is the outbox row id.
     */
    static tools.jackson.databind.node.ObjectNode retiredEvent(String eventId, String correlationId, String tenant,
            String ruleSet, int version, String retiredBy, String reason, Integer supersededBy, Instant at,
            String approvalMode, boolean noVersionInEffect) {
        tools.jackson.databind.node.ObjectNode payload = EventEnvelope.start(eventId, EVENT_RETIRED, tenant, at,
                        correlationId, null)
                .put("ruleSet", ruleSet)
                .put("version", version)
                .put("actor", retiredBy)
                .put("retiredBy", retiredBy)
                .put("reason", reason);
        if (supersededBy == null) {
            payload.putNull("supersededBy");
        } else {
            payload.put("supersededBy", supersededBy.intValue());
        }
        payload.put("retiredAt", at.toString()).put("approvalMode", approvalMode).putNull("requestedBy")
                .put("noVersionInEffect", noVersionInEffect);
        return payload;
    }
}
