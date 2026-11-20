package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.internal.store.JobStore.JobRow;
import io.github.dhluong90.bpm.process.spi.HandlerResult;
import io.github.dhluong90.bpm.process.spi.ServiceCall;
import io.github.dhluong90.bpm.process.spi.ServiceTaskHandler;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * PRE-006 on PostgreSQL: timers are claimed with {@code FOR UPDATE SKIP LOCKED} and a lease, fired exactly once
 * across replicas, and a dead replica's claim is taken over after its lease. Each replica is a separate engine with
 * its own replica id on one database, as separate copies of the host would be. The example is a debit card that was
 * issued and not activated: three days later the customer gets one reminder and the contact centre one call task.
 */
class ExactlyOnceTimersTest {

    private static final Instant ISSUED = Instant.parse("2027-03-01T02:00:00Z");
    private static final Instant DUE = ISSUED.plus(Duration.ofDays(3));

    /** Counts reminders per card: a reminder sent twice is the failure this slice prevents. */
    private final Map<String, AtomicInteger> reminders = new ConcurrentHashMap<>();
    private final AtomicBoolean failNextReminder = new AtomicBoolean();
    private final ServiceTaskHandler sendReminder = new ServiceTaskHandler() {
        @Override
        public String name() {
            return "cardReminders.send";
        }

        @Override
        public HandlerResult handle(ServiceCall call) {
            String card = String.valueOf(call.inputs().get("cardId"));
            reminders.computeIfAbsent(card, k -> new AtomicInteger()).incrementAndGet();
            if (failNextReminder.compareAndSet(true, false)) {
                throw new IllegalStateException("SMS gateway adapter not ready");
            }
            return HandlerResult.completed(Map.of("channel", "SMS"));
        }
    };
    private final TestClock clock = TestClock.at(ISSUED);

    @BeforeEach
    void clean() {
        TestEngine.reset();
    }

    private TestEngine replica(String id) {
        return TestEngine.replica(id, clock).handlers(sendReminder).build();
    }

    private static void deploy(TestEngine engine) {
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                TestEngine.resource("/bpmn/card-activation-reminder.bpmn"), null));
    }

    private static ProcessInstance issue(TestEngine engine, String cardId) {
        return engine.services.start(new StartRequest("card-activation-reminder", cardId, Map.of("cardId", cardId), null));
    }

    private static int count(String sql, Object... params) {
        return TestEngine.jdbc().sql(sql).params(params).query(Integer.class).single();
    }

    private static Map<String, Object> job(String instanceId) {
        return TestEngine.jdbc().sql("SELECT status, locked_by, locked_until, attempt FROM pre_timer WHERE instance_id = ?")
                .param(instanceId).query().singleRow();
    }

    @Test
    void PRE_006_timer_lease_recovers_after_worker_crash() {
        // Given a card issued at T0 - 3 days, its reminder timer due at T0; pod 1 claims it and dies before firing.
        TestEngine pod1 = replica("pod-1");
        TestEngine pod2 = replica("pod-2");
        deploy(pod1);
        ProcessInstance card = issue(pod1, "CARD-4111");
        assertThat(card.tokens()).anyMatch(t -> t.nodeId().equals("wait-activation") && t.status().equals("WAITING_TIMER"));
        clock.set(DUE);
        List<JobRow> claimedByPod1 = pod1.jobs.claim();
        assertThat(claimedByPod1).hasSize(1);
        assertThat(job(card.id())).containsEntry("status", "CLAIMED").containsEntry("locked_by", "pod-1")
                .containsEntry("attempt", 1);
        assertThat(((java.sql.Timestamp) job(card.id()).get("locked_until")).toInstant()).isEqualTo(DUE.plusSeconds(60));

        // When pod 2 polls at T0 + 10 s, the lease still holds
        clock.set(DUE.plusSeconds(10));
        int firedWhileLeased = pod2.jobs.pollOnce();

        // Then pod 2 skips the timer
        assertThat(firedWhileLeased).isZero();
        assertThat(reminders).isEmpty();
        assertThat(job(card.id())).containsEntry("locked_by", "pod-1");

        // When pod 2 polls at T0 + 65 s, the lease has expired
        clock.set(DUE.plusSeconds(65));
        int firedAfterLease = pod2.jobs.pollOnce();

        // Then pod 2 reclaims it and fires it once
        assertThat(firedAfterLease).isEqualTo(1);
        assertThat(reminders.get("CARD-4111")).hasValue(1);
        assertThat(job(card.id())).containsEntry("status", "DONE").containsEntry("attempt", 2);
        assertThat(count("SELECT count(*) FROM pre_history WHERE instance_id = ? AND event_type = 'TIMER_FIRED'",
                card.id())).isEqualTo(1);
        assertThat(pod2.services.instance(card.id()).tasks()).singleElement()
                .satisfies(t -> assertThat(t.nodeId()).isEqualTo("call-customer"));

        // And pod 1, back with its stale claim, fires nothing and writes nothing
        assertThat(pod1.jobs.fire(claimedByPod1.get(0))).isFalse();
        assertThat(pod1.jobs.pollOnce()).isZero();
        assertThat(reminders.get("CARD-4111")).hasValue(1);
        assertThat(count("SELECT count(*) FROM pre_task WHERE instance_id = ?", card.id())).isEqualTo(1);
    }

    @Test
    void PRE_006_two_replicas_fire_each_timer_exactly_once() throws Exception {
        // Given 120 cards whose reminders are due, and a replica that claimed 10 of them and stalled past its lease
        int cards = 120;
        TestEngine stalled = TestEngine.replica("pod-stalled", clock).handlers(sendReminder).batchSize(10).build();
        deploy(stalled);
        List<String> instances = new ArrayList<>();
        for (int i = 0; i < cards; i++) {
            instances.add(issue(stalled, "CARD-%04d".formatted(i)).id());
        }
        clock.set(DUE);
        List<JobRow> staleClaims = stalled.jobs.claim();
        assertThat(staleClaims).hasSize(10);
        clock.set(DUE.plusSeconds(61));
        List<TestEngine> replicas = List.of(
                TestEngine.replica("pod-1", clock).handlers(sendReminder).batchSize(7).build(),
                TestEngine.replica("pod-2", clock).handlers(sendReminder).batchSize(7).build(),
                TestEngine.replica("pod-3", clock).handlers(sendReminder).batchSize(7).build(),
                TestEngine.replica("pod-4", clock).handlers(sendReminder).batchSize(7).build());

        // When the four replicas poll the same table at the same time until no timer is open
        ExecutorService pool = Executors.newFixedThreadPool(replicas.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (TestEngine replica : replicas) {
            results.add(pool.submit(() -> {
                go.await();
                int fired = 0;
                for (int round = 0; round < 500 && openJobs() > 0; round++) {
                    fired += replica.jobs.pollOnce();
                }
                return fired;
            }));
        }
        go.countDown();
        int firedTotal = 0;
        for (Future<Integer> result : results) {
            firedTotal += result.get(2, TimeUnit.MINUTES);
        }
        pool.shutdown();
        // and the stalled replica wakes up and fires the claims it still believes it holds
        int firedByStalled = 0;
        for (JobRow stale : staleClaims) {
            firedByStalled += stalled.jobs.fire(stale) ? 1 : 0;
        }

        // Then every timer fired exactly once, and the stalled replica fired none
        assertThat(firedTotal).isEqualTo(cards);
        assertThat(firedByStalled).isZero();
        assertThat(reminders).hasSize(cards);
        assertThat(reminders.values()).allSatisfy(n -> assertThat(n).hasValue(1));
        for (String instance : instances) {
            assertThat(count("SELECT count(*) FROM pre_history WHERE instance_id = ? AND event_type = 'TIMER_FIRED'",
                    instance)).as("TIMER_FIRED of %s", instance).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM pre_task WHERE instance_id = ?", instance)).isEqualTo(1);
        }
        assertThat(count("SELECT count(*) FROM pre_timer WHERE status = 'DONE'")).isEqualTo(cards);
        assertThat(count("SELECT count(DISTINCT locked_by) FROM pre_timer WHERE status = 'DONE'"))
                .as("the work spread over more than one replica").isGreaterThan(1);
    }

    @Test
    void PRE_006_failed_firing_rolls_back_and_is_retried_after_the_backoff() {
        // Given a due reminder whose first sending fails inside the fire transaction
        TestEngine pod1 = TestEngine.replica("pod-1", clock).handlers(sendReminder).retryBackoff(Duration.ofSeconds(30))
                .build();
        deploy(pod1);
        ProcessInstance card = issue(pod1, "CARD-5500");
        clock.set(DUE);
        failNextReminder.set(true);

        // When pod 1 polls, then polls again after the back-off
        int firstPoll = pod1.jobs.pollOnce();
        Map<String, Object> afterFailure = job(card.id());
        int earlyPoll = pod1.jobs.pollOnce();
        clock.set(DUE.plusSeconds(31));
        int retryPoll = pod1.jobs.pollOnce();

        // Then the failed firing left no trace but the attempt count, and the retry fired once
        assertThat(firstPoll).isZero();
        assertThat(afterFailure).containsEntry("status", "DUE").containsEntry("attempt", 1)
                .containsEntry("locked_by", null);
        assertThat(earlyPoll).as("not due again before the back-off").isZero();
        assertThat(retryPoll).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM pre_history WHERE instance_id = ? AND event_type = 'TIMER_FIRED'",
                card.id())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM pre_task WHERE instance_id = ?", card.id())).isEqualTo(1);
    }

    @Test
    void PRE_006_cancelled_claim_is_never_fired() {
        // Given a claimed timer
        TestEngine pod1 = replica("pod-1");
        deploy(pod1);
        ProcessInstance card = issue(pod1, "CARD-6600");
        clock.set(DUE);
        List<JobRow> claimed = pod1.jobs.claim();
        JdbcJobStore store = new JdbcJobStore(TestEngine.dataSource());

        // When the timer is cancelled before the claim fires
        int cancelled = store.cancelJob("bank-a", claimed.get(0).id(), DUE);

        // Then the fire finds no claim and writes nothing
        assertThat(cancelled).isEqualTo(1);
        assertThat(pod1.jobs.fire(claimed.get(0))).isFalse();
        assertThat(reminders).isEmpty();
        assertThat(job(card.id())).containsEntry("status", "CANCELLED");
    }

    @Test
    void PRE_006_another_tenants_job_is_refused_and_nothing_is_written() {
        // Given a timer of bank-a
        TestEngine pod1 = replica("pod-1");
        deploy(pod1);
        ProcessInstance card = issue(pod1, "CARD-7700");
        JobRow bankA = TestEngine.jdbc().sql("SELECT id FROM pre_timer WHERE instance_id = ?").param(card.id())
                .query(String.class).optional().map(id -> new JdbcJobStore(TestEngine.dataSource()).findJob("bank-a", id)
                        .orElseThrow()).orElseThrow();
        JdbcJobStore store = new JdbcJobStore(TestEngine.dataSource());

        // When bank-b reads, cancels or schedules against bank-a's instance
        var read = store.findJob("bank-b", bankA.id());
        int cancelled = store.cancelJob("bank-b", bankA.id(), ISSUED);
        assertThatThrownBy(() -> store.insertJob(JobRow.due("bank-b", "01JQ0000000000000000000000", card.id(),
                bankA.definitionId(), bankA.tokenId(), "TIMER", "wait-activation", ISSUED, Duration.ofSeconds(60), 3,
                "{}"), ISSUED)).isInstanceOf(DataIntegrityViolationException.class);

        // Then nothing is found, nothing changes and nothing is written
        assertThat(read).isEmpty();
        assertThat(cancelled).isZero();
        assertThat(job(card.id())).containsEntry("status", "DUE");
        assertThat(count("SELECT count(*) FROM pre_timer WHERE tenant_id = 'bank-b'")).isZero();
    }

    private static int openJobs() {
        return count("SELECT count(*) FROM pre_timer WHERE status IN ('DUE','CLAIMED')");
    }
}
