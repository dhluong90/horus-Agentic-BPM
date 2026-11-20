package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.Money;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The compiled cache of one replica on the in-memory stores (ADR-0009): eviction after this replica's approval and
 * retirement, by notification and by the epoch check for another replica's approval, the hand-over to a later
 * effective version without an epoch change, a chosen clock served from the window that holds it, and the bound of
 * RLE-034. The PostgreSQL notification path and two real replicas are
 * tested by CacheCoherencePostgresIT and the example host.
 */
class CompiledCacheTest {

    static final Instant NOW = Instant.parse("2026-12-15T02:00:00Z");
    static final DecisionInput SILVER = DecisionInput.of(Map.of("tier", "SILVER", "txCount", 1));

    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "alice");
    final EditorApiTest.MovableClock clock = new EditorApiTest.MovableClock(NOW);

    RuleEngines replica(int maxEntries) {
        EngineSettings settings = new EngineSettings(maxEntries, Duration.ofHours(24), 34, true);
        return RuleEngines.create(new EngineContext(caller, caller, clock, stores, stores, settings),
                stores, stores, stores);
    }

    @Test
    void RLE_010_this_replica_evicts_after_its_own_approval_and_serves_the_new_version() {
        // Given card-fee v1 cached
        RuleEngines a = replica(1000);
        publish(a, "card-fee", CardFee.yaml(), 1);
        assertThat(evaluate(a, "card-fee").version()).isEqualTo(1);

        // When v2 is approved on the same replica
        publish(a, "card-fee", withDefaultFee(60000), 2);

        // Then the epoch moved with a notification, and the next evaluation is v2
        assertThat(stores.notifications).containsExactly("bank-a/card-fee:2", "bank-a/card-fee:3");
        DecisionResult result = evaluate(a, "card-fee");
        assertThat(result.version()).isEqualTo(2);
        assertThat(result.outputs()).containsEntry("fee", Money.ofMinor("VND", 60000));
        assertThat(a.cache().evictions(CompiledCache.Cause.NOTIFY)).isEqualTo(1);
    }

    @Test
    void RLE_010_another_replica_refreshes_on_the_notification_or_else_on_the_epoch_check() {
        // Given two replicas on the same stores, both holding card-fee v1
        RuleEngines a = replica(1000);
        RuleEngines b = replica(1000);
        publish(a, "card-fee", CardFee.yaml(), 1);
        assertThat(evaluate(b, "card-fee").version()).isEqualTo(1);

        // When A approves v2 and no notification reaches B
        publish(a, "card-fee", withDefaultFee(60000), 2);

        // Then B serves v1 from its cache until the epoch check, then v2
        assertThat(evaluate(b, "card-fee").version()).isEqualTo(1);
        assertThat(b.cache().checkEpochs()).isEqualTo(1);
        assertThat(b.cache().evictions(CompiledCache.Cause.EPOCH)).isEqualTo(1);
        assertThat(evaluate(b, "card-fee").version()).isEqualTo(2);

        // When A approves v3 and the notification does reach B
        publish(a, "card-fee", withDefaultFee(70000), 3);
        assertThat(b.cache().evict("bank-a", "card-fee", 4)).isTrue();

        // Then B serves v3 at once, and the next check finds nothing stale
        assertThat(evaluate(b, "card-fee").version()).isEqualTo(3);
        assertThat(b.cache().checkEpochs()).isZero();
    }

    @Test
    void RLE_010_a_load_that_raced_a_notification_is_not_installed() {
        // Given B read v1 with epoch 2 just before a notification of epoch 3 arrived
        RuleEngines a = replica(1000);
        RuleEngines b = replica(1000);
        publish(a, "card-fee", CardFee.yaml(), 1);
        CompiledCache.Entry stale = b.cache().get("bank-a", "card-fee", NOW);
        assertThat(stale).isNull();
        evaluate(b, "card-fee");
        CompiledCache.Entry loaded = b.cache().get("bank-a", "card-fee", NOW);
        b.cache().evict("bank-a", "card-fee", 3);

        // When the old load is installed afterwards
        b.cache().put(loaded);

        // Then it is refused: an entry older than a notified epoch never serves
        assertThat(b.cache().get("bank-a", "card-fee", NOW)).isNull();
        assertThat(b.cache().size()).isZero();
    }

    @Test
    void RLE_010_a_later_effective_version_takes_over_at_its_date_without_an_epoch_change() {
        // Given v1 in force and v2 approved now with effectiveFrom one hour later
        RuleEngines a = replica(1000);
        publish(a, "card-fee", CardFee.yaml(), 1);
        Instant later = NOW.plus(Duration.ofHours(1));
        publish(a, "card-fee", withDefaultFee(60000).replace("hitPolicy: first",
                "effectiveFrom: \"" + later + "\"\nhitPolicy: first"), 2);
        assertThat(evaluate(a, "card-fee").version()).isEqualTo(1);
        int notifications = stores.notifications.size();

        // When the clock reaches the hand-over instant
        clock.set(later);

        // Then v2 is served with no notification and no epoch check
        assertThat(evaluate(a, "card-fee").version()).isEqualTo(2);
        assertThat(stores.notifications).hasSize(notifications);
        clock.set(later.minusSeconds(1));
        assertThat(evaluate(a, "card-fee").version()).isEqualTo(1);
    }

    @Test
    void RLE_008_a_chosen_clock_is_served_from_the_window_that_holds_it() {
        // Given v1 in force since 15 Dec 2026 and v2 approved now, in force from 1 Jan 2027
        RuleEngines a = replica(1000);
        publish(a, "card-fee", CardFee.yaml(), 1);
        Instant newYear = Instant.parse("2027-01-01T00:00:00Z");
        publish(a, "card-fee", withDefaultFee(60000).replace("hitPolicy: first",
                "effectiveFrom: \"" + newYear + "\"\nhitPolicy: first"), 2);

        // When the same replica evaluates at the host clock, at a chosen clock in January, and at the host clock again
        DecisionResult now = evaluate(a, "card-fee");
        DecisionResult january = evaluateAt(a, "card-fee", Instant.parse("2027-01-05T09:00:00Z"));
        DecisionResult again = evaluate(a, "card-fee");

        // Then each instant gets the version in force at it, whatever the cache held before
        assertThat(now.version()).isEqualTo(1);
        assertThat(january.version()).isEqualTo(2);
        assertThat(january.outputs()).containsEntry("fee", Money.ofMinor("VND", 60000));
        assertThat(again.version()).isEqualTo(1);
        assertThat(evaluateAt(a, "card-fee", newYear.minusMillis(1)).version()).isEqualTo(1);
        assertThat(evaluateAt(a, "card-fee", newYear).version()).isEqualTo(2);
    }

    @Test
    void RLE_026_a_retire_moves_the_epoch_and_this_replica_stops_serving_at_once() {
        // Given card-fee v1 cached on replica A
        RuleEngines a = replica(1000);
        long lock = publish(a, "card-fee", CardFee.yaml(), 1).lockVersion();
        assertThat(evaluate(a, "card-fee").version()).isEqualTo(1);
        int notifications = stores.notifications.size();

        // When bob retires v1 one hour later, confirming that no version stays in effect
        Instant retiredAt = NOW.plus(Duration.ofHours(1));
        clock.set(retiredAt);
        caller.become("bank-a", "bob");
        a.versionService().retire("card-fee", 1, new WriteOptions("k-retire", lock, "Fee table withdrawn"), true);

        // Then the epoch moved once with a notification, A no longer serves v1, and a clock before still selects it
        assertThat(stores.notifications).hasSize(notifications + 1);
        assertThat(a.cache().evictions(CompiledCache.Cause.NOTIFY)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> evaluate(a, "card-fee"))
                .isInstanceOf(io.github.dhluong90.bpm.rules.api.NoEffectiveVersionException.class);
        assertThat(evaluateAt(a, "card-fee", retiredAt.minusSeconds(1)).version()).isEqualTo(1);
    }

    @Test
    void RLE_034_compiled_cache_is_bounded_and_heap_stays_flat() {
        // Given cache.max-entries = 50 and 100 published rule sets fee-001 to fee-100 of 200 rows each
        RuleEngines bounded = replica(50);
        RuleEngines unbounded = replica(100_000);
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            String name = String.format("fee-%03d", i);
            names.add(name);
            publish(bounded, name, twoHundredRows(name).getBytes(StandardCharsets.UTF_8), 1);
        }
        DecisionInput input = DecisionInput.of(Map.of("txCount", 150));

        // When the host evaluates them in rotation: a warm-up round, then 10 rounds
        long[] heapAfterRound = new long[11];
        int largest = 0;
        for (int round = 0; round <= 10; round++) {
            for (String name : names) {
                caller.become("bank-a", "teller-01");
                DecisionResult result = bounded.ruleEngine().evaluate(name, input);
                largest = Math.max(largest, bounded.cache().size());
                if (round == 1) {
                    DecisionResult reference = unbounded.ruleEngine().evaluate(name, input);
                    assertThat(result.outcome().getClass()).isEqualTo(reference.outcome().getClass());
                    assertThat(result.matchedRuleIds()).isEqualTo(reference.matchedRuleIds());
                    assertThat(result.outputs()).isEqualTo(reference.outputs());
                }
            }
            if (round == 2 || round == 10) {
                heapAfterRound[round] = retainedHeap();
            }
        }

        // Then the store never held more than 50, evictions are at least 950, and the heap stayed flat
        assertThat(largest).isLessThanOrEqualTo(50);
        assertThat(bounded.cache().size()).isEqualTo(50);
        assertThat(bounded.cache().evictions(CompiledCache.Cause.SIZE)).isGreaterThanOrEqualTo(950);
        long margin = Math.max(heapAfterRound[2] / 10, 8L * 1024 * 1024);
        assertThat(heapAfterRound[10]).isLessThanOrEqualTo(heapAfterRound[2] + margin);
    }

    /** Median of three measurements of used heap, each after a full collection. */
    private static long retainedHeap() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        long[] samples = new long[3];
        for (int i = 0; i < 3; i++) {
            System.gc();
            samples[i] = memory.getHeapMemoryUsage().getUsed();
        }
        Arrays.sort(samples);
        return samples[1];
    }

    private DecisionResult evaluate(RuleEngines replica, String ruleSet) {
        caller.become("bank-a", "teller-01");
        return replica.ruleEngine().evaluate(ruleSet, SILVER);
    }

    private DecisionResult evaluateAt(RuleEngines replica, String ruleSet, Instant at) {
        caller.become("bank-a", "analyst-01", java.util.Set.of("rules:evaluate", "rules:simulate"));
        return replica.ruleEngine().evaluate(io.github.dhluong90.bpm.rules.api.DecisionRequest.of(ruleSet, SILVER)
                .withClock(at));
    }

    private io.github.dhluong90.bpm.rules.api.RuleSetVersion publish(RuleEngines replica, String ruleSet, byte[] yaml,
            int version) {
        caller.become("bank-a", "alice");
        if (version == 1) {
            replica.versionService().importRuleSet(ImportRequest.yaml(yaml));
        } else {
            replica.versionService().importVersion(ruleSet, ImportRequest.yaml(yaml));
        }
        long lock = replica.versionService().requestPublish(ruleSet, version, WriteOptions.none()).lockVersion();
        caller.become("bank-a", "bob");
        return replica.versionService().approve(ruleSet, version, new WriteOptions(null, lock, null));
    }

    private io.github.dhluong90.bpm.rules.api.RuleSetVersion publish(RuleEngines replica, String ruleSet, String yaml,
            int version) {
        return publish(replica, ruleSet, yaml.getBytes(StandardCharsets.UTF_8), version);
    }

    private static String withDefaultFee(long fee) {
        return new String(CardFee.yaml(), StandardCharsets.UTF_8).replace("\"50000 VND\"", "\"" + fee + " VND\"");
    }

    /** A first-hit table of 200 rows: row i matches txCount >= 200 - i, the last row is the default. */
    private static String twoHundredRows(String name) {
        StringBuilder yaml = new StringBuilder("""
                schemaVersion: 1
                ruleSet: %s
                hitPolicy: first
                inputs:
                  - { name: txCount, type: integer }
                outputs:
                  - { name: fee, type: money }
                rules:
                """.formatted(name));
        for (int i = 1; i < 200; i++) {
            yaml.append("  - id: R").append(i).append('\n')
                    .append("    when: { txCount: \">= ").append(200 - i).append("\" }\n")
                    .append("    then: { fee: \"").append(i * 100).append(" VND\" }\n");
        }
        yaml.append("  - id: R200\n    when: {}\n    then: { fee: \"50000 VND\" }\n");
        return yaml.toString();
    }
}
