package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.internal.store.JobStore.JobRow;
import io.github.dhluong90.bpm.process.spi.HandlerResult;
import io.github.dhluong90.bpm.process.spi.ServiceCall;
import io.github.dhluong90.bpm.process.spi.ServiceTaskHandler;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PRE-006, timer re-lease, deterministic (#655): no sleep anywhere. Time is the shared {@link TestClock}, a lease
 * ends when the test sets the clock to its {@code locked_until}, and a node that dies is a replica that claimed and
 * never fired (or one that is closed and rebuilt under the same id). Two replicas race through a barrier, not a delay.
 * The example is the debit card whose activation reminder is sent once, whichever node fires the timer.
 */
class TimerReleaseTest {

    private static final Instant ISSUED = Instant.parse("2027-03-01T02:00:00Z");
    private static final Instant DUE = ISSUED.plus(Duration.ofDays(3));
    private static final Duration LEASE = Duration.ofSeconds(60);

    private final Map<String, AtomicInteger> reminders = new ConcurrentHashMap<>();
    private final ServiceTaskHandler sendReminder = new ServiceTaskHandler() {
        @Override
        public String name() {
            return "cardReminders.send";
        }

        @Override
        public HandlerResult handle(ServiceCall call) {
            reminders.computeIfAbsent(String.valueOf(call.inputs().get("cardId")), k -> new AtomicInteger())
                    .incrementAndGet();
            return HandlerResult.completed(Map.of("channel", "SMS"));
        }
    };
    private final TestClock clock = TestClock.at(ISSUED);

    @BeforeEach
    void clean() {
        TestEngine.reset();
    }

    private TestEngine node(String id) {
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

    private static int timerFired(String instanceId) {
        return count("SELECT count(*) FROM pre_history WHERE instance_id = ? AND event_type = 'TIMER_FIRED'", instanceId);
    }

    /** Everything the instance wrote that a second firing would duplicate. */
    private void assertSingleFiring(TestEngine reader, String instanceId, String card) {
        assertThat(reminders.get(card)).as("reminder sends of %s", card).hasValue(1);
        assertThat(timerFired(instanceId)).as("TIMER_FIRED history rows").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM pre_history WHERE instance_id = ? AND node_id = 'send-reminder' "
                + "AND event_type = 'NODE_ENTERED'", instanceId)).as("send-reminder entered").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM pre_task WHERE instance_id = ?", instanceId)).as("call tasks").isEqualTo(1);
        assertThat(reader.services.instance(instanceId).tasks()).singleElement()
                .satisfies(t -> assertThat(t.nodeId()).isEqualTo("call-customer"));
        assertThat(count("SELECT count(*) FROM pre_timer WHERE instance_id = ? AND status = 'DONE'", instanceId))
                .isEqualTo(1);
    }

    @Test
    void PRE_006_dead_node_a_timer_is_fired_once_by_node_b_after_the_lease() {
        // Given node A claims the due timer and dies before firing it: its engine is closed, nothing was fired
        TestEngine a = node("node-a");
        TestEngine b = node("node-b");
        deploy(a);
        ProcessInstance card = issue(a, "CARD-1001");
        clock.set(DUE);
        List<JobRow> claimed = a.jobs.claim();
        assertThat(claimed).hasSize(1);
        Instant leaseEnd = DUE.plus(LEASE);
        assertThat(((Timestamp) job(card.id()).get("locked_until")).toInstant()).isEqualTo(leaseEnd);
        a.close();

        // When node B polls one millisecond before the lease ends, it fires nothing
        clock.set(leaseEnd.minusMillis(1));
        assertThat(b.jobs.pollOnce()).isZero();
        assertThat(reminders).isEmpty();
        assertThat(job(card.id())).containsEntry("locked_by", "node-a").containsEntry("attempt", 1);

        // When the clock reaches the lease end exactly, node B takes the timer over and fires it once
        clock.set(leaseEnd);
        assertThat(b.jobs.pollOnce()).isEqualTo(1);

        // Then one firing is on record, under attempt 2, and a further poll finds nothing
        assertThat(job(card.id())).containsEntry("status", "DONE").containsEntry("attempt", 2);
        assertSingleFiring(b, card.id(), "CARD-1001");
        assertThat(b.jobs.pollOnce()).isZero();
        assertSingleFiring(b, card.id(), "CARD-1001");
    }

    @Test
    void PRE_006_node_a_back_late_with_its_stale_claim_is_refused_in_each_state_of_the_takeover() {
        // Given node A claimed the timer and went quiet
        TestEngine a = node("node-a");
        TestEngine b = node("node-b");
        deploy(a);
        ProcessInstance card = issue(a, "CARD-1002");
        clock.set(DUE);
        JobRow stale = a.jobs.claim().get(0);
        Instant leaseEnd = DUE.plus(LEASE);
        int eventsOfA = a.events.size();

        // When A wakes after its lease ended and before anyone took the timer over, it must not fire on an expired lease
        clock.set(leaseEnd);
        assertThat(a.jobs.fire(stale)).as("A on an expired lease, timer not yet re-leased").isFalse();
        assertThat(reminders).isEmpty();
        assertThat(timerFired(card.id())).isZero();

        // And when B has re-leased it (attempt 2) but not yet fired, A's attempt 1 is still refused
        List<JobRow> reclaimed = b.jobs.claim();
        assertThat(reclaimed).hasSize(1);
        assertThat(reclaimed.get(0).attempt()).isEqualTo(2);
        clock.set(leaseEnd.plusSeconds(1));
        assertThat(a.jobs.fire(stale)).as("A while B holds the lease").isFalse();
        assertThat(reminders).isEmpty();
        assertThat(job(card.id())).containsEntry("locked_by", "node-b").containsEntry("attempt", 2);

        // And B then fires it once
        assertThat(b.jobs.fire(reclaimed.get(0))).isTrue();

        // And when A, restarted under the same id (a new JVM), presents the same stale claim after B finished, it is refused
        a.close();
        TestEngine restartedA = node("node-a");
        assertThat(restartedA.jobs.fire(stale)).as("A after B finished").isFalse();
        assertThat(restartedA.jobs.pollOnce()).isZero();

        // Then the timer fired once, nothing of A's stale attempts reached the history or the event stream
        assertSingleFiring(b, card.id(), "CARD-1002");
        assertThat(job(card.id())).containsEntry("status", "DONE").containsEntry("attempt", 2);
        assertThat(a.events).as("A published nothing after its claim").hasSize(eventsOfA);
        assertThat(restartedA.events).isEmpty();
    }

    @Test
    void PRE_006_stale_claim_cannot_complete_the_row_it_no_longer_holds() {
        // Given B holds a live claim (attempt 2) taken over from A (attempt 1)
        TestEngine a = node("node-a");
        TestEngine b = node("node-b");
        deploy(a);
        ProcessInstance card = issue(a, "CARD-1003");
        clock.set(DUE);
        JobRow stale = a.jobs.claim().get(0);
        clock.set(DUE.plus(LEASE));
        JobRow live = b.jobs.claim().get(0);
        JdbcJobStore store = new JdbcJobStore(TestEngine.dataSource());

        // When A tries the store operations that complete or give back a claim, with its attempt 1
        boolean locked = store.lockClaim(stale.tenantId(), stale.id(), "node-a", stale.attempt(), clock.instant()).isPresent();
        boolean done = store.markDone(stale.tenantId(), stale.id(), "node-a", stale.attempt(), clock.instant());
        boolean released = store.release(stale.tenantId(), stale.id(), "node-a", stale.attempt(), clock.instant(), true,
                clock.instant());
        // and with B's id but A's attempt number (a stale attempt under a reused id)
        boolean doneWithOldAttempt = store.markDone(live.tenantId(), live.id(), "node-b", stale.attempt(), clock.instant());

        // Then every one is refused and B's claim is untouched
        assertThat(locked).isFalse();
        assertThat(done).isFalse();
        assertThat(released).isFalse();
        assertThat(doneWithOldAttempt).isFalse();
        assertThat(job(card.id())).containsEntry("status", "CLAIMED").containsEntry("locked_by", "node-b")
                .containsEntry("attempt", 2);
        assertThat(b.jobs.fire(live)).isTrue();
        assertSingleFiring(b, card.id(), "CARD-1003");
    }

    @Test
    void PRE_006_two_nodes_racing_for_one_due_timer_only_one_fires_it() throws Exception {
        TestEngine a = node("node-a");
        TestEngine b = node("node-b");
        deploy(a);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 30 rounds, one fresh due timer each; both nodes leave a barrier together and poll
            for (int round = 0; round < 30; round++) {
                String card = "CARD-R%03d".formatted(round);
                clock.set(ISSUED.plus(Duration.ofDays(10L * round)));
                ProcessInstance instance = issue(a, card);
                clock.set(clock.instant().plus(Duration.ofDays(3)));
                int fired = raceOnce(pool, List.of(a, b));
                assertThat(fired).as("timers fired in round %d", round).isEqualTo(1);
                assertSingleFiring(a, instance.id(), card);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void PRE_006_two_nodes_racing_for_a_timer_whose_holder_died_only_one_fires_it() throws Exception {
        TestEngine dead = node("node-dead");
        TestEngine a = node("node-a");
        TestEngine b = node("node-b");
        deploy(dead);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 30; round++) {
                String card = "CARD-D%03d".formatted(round);
                clock.set(ISSUED.plus(Duration.ofDays(10L * round)));
                ProcessInstance instance = issue(dead, card);
                Instant due = clock.instant().plus(Duration.ofDays(3));
                clock.set(due);
                assertThat(dead.jobs.claim()).as("claimed by the node that dies").hasSize(1);
                clock.set(due.plus(LEASE));
                int fired = raceOnce(pool, List.of(a, b));
                assertThat(fired).as("timers fired in round %d", round).isEqualTo(1);
                assertThat(job(instance.id())).containsEntry("attempt", 2);
                assertSingleFiring(a, instance.id(), card);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Both nodes announce themselves, pass one barrier, and only then claim. Each records how many contenders had
     * arrived when it was released; the race counts only if both saw the other there. Claims are summed before firing.
     */
    private static int raceOnce(ExecutorService pool, List<TestEngine> nodes) throws Exception {
        CyclicBarrier start = new CyclicBarrier(nodes.size());
        AtomicInteger arrived = new AtomicInteger();
        List<Future<int[]>> results = new ArrayList<>();
        for (TestEngine node : nodes) {
            results.add(pool.submit(() -> {
                arrived.incrementAndGet();
                start.await(30, TimeUnit.SECONDS);
                int seenAtRelease = arrived.get();
                List<JobRow> claimed = node.jobs.claim();
                int fired = 0;
                for (JobRow job : claimed) {
                    fired += node.jobs.fire(job) ? 1 : 0;
                }
                return new int[] {seenAtRelease, claimed.size(), fired};
            }));
        }
        int fired = 0;
        int claims = 0;
        for (Future<int[]> result : results) {
            int[] r = result.get(60, TimeUnit.SECONDS);
            assertThat(r[0]).as("contenders in the claim window together").isEqualTo(nodes.size());
            claims += r[1];
            fired += r[2];
        }
        assertThat(claims).as("claims granted for one due timer").isEqualTo(1);
        return fired;
    }
}
