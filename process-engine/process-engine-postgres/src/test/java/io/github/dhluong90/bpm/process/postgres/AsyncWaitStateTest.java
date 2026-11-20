package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import io.github.dhluong90.bpm.process.api.AsyncStepNotFoundException;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.InputInvalidException;
import io.github.dhluong90.bpm.process.api.PermissionDeniedException;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.api.StepCompletion;
import io.github.dhluong90.bpm.process.internal.service.AsyncSettings;
import io.github.dhluong90.bpm.process.internal.service.AsyncStepExecutor;
import io.github.dhluong90.bpm.process.internal.service.ProcessServices;
import io.github.dhluong90.bpm.process.internal.store.JobStore.JobRow;
import io.github.dhluong90.bpm.process.spi.AdapterCall;
import io.github.dhluong90.bpm.process.spi.AdapterResult;
import io.github.dhluong90.bpm.process.spi.ServiceAdapter;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The Async Wait-State on PostgreSQL (ADR-0008): a loan application asks the credit bureau for a score through a
 * remote service step. The step's job is claimed and leased in one transaction, the bureau is called with no
 * connection held, and the result is recorded in a second transaction only while the attempt is current. Replicas are
 * separate engines with their own replica ids on one database and share one {@link TestClock}; no test sleeps.
 */
class AsyncWaitStateTest {

    private static final Instant T0 = Instant.parse("2027-03-01T02:00:00Z");
    private static final String BUREAU = "creditBureau.score";

    private final TestClock clock = TestClock.at(T0);
    private final List<TestEngine> engines = new ArrayList<>();
    /** Every call the bureau received, in order. */
    private final List<AdapterCall> calls = new CopyOnWriteArrayList<>();

    @BeforeEach
    void clean() {
        TestEngine.reset();
    }

    @AfterEach
    void stop() {
        engines.forEach(TestEngine::close);
    }

    /** A bureau stub: records the call, then answers with the function. */
    private ServiceAdapter bureau(Function<AdapterCall, AdapterResult> answer) {
        return new ServiceAdapter() {
            @Override
            public String name() {
                return BUREAU;
            }

            @Override
            public AdapterResult execute(AdapterCall call) {
                calls.add(call);
                return answer.apply(call);
            }
        };
    }

    private static AdapterResult score(int value) {
        return AdapterResult.completed(Map.of("score", value, "band", value >= 700 ? "A" : "C"));
    }

    private TestEngine replica(String id, ServiceAdapter adapter) {
        return track(TestEngine.replica(id, clock).adapters(adapter).build());
    }

    private TestEngine track(TestEngine engine) {
        engines.add(engine);
        engine.identity.permissions = java.util.Set.of("processes:deploy", "processes:start", "processes:read",
                "processes:task:work", "processes:cancel", "processes:step:complete");
        return engine;
    }

    private static void deploy(TestEngine engine) {
        deploy(engine, TestEngine.resource("/bpmn/loan-bureau-check.bpmn"));
    }

    private static void deploy(TestEngine engine, byte[] bpmn) {
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML, bpmn, null));
    }

    private static ProcessInstance apply(TestEngine engine, String applicationId) {
        return engine.services.start(new StartRequest("loan-bureau-check", applicationId,
                Map.of("nationalId", "079088001234", "amount", 250_000_000), null));
    }

    private static Map<String, Object> job(String instanceId) {
        return TestEngine.jdbc().sql("SELECT id, status, locked_by, locked_until, attempt, job_kind FROM pre_timer "
                + "WHERE instance_id = ?").param(instanceId).query().singleRow();
    }

    private static Instant lockedUntil(String instanceId) {
        return ((Timestamp) job(instanceId).get("locked_until")).toInstant();
    }

    private static int history(String instanceId) {
        return TestEngine.jdbc().sql("SELECT count(*) FROM pre_history WHERE instance_id = ?").param(instanceId)
                .query(Integer.class).single();
    }

    private static int history(String instanceId, String type) {
        return TestEngine.jdbc().sql("SELECT count(*) FROM pre_history WHERE instance_id = ? AND event_type = ?")
                .params(instanceId, type).query(Integer.class).single();
    }

    private static String tokenOn(TestEngine engine, String instanceId) {
        return engine.services.instance(instanceId).tokens().stream().filter(t -> t.kind().equals("FLOW")
                && !t.status().equals("COMPLETED")).map(t -> t.nodeId() + ":" + t.status()).findFirst().orElse("-");
    }

    // ---- PRE-009 ----------------------------------------------------------------------------------------------

    @Test
    void PRE_009_remote_step_executes_outside_db_transaction() throws Exception {
        // Given a host with a 2-connection pool, and a bureau that holds every call until all 10 are in flight
        CountDownLatch allInFlight = new CountDownLatch(10);
        CountDownLatch answer = new CountDownLatch(1);
        AtomicInteger insideTransaction = new AtomicInteger();
        ServiceAdapter slowBureau = bureau(call -> {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                insideTransaction.incrementAndGet();
            }
            allInFlight.countDown();
            try {
                if (!answer.await(30, TimeUnit.SECONDS)) {
                    return new AdapterResult.Failed("TEST_STALLED", "never released", false);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new AdapterResult.Failed("INTERRUPTED", "interrupted", true);
            }
            return score(712);
        });
        try (HikariDataSource pool = TestEngine.pool(2, Duration.ofMillis(500))) {
            TestEngine host = track(TestEngine.replica("pod-1", clock).dataSource(pool).adapters(slowBureau).build());
            deploy(host);
            List<String> applications = new ArrayList<>();
            for (int i = 1; i <= 10; i++) {
                applications.add(apply(host, "LOAN-2027-" + i).id());
            }

            // When the replica starts all 10 bureau calls while another thread keeps querying the database
            assertThat(host.async.pollOnce()).isEqualTo(10);
            assertThat(allInFlight.await(30, TimeUnit.SECONDS)).isTrue();
            int activeWhileCalling = pool.getHikariPoolMXBean().getActiveConnections();
            ExecutorService other = Executors.newSingleThreadExecutor();
            Future<Integer> queries = other.submit(() -> {
                int ok = 0;
                for (int i = 0; i < 20; i++) {
                    ok += org.springframework.jdbc.core.simple.JdbcClient.create(pool)
                            .sql("SELECT count(*) FROM pre_instance").query(Integer.class).single() >= 10 ? 1 : 0;
                }
                return ok;
            });
            int succeeded = queries.get(30, TimeUnit.SECONDS);
            other.shutdown();
            answer.countDown();
            assertThat(host.async.awaitIdle(Duration.ofSeconds(30))).isTrue();

            // Then no connection was held during the calls, every query got one, and every instance moved on once
            assertThat(activeWhileCalling).isZero();
            assertThat(succeeded).isEqualTo(20);
            assertThat(insideTransaction).hasValue(0);
            assertThat(calls).hasSize(10);
            for (String id : applications) {
                assertThat(tokenOn(host, id)).isEqualTo("underwrite:WAITING_USER");
                assertThat(job(id)).containsEntry("status", "DONE").containsEntry("attempt", 1);
                assertThat(host.services.instance(id).variables()).containsEntry("bureau",
                        Map.of("score", 712L, "band", "A"));
            }
        }
    }

    // ---- PRE-017 ----------------------------------------------------------------------------------------------

    @Test
    void PRE_017_async_lease_is_timeout_plus_margin() {
        // Given a bureau step with timeout PT2S and jobs.lease-margin 30 s; pod 1's bureau answers after 1.9 s
        AtomicBoolean pod2ClaimedDuringCall = new AtomicBoolean();
        TestEngine[] pod2 = new TestEngine[1];
        TestEngine pod1 = replica("pod-1", bureau(call -> {
            clock.advance(Duration.ofMillis(1_900));
            pod2ClaimedDuringCall.set(!pod2[0].async.claim(16).isEmpty() || pod2[0].jobs.pollOnce() > 0);
            return score(688);
        }));
        pod2[0] = replica("pod-2", bureau(call -> score(1)));
        deploy(pod1);
        String loan = apply(pod1, "LOAN-2027-0042").id();
        assertThat(tokenOn(pod1, loan)).isEqualTo("bureau-score:WAITING_ASYNC");
        assertThat(job(loan)).containsEntry("status", "DUE").containsEntry("job_kind", "ASYNC_SERVICE");

        // When pod 1 claims at T0 and pod 2 polls during the call and after it
        List<JobRow> claimed = pod1.async.claim(16);

        // Then TX1 sets locked_until = T0 + 32 s, pod 2 never claims the live attempt, and TX2 records the result once
        assertThat(claimed).hasSize(1);
        assertThat(lockedUntil(loan)).isEqualTo(T0.plusSeconds(32));
        assertThat(claimed.get(0).leaseDuration()).isEqualTo(Duration.ofSeconds(32));
        assertThat(pod1.async.runNow(claimed.get(0))).contains(StepCompletion.Outcome.ACCEPTED);
        assertThat(pod2ClaimedDuringCall).isFalse();
        assertThat(pod2[0].async.claim(16)).isEmpty();
        assertThat(calls).hasSize(1);
        assertThat(job(loan)).containsEntry("status", "DONE").containsEntry("attempt", 1);
        assertThat(history(loan, "ASYNC_RESULT_ACCEPTED")).isEqualTo(1);
        assertThat(tokenOn(pod1, loan)).isEqualTo("underwrite:WAITING_USER");
    }

    @Test
    void PRE_017_a_crashed_attempt_is_reclaimed_at_the_end_of_its_lease_with_a_new_attempt_id() {
        // Given pod 1 claims the bureau step at T0 and crashes right after TX1
        TestEngine pod1 = replica("pod-1", bureau(call -> score(700)));
        TestEngine pod2 = replica("pod-2", bureau(call -> score(731)));
        deploy(pod1);
        String loan = apply(pod1, "LOAN-2027-0043").id();
        JobRow first = pod1.async.claim(16).get(0);

        // When pod 2 polls just before and at T0 + 32 s
        clock.set(T0.plusSeconds(32).minusMillis(1));
        List<JobRow> early = pod2.async.claim(16);
        clock.set(T0.plusSeconds(32));
        List<JobRow> late = pod2.async.claim(16);

        // Then pod 2 skips the job until the lease ends, then reclaims it as attempt 2 with a new attempt id
        assertThat(early).isEmpty();
        assertThat(late).hasSize(1);
        assertThat(late.get(0).attempt()).isEqualTo(2);
        assertThat(job(loan)).containsEntry("locked_by", "pod-2").containsEntry("attempt", 2);
        assertThat(pod2.async.runNow(late.get(0))).contains(StepCompletion.Outcome.ACCEPTED);
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).attempt()).isEqualTo(2);
        assertThat(calls.get(0).attemptId()).isEqualTo(ProcessServices.attemptId(first.id(), 2))
                .isNotEqualTo(ProcessServices.attemptId(first.id(), 1));
        assertThat(calls.get(0).deadline()).isEqualTo(T0.plusSeconds(34));
        assertThat(pod2.services.instance(loan).variables()).containsEntry("bureau", Map.of("score", 731L, "band", "A"));
    }

    // ---- TR-ASY-02 --------------------------------------------------------------------------------------------

    @Test
    void TR_ASY_02_a_result_after_takeover_is_discarded_and_changes_nothing() {
        // Given pod 1 claims at T0 and stalls past its lease; pod 2 takes the step over at T0 + 32 s
        TestEngine pod1 = replica("pod-1", bureau(call -> score(555)));
        TestEngine pod2 = replica("pod-2", bureau(call -> score(744)));
        deploy(pod1);
        String loan = apply(pod1, "LOAN-2027-0044").id();
        JobRow stalled = pod1.async.claim(16).get(0);
        clock.set(T0.plusSeconds(32));
        JobRow takeover = pod2.async.claim(16).get(0);
        int historyBefore = history(loan);

        // When pod 1's bureau answer finally arrives
        Optional<StepCompletion.Outcome> late = pod1.async.runNow(stalled);

        // Then it is discarded: no history, the token still waits, pod 2 still owns the step and completes it
        assertThat(late).contains(StepCompletion.Outcome.DISCARDED);
        assertThat(history(loan)).isEqualTo(historyBefore);
        assertThat(tokenOn(pod1, loan)).isEqualTo("bureau-score:WAITING_ASYNC");
        assertThat(job(loan)).containsEntry("status", "CLAIMED").containsEntry("locked_by", "pod-2")
                .containsEntry("attempt", 2);
        assertThat(pod2.async.runNow(takeover)).contains(StepCompletion.Outcome.ACCEPTED);
        assertThat(pod2.services.instance(loan).variables()).containsEntry("bureau", Map.of("score", 744L, "band", "A"));
    }

    @Test
    void TR_ASY_02_a_result_after_the_lease_ended_is_discarded_even_without_a_takeover() {
        TestEngine pod1 = replica("pod-1", bureau(call -> {
            clock.advance(Duration.ofSeconds(33));
            return score(600);
        }));
        deploy(pod1);
        String loan = apply(pod1, "LOAN-2027-0045").id();
        int historyBefore = history(loan);

        assertThat(pod1.async.runNow(pod1.async.claim(16).get(0))).contains(StepCompletion.Outcome.DISCARDED);

        assertThat(history(loan)).isEqualTo(historyBefore);
        assertThat(job(loan)).containsEntry("status", "CLAIMED").containsEntry("attempt", 1);
        assertThat(pod1.async.claim(16)).singleElement().extracting(JobRow::attempt).isEqualTo(2);
    }

    // ---- completion API ---------------------------------------------------------------------------------------

    @Test
    void a_handed_off_call_is_completed_once_through_the_completion_api() {
        // Given a bureau that takes the request and calls back later
        TestEngine pod1 = replica("pod-1", bureau(call -> new AdapterResult.Accepted()));
        deploy(pod1);
        String loan = apply(pod1, "LOAN-2027-0046").id();
        JobRow claimed = pod1.async.claim(16).get(0);
        assertThat(pod1.async.runNow(claimed)).isEmpty();
        assertThat(job(loan)).containsEntry("status", "CLAIMED");
        String attemptId = calls.get(0).attemptId();
        assertThat(calls.get(0).jobId()).isEqualTo(claimed.id());

        // When the callback arrives on another replica, and the partner repeats it
        TestEngine pod2 = replica("pod-2", bureau(call -> score(1)));
        clock.advance(Duration.ofSeconds(5));
        StepCompletion first = pod2.services.complete(claimed.id(), attemptId, score(705));
        int historyAfterFirst = history(loan);
        StepCompletion repeat = pod2.services.complete(claimed.id(), attemptId, score(705));

        // Then the first is accepted and moves the loan on; the repeat is a duplicate and writes nothing
        assertThat(first.outcome()).isEqualTo(StepCompletion.Outcome.ACCEPTED);
        assertThat(repeat.outcome()).isEqualTo(StepCompletion.Outcome.DUPLICATE);
        assertThat(history(loan)).isEqualTo(historyAfterFirst);
        assertThat(tokenOn(pod2, loan)).isEqualTo("underwrite:WAITING_USER");
        assertThat(job(loan)).containsEntry("status", "DONE");
        assertThat(TestEngine.jdbc().sql("SELECT actor_id FROM pre_history WHERE instance_id = ? AND event_type = "
                + "'ASYNC_RESULT_ACCEPTED'").param(loan).query(String.class).single()).isEqualTo("pipeline");
    }

    @Test
    void a_completion_for_another_tenants_step_is_refused_and_writes_nothing() {
        TestEngine pod1 = replica("pod-1", bureau(call -> new AdapterResult.Accepted()));
        deploy(pod1);
        String loan = apply(pod1, "LOAN-2027-0047").id();
        JobRow claimed = pod1.async.claim(16).get(0);
        pod1.async.runNow(claimed);
        String attemptId = calls.get(0).attemptId();
        int historyBefore = history(loan);

        pod1.identity.as("bank-b", "partner-b", "processes:step:complete");
        assertThatThrownBy(() -> pod1.services.complete(claimed.id(), attemptId, score(800)))
                .isInstanceOf(AsyncStepNotFoundException.class)
                .extracting(e -> ((AsyncStepNotFoundException) e).code()).isEqualTo("ASYNC_STEP_NOT_FOUND");

        pod1.identity.as("bank-a", "teller-lan", "processes:read");
        assertThatThrownBy(() -> pod1.services.complete(claimed.id(), attemptId, score(800)))
                .isInstanceOf(PermissionDeniedException.class);

        pod1.identity.as("bank-a", "partner-a", "processes:step:complete");
        assertThatThrownBy(() -> pod1.services.complete(claimed.id(), attemptId, new AdapterResult.Accepted()))
                .isInstanceOf(InputInvalidException.class);

        assertThat(history(loan)).isEqualTo(historyBefore);
        assertThat(job(loan)).containsEntry("status", "CLAIMED").containsEntry("attempt", 1);
    }

    @Test
    void a_failed_attempt_is_retried_with_a_new_attempt_id_and_a_business_error_is_not() {
        AtomicInteger answers = new AtomicInteger();
        TestEngine pod1 = replica("pod-1", bureau(call -> answers.incrementAndGet() == 1
                ? new AdapterResult.Failed("BUREAU_UNAVAILABLE", "503 from the bureau", true)
                : new AdapterResult.BusinessError("APPLICANT_UNKNOWN", "no file at the bureau")));
        deploy(pod1);
        String loan = apply(pod1, "LOAN-2027-0048").id();

        assertThat(pod1.async.runNow(pod1.async.claim(16).get(0))).contains(StepCompletion.Outcome.ACCEPTED);
        assertThat(job(loan)).containsEntry("status", "DUE");
        assertThat(pod1.async.claim(16)).isEmpty();
        clock.advance(Duration.ofSeconds(10));
        JobRow retry = pod1.async.claim(16).get(0);
        assertThat(pod1.async.runNow(retry)).contains(StepCompletion.Outcome.ACCEPTED);

        assertThat(calls).extracting(AdapterCall::attempt).containsExactly(1, 2);
        assertThat(calls.get(0).attemptId()).isNotEqualTo(calls.get(1).attemptId());
        assertThat(job(loan)).containsEntry("status", "FAILED").containsEntry("attempt", 2);
        assertThat(history(loan, "ASYNC_ATTEMPT_FAILED")).isEqualTo(2);
        assertThat(tokenOn(pod1, loan)).isEqualTo("bureau-score:WAITING_ASYNC");
    }

    @Test
    void an_attempt_abandoned_at_its_timeout_frees_its_slot_and_is_retried() throws Exception {
        CountDownLatch never = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        TestEngine pod1 = track(TestEngine.replica("pod-1", clock)
                .async(new AsyncSettings(Duration.ofSeconds(30), Duration.ofSeconds(30), 2))
                .adapters(bureau(call -> {
                    try {
                        never.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        interrupted.set(true);
                    }
                    return score(1);
                })).build());
        byte[] fast = new String(TestEngine.resource("/bpmn/loan-bureau-check.bpmn"), StandardCharsets.UTF_8)
                .replace("horus:timeout=\"PT2S\"", "horus:timeout=\"PT0.2S\"").getBytes(StandardCharsets.UTF_8);
        deploy(pod1, fast);
        String loan = apply(pod1, "LOAN-2027-0049").id();

        assertThat(pod1.async.pollOnce()).isEqualTo(1);
        assertThat(pod1.async.freeSlots()).isEqualTo(1);
        assertThat(pod1.async.awaitIdle(Duration.ofSeconds(30))).isTrue();

        assertThat(interrupted).isTrue();
        assertThat(job(loan)).containsEntry("status", "DUE").containsEntry("attempt", 1);
        assertThat(TestEngine.jdbc().sql("SELECT details->>'errorCode' FROM pre_history WHERE instance_id = ? AND "
                + "event_type = 'ASYNC_ATTEMPT_FAILED'").param(loan).query(String.class).single())
                .isEqualTo(AsyncStepExecutor.TIMEOUT);
        assertThat(pod1.async.freeSlots()).isEqualTo(2);
    }

    // ---- PRE-041 ----------------------------------------------------------------------------------------------

    @Test
    void PRE_041_cancel_during_adapter_call_discards_late_result() {
        // Given the bureau step's job claimed by pod 1, with the bureau still working
        TestEngine[] pod = new TestEngine[1];
        pod[0] = replica("pod-1", bureau(call -> {
            // When the instance is cancelled at 0.5 s and the bureau answers at 2 s
            clock.advance(Duration.ofMillis(500));
            ProcessInstance current = pod[0].services.instance(call.instanceId());
            pod[0].services.cancel(call.instanceId(), current.lockVersion(), "Applicant withdrew", "cancel-0050");
            clock.advance(Duration.ofMillis(1_500));
            return score(690);
        }));
        deploy(pod[0]);
        String loan = apply(pod[0], "LOAN-2027-0050").id();
        JobRow claimed = pod[0].async.claim(16).get(0);

        Optional<StepCompletion.Outcome> outcome = pod[0].async.runNow(claimed);

        // Then TX2 writes only LATE_RESULT_DISCARDED with the attempt id; the instance stays cancelled
        assertThat(outcome).contains(StepCompletion.Outcome.DISCARDED);
        assertThat(pod[0].services.instance(loan).status()).isEqualTo("CANCELLED");
        assertThat(job(loan)).containsEntry("status", "CANCELLED");
        assertThat(history(loan, "TOKEN_CANCELLED")).isEqualTo(1);
        assertThat(TestEngine.jdbc().sql("SELECT attempt_id FROM pre_history WHERE instance_id = ? AND event_type = "
                + "'LATE_RESULT_DISCARDED'").param(loan).query(String.class).single())
                .isEqualTo(ProcessServices.attemptId(claimed.id(), 1));
        assertThat(history(loan, "ASYNC_RESULT_ACCEPTED") + history(loan, "ASYNC_ATTEMPT_FAILED")).isZero();
        assertThat(pod[0].services.instance(loan).variables()).doesNotContainKey("bureau");
    }
}
