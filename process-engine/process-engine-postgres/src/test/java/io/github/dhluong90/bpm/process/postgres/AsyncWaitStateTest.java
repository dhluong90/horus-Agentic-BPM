package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import io.github.dhluong90.bpm.process.api.AsyncStepNotFoundException;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.Incident;
import io.github.dhluong90.bpm.process.api.InputInvalidException;
import io.github.dhluong90.bpm.process.api.InstanceNotFoundException;
import io.github.dhluong90.bpm.process.api.PermissionDeniedException;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.api.StepCompletion;
import io.github.dhluong90.bpm.process.internal.service.AsyncSettings;
import io.github.dhluong90.bpm.process.internal.service.AsyncStepExecutor;
import io.github.dhluong90.bpm.process.internal.service.ProcessServices;
import io.github.dhluong90.bpm.process.internal.service.AsyncStepExecutor.Claim;
import io.github.dhluong90.bpm.process.spi.AdapterCall;
import io.github.dhluong90.bpm.process.spi.AdapterResult;
import io.github.dhluong90.bpm.process.spi.HandlerResult;
import io.github.dhluong90.bpm.process.spi.ServiceCall;
import io.github.dhluong90.bpm.process.spi.ServiceTaskHandler;
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
    private static final String SANCTIONS = "sanctions.screen";
    /** The role of the bureau's callback client: may complete bureau steps only. */
    private static final String BUREAU_CALLBACK = "BUREAU_CALLBACK";
    /** The role of the sanctions provider's callback client: may complete sanctions steps only. */
    private static final String SANCTIONS_CALLBACK = "SANCTIONS_CALLBACK";
    /** adapters.completer-roles of every replica. */
    private static final AsyncSettings SCOPED = new AsyncSettings(AsyncSettings.DEFAULT_LEASE_MARGIN,
            AsyncSettings.DEFAULT_TIMEOUT, AsyncSettings.DEFAULT_EXECUTOR_THREADS,
            Map.of(BUREAU, java.util.Set.of("ROLE_" + BUREAU_CALLBACK), SANCTIONS, java.util.Set.of(SANCTIONS_CALLBACK)));

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

    /** A sanctions screening stub on the same replica, never called by the loan process. */
    private static ServiceAdapter sanctions() {
        return new ServiceAdapter() {
            @Override
            public String name() {
                return SANCTIONS;
            }

            @Override
            public AdapterResult execute(AdapterCall call) {
                return AdapterResult.completed(Map.of("cleared", true));
            }
        };
    }

    private static AdapterResult score(int value) {
        return AdapterResult.completed(Map.of("score", value, "band", value >= 700 ? "A" : "C"));
    }

    private TestEngine replica(String id, ServiceAdapter adapter) {
        return track(TestEngine.replica(id, clock).async(SCOPED).adapters(adapter, sanctions()).build());
    }

    private TestEngine track(TestEngine engine) {
        engines.add(engine);
        engine.identity.permissions = java.util.Set.of("processes:deploy", "processes:start", "processes:read",
                "processes:task:work", "processes:cancel", "processes:step:complete");
        engine.identity.roles = java.util.Set.of(BUREAU_CALLBACK);
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
        List<Claim> claimed = pod1.async.claim(16);

        // Then TX1 sets locked_until = T0 + 32 s, pod 2 never claims the live attempt, and TX2 records the result once
        assertThat(claimed).hasSize(1);
        assertThat(lockedUntil(loan)).isEqualTo(T0.plusSeconds(32));
        assertThat(claimed.get(0).job().leaseDuration()).isEqualTo(Duration.ofSeconds(32));
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
        Claim first = pod1.async.claim(16).get(0);

        // When pod 2 polls just before and at T0 + 32 s
        clock.set(T0.plusSeconds(32).minusMillis(1));
        List<Claim> early = pod2.async.claim(16);
        clock.set(T0.plusSeconds(32));
        List<Claim> late = pod2.async.claim(16);

        // Then pod 2 skips the job until the lease ends, then reclaims it as attempt 2 with a new attempt id
        assertThat(early).isEmpty();
        assertThat(late).hasSize(1);
        assertThat(late.get(0).job().attempt()).isEqualTo(2);
        assertThat(job(loan)).containsEntry("locked_by", "pod-2").containsEntry("attempt", 2);
        assertThat(pod2.async.runNow(late.get(0))).contains(StepCompletion.Outcome.ACCEPTED);
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).attempt()).isEqualTo(2);
        assertThat(calls.get(0).attemptId()).isEqualTo(ProcessServices.attemptId(first.job().id(), 2))
                .isNotEqualTo(ProcessServices.attemptId(first.job().id(), 1));
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
        Claim stalled = pod1.async.claim(16).get(0);
        clock.set(T0.plusSeconds(32));
        Claim takeover = pod2.async.claim(16).get(0);
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
        assertThat(pod1.async.claim(16)).singleElement().extracting(c -> c.job().attempt()).isEqualTo(2);
    }

    // ---- completion API ---------------------------------------------------------------------------------------

    @Test
    void a_handed_off_call_is_completed_once_through_the_completion_api() {
        // Given a bureau that takes the request and calls back later
        TestEngine pod1 = replica("pod-1", bureau(call -> new AdapterResult.Accepted()));
        deploy(pod1);
        String loan = apply(pod1, "LOAN-2027-0046").id();
        Claim claimed = pod1.async.claim(16).get(0);
        assertThat(pod1.async.runNow(claimed)).isEmpty();
        assertThat(job(loan)).containsEntry("status", "CLAIMED");
        String attemptId = calls.get(0).attemptId();
        String token = calls.get(0).completionToken();
        assertThat(calls.get(0).jobId()).isEqualTo(claimed.job().id());

        // When the callback arrives on another replica, and the partner repeats it
        TestEngine pod2 = replica("pod-2", bureau(call -> score(1)));
        clock.advance(Duration.ofSeconds(5));
        StepCompletion first = pod2.services.complete(claimed.job().id(), attemptId, token, score(705));
        int historyAfterFirst = history(loan);
        StepCompletion repeat = pod2.services.complete(claimed.job().id(), attemptId, token, score(705));

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
        Claim claimed = pod1.async.claim(16).get(0);
        pod1.async.runNow(claimed);
        String attemptId = calls.get(0).attemptId();
        String token = calls.get(0).completionToken();
        int historyBefore = history(loan);

        pod1.identity.as("bank-b", "partner-b", "processes:step:complete").roles = java.util.Set.of(BUREAU_CALLBACK);
        assertThatThrownBy(() -> pod1.services.complete(claimed.job().id(), attemptId, token, score(800)))
                .isInstanceOf(AsyncStepNotFoundException.class)
                .extracting(e -> ((AsyncStepNotFoundException) e).code()).isEqualTo("ASYNC_STEP_NOT_FOUND");

        pod1.identity.as("bank-a", "teller-lan", "processes:read");
        assertThatThrownBy(() -> pod1.services.complete(claimed.job().id(), attemptId, token, score(800)))
                .isInstanceOf(PermissionDeniedException.class);

        pod1.identity.as("bank-a", "partner-a", "processes:step:complete").roles = java.util.Set.of(BUREAU_CALLBACK);
        assertThatThrownBy(() -> pod1.services.complete(claimed.job().id(), attemptId, token, new AdapterResult.Accepted()))
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
        Claim retry = pod1.async.claim(16).get(0);
        assertThat(pod1.async.runNow(retry)).contains(StepCompletion.Outcome.ACCEPTED);

        assertThat(calls).extracting(AdapterCall::attempt).containsExactly(1, 2);
        assertThat(calls.get(0).attemptId()).isNotEqualTo(calls.get(1).attemptId());
        assertThat(job(loan)).containsEntry("status", "FAILED").containsEntry("attempt", 2);
        assertThat(history(loan, "ASYNC_ATTEMPT_FAILED")).isEqualTo(2);
        assertThat(TestEngine.jdbc().sql("SELECT details->>'errorCode' || ' retryable=' || (details->>'retryable') || "
                + "' final=' || (details->>'final') FROM pre_history WHERE instance_id = ? AND event_type = "
                + "'ASYNC_ATTEMPT_FAILED' ORDER BY (details->>'attempt')::int").param(loan).query(String.class).list())
                .containsExactly("BUREAU_UNAVAILABLE retryable=true final=false",
                        "APPLICANT_UNKNOWN retryable=false final=true");
        assertThat(TestEngine.jdbc().sql("SELECT error_code || ' retry=' || retry_count || ' ' || status FROM pre_incident "
                + "WHERE instance_id = ?").param(loan).query(String.class).list()).containsExactly("UNHANDLED_BUSINESS_ERROR retry=1 OPEN");
        assertThat(history(loan, "INCIDENT_RAISED")).isEqualTo(1);
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
        assertThat(pod1.async.freeSlots(io.github.dhluong90.bpm.process.internal.store.JobStore.ASYNC_SERVICE)).isEqualTo(1);
        assertThat(pod1.async.awaitIdle(Duration.ofSeconds(30))).isTrue();

        assertThat(interrupted).isTrue();
        assertThat(job(loan)).containsEntry("status", "DUE").containsEntry("attempt", 1);
        assertThat(TestEngine.jdbc().sql("SELECT details->>'errorCode' FROM pre_history WHERE instance_id = ? AND "
                + "event_type = 'ASYNC_ATTEMPT_FAILED'").param(loan).query(String.class).single())
                .isEqualTo(AsyncStepExecutor.TIMEOUT);
        assertThat(pod1.async.freeSlots(io.github.dhluong90.bpm.process.internal.store.JobStore.ASYNC_SERVICE)).isEqualTo(2);
    }

    // ---- PRE-040, PRE-111, PRE-113: business errors (#55) -------------------------------------------------------

    /**
     * A loan disbursement: the request is recorded, then the daily limit is checked by a local handler, then an
     * officer disburses. Both handlers run inside the step transaction.
     */
    private static final String DISBURSEMENT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:horus="urn:horus-bpm:bpmn:extensions:v1" id="Definitions_loan-disbursement"
                              targetNamespace="urn:horus-bpm:examples" expressionLanguage="urn:horus-bpm:expr:v1">
              <bpmn:process id="loan-disbursement" name="Loan disbursement" isExecutable="true">
                <bpmn:startEvent id="start"/>
                <bpmn:serviceTask id="record-request" horus:handler="disbursements.record" horus:resultVariable="request">
                  <bpmn:extensionElements>
                    <horus:input name="amount" value="${instance.amount}"/>
                  </bpmn:extensionElements>
                </bpmn:serviceTask>
                <bpmn:serviceTask id="check-limit" horus:handler="limits.check" horus:resultVariable="limit">
                  <bpmn:extensionElements>
                    <horus:input name="accountId" value="${instance.accountId}"/>
                    <horus:input name="amount" value="${instance.amount}"/>
                  </bpmn:extensionElements>
                </bpmn:serviceTask>
                <bpmn:userTask id="disburse" horus:assignee="role:LOAN_OFFICER" horus:resultVariable="disbursal"/>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="record-request"/>
                <bpmn:sequenceFlow id="f2" sourceRef="record-request" targetRef="check-limit"/>
                <bpmn:sequenceFlow id="f3" sourceRef="check-limit" targetRef="disburse"/>
                <bpmn:sequenceFlow id="f4" sourceRef="disburse" targetRef="end"/>
              </bpmn:process>
            </bpmn:definitions>
            """;

    private static ServiceTaskHandler handler(String name, java.util.function.Function<ServiceCall, HandlerResult> body) {
        return new ServiceTaskHandler() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public HandlerResult handle(ServiceCall call) {
                return body.apply(call);
            }
        };
    }

    private final AtomicInteger limitChecks = new AtomicInteger();

    /** A replica with the disbursement handlers: the limit check reports the daily limit reached. */
    private TestEngine disbursements() {
        TestEngine engine = track(TestEngine.replica("pod-1", clock).async(SCOPED).adapters(bureau(call ->
                new AdapterResult.Accepted()), sanctions()).handlers(
                handler("disbursements.record", call -> HandlerResult.completed(Map.of("recordedAmount",
                        call.inputs().get("amount")))),
                handler("limits.check", call -> {
                    limitChecks.incrementAndGet();
                    return new HandlerResult.BusinessError("LIMIT_EXCEEDED", "Daily limit reached for account "
                            + call.inputs().get("accountId"));
                })).build());
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                DISBURSEMENT.strip().getBytes(StandardCharsets.UTF_8), null));
        return engine;
    }

    private static List<String> textsOf(String instanceId) {
        return TestEngine.jdbc().sql("SELECT details::text FROM pre_history WHERE instance_id = ? UNION ALL "
                + "SELECT cause_summary FROM pre_incident WHERE instance_id = ?").params(instanceId, instanceId)
                .query(String.class).list();
    }

    private static Map<String, Object> incidentDetails(String instanceId) {
        return io.github.dhluong90.bpm.process.internal.json.Values.readMap(TestEngine.jdbc().sql("SELECT details::text "
                + "FROM pre_history WHERE instance_id = ? AND event_type = 'INCIDENT_RAISED'").param(instanceId)
                .query(String.class).single());
    }

    @Test
    void PRE_040_business_error_result_opens_incident() {
        // Given loan-disbursement, whose local step check-limit answers BusinessError(LIMIT_EXCEEDED)
        TestEngine pod = disbursements();

        // When an instance reaches check-limit at its start
        ProcessInstance loan = pod.services.start(new StartRequest("loan-disbursement", "DISB-2027-0040",
                Map.of("accountId", "ACC-0001", "amount", 50_000_000), null));

        // Then the start succeeds, the step transaction committed one incident with the business code in its cause, and
        // the token waits on check-limit with the earlier step's result kept
        assertThat(loan.status()).isEqualTo("RUNNING");
        assertThat(tokenOn(pod, loan.id())).isEqualTo("check-limit:WAITING_ASYNC");
        assertThat(pod.services.instance(loan.id()).variables()).containsKey("request").doesNotContainKey("limit");
        List<Incident> incidents = pod.services.openIncidents(loan.id());
        assertThat(incidents).singleElement().satisfies(i -> {
            assertThat(i.errorCode()).isEqualTo("UNHANDLED_BUSINESS_ERROR");
            assertThat(i.causeSummary()).contains("LIMIT_EXCEEDED");
            assertThat(i.nodeId()).isEqualTo("check-limit");
            assertThat(i.retryCount()).isZero();
            assertThat(i.status()).isEqualTo("OPEN");
        });
        assertThat(incidentDetails(loan.id())).containsEntry("errorCode", "UNHANDLED_BUSINESS_ERROR")
                .containsEntry("businessCode", "LIMIT_EXCEEDED").doesNotContainKey("jobId");
        assertThat(pod.events).filteredOn(e -> e.type().equals("processes.incident.raised.v1")).singleElement()
                .satisfies(e -> assertThat(e.payload()).containsEntry("businessCode", "LIMIT_EXCEEDED")
                        .containsEntry("errorCode", "UNHANDLED_BUSINESS_ERROR").containsEntry("nodeId", "check-limit"));
        // And nothing is retried: no job exists, and polling runs the handler no more
        assertThat(TestEngine.jdbc().sql("SELECT count(*) FROM pre_timer WHERE instance_id = ?").param(loan.id())
                .query(Integer.class).single()).isZero();
        clock.advance(Duration.ofMinutes(10));
        pod.jobs.pollOnce();
        pod.async.pollOnce();
        assertThat(limitChecks).hasValue(1);
        // And the handler's message never reached history or the incident
        assertThat(textsOf(loan.id())).noneMatch(t -> t.contains("Daily limit") || t.contains("ACC-0001"));
    }

    @Test
    void PRE_111_a_business_error_fails_the_job_at_once_and_opens_one_incident() {
        // Given the bureau answers that it has no file for the applicant, on attempt 1 of 3
        TestEngine pod = replica("pod-1", bureau(call -> new AdapterResult.BusinessError("APPLICANT_UNKNOWN",
                "no file at the bureau for " + call.inputs().get("nationalId"))));
        deploy(pod);
        String loan = apply(pod, "LOAN-2027-1111").id();

        // When the executor records the answer
        assertThat(pod.async.runNow(pod.async.claim(16).get(0))).contains(StepCompletion.Outcome.ACCEPTED);

        // Then the job failed on its first attempt, one incident is open with the business code, and nothing is due
        assertThat(job(loan)).containsEntry("status", "FAILED").containsEntry("attempt", 1);
        assertThat(TestEngine.jdbc().sql("SELECT details->>'errorCode' || ' retryable=' || (details->>'retryable') || "
                + "' final=' || (details->>'final') FROM pre_history WHERE instance_id = ? AND event_type = "
                + "'ASYNC_ATTEMPT_FAILED'").param(loan).query(String.class).single())
                .isEqualTo("APPLICANT_UNKNOWN retryable=false final=true");
        assertThat(pod.services.openIncidents(loan)).singleElement().satisfies(i -> {
            assertThat(i.errorCode()).isEqualTo("UNHANDLED_BUSINESS_ERROR");
            assertThat(i.retryCount()).isZero();
            assertThat(i.causeSummary()).contains("APPLICANT_UNKNOWN");
        });
        assertThat(incidentDetails(loan)).containsEntry("businessCode", "APPLICANT_UNKNOWN");
        assertThat(pod.events).filteredOn(e -> e.type().equals("processes.incident.raised.v1")).singleElement()
                .satisfies(e -> assertThat(e.payload()).containsEntry("businessCode", "APPLICANT_UNKNOWN"));
        assertThat(tokenOn(pod, loan)).isEqualTo("bureau-score:WAITING_ASYNC");
        clock.advance(Duration.ofMinutes(10));
        assertThat(pod.async.claim(16)).isEmpty();
        assertThat(calls).hasSize(1);
        // And neither the adapter's message nor the national id was stored
        assertThat(textsOf(loan)).noneMatch(t -> t.contains("no file") || t.contains("079088001234"));
        assertThat(pod.events.toString()).doesNotContain("079088001234");
    }

    @Test
    void PRE_111_a_business_error_reported_through_the_completion_api_is_final_and_its_repeat_writes_nothing() {
        // Given the bureau hands the call off
        TestEngine pod = replica("pod-1", bureau(call -> new AdapterResult.Accepted()));
        deploy(pod);
        String loan = apply(pod, "LOAN-2027-1112").id();
        Claim claimed = pod.async.claim(16).get(0);
        pod.async.runNow(claimed);
        String attemptId = calls.get(0).attemptId();
        String token = calls.get(0).completionToken();
        pod.identity.as("bank-a", "bureau-callback", "processes:step:complete", "processes:read").roles =
                java.util.Set.of(BUREAU_CALLBACK);
        AdapterResult unknown = new AdapterResult.BusinessError("APPLICANT_UNKNOWN", "no file at the bureau");

        // When its callback reports the business error, then repeats it
        StepCompletion first = pod.services.complete(claimed.job().id(), attemptId, token, unknown);
        int afterFirst = history(loan);
        StepCompletion repeat = pod.services.complete(claimed.job().id(), attemptId, token, unknown);

        // Then the first is final: the job failed and one incident is open; the repeat is discarded and writes nothing
        assertThat(first.outcome()).isEqualTo(StepCompletion.Outcome.ACCEPTED);
        assertThat(repeat.outcome()).isEqualTo(StepCompletion.Outcome.DISCARDED);
        assertThat(job(loan)).containsEntry("status", "FAILED").containsEntry("attempt", 1);
        assertThat(pod.services.openIncidents(loan)).singleElement()
                .satisfies(i -> assertThat(i.errorCode()).isEqualTo("UNHANDLED_BUSINESS_ERROR"));
        assertThat(history(loan)).isEqualTo(afterFirst);
        assertThat(TestEngine.jdbc().sql("SELECT actor_id FROM pre_history WHERE instance_id = ? AND event_type = "
                + "'ASYNC_ATTEMPT_FAILED'").param(loan).query(String.class).single()).isEqualTo("bureau-callback");
    }

    @Test
    void PRE_113_another_tenant_cannot_report_a_late_result_or_read_a_business_error_incident() {
        // Given bank-a's disbursement with a business error incident, and a loan whose bureau call was handed off and
        // then cancelled
        TestEngine pod = disbursements();
        pod.identity.permissions = java.util.Set.of("processes:deploy", "processes:start", "processes:read",
                "processes:cancel", "processes:step:complete");
        deploy(pod);
        String disbursement = pod.services.start(new StartRequest("loan-disbursement", "DISB-2027-0113",
                Map.of("accountId", "ACC-0113", "amount", 10_000_000), null)).id();
        String loan = apply(pod, "LOAN-2027-1113").id();
        Claim claimed = pod.async.claim(16).get(0);
        pod.async.runNow(claimed);
        String attemptId = calls.get(0).attemptId();
        String token = calls.get(0).completionToken();
        pod.services.cancel(loan, pod.services.instance(loan).lockVersion(), "Applicant withdrew", "cancel-1113");
        int loanHistory = history(loan);
        int disbursementHistory = history(disbursement);

        // When bank-b's client reports a result for the cancelled step and reads the disbursement's incidents
        pod.identity.as("bank-b", "partner-b", "processes:step:complete", "processes:read").roles =
                java.util.Set.of(BUREAU_CALLBACK);

        // Then both are not found, and nothing is written
        assertThatThrownBy(() -> pod.services.complete(claimed.job().id(), attemptId, token, score(800)))
                .isInstanceOf(AsyncStepNotFoundException.class)
                .extracting(e -> ((AsyncStepNotFoundException) e).code()).isEqualTo("ASYNC_STEP_NOT_FOUND");
        assertThatThrownBy(() -> pod.services.openIncidents(disbursement))
                .isInstanceOf(InstanceNotFoundException.class);
        assertThat(history(loan)).isEqualTo(loanHistory);
        assertThat(history(loan, "LATE_RESULT_DISCARDED")).isZero();
        assertThat(history(disbursement)).isEqualTo(disbursementHistory);
        assertThat(job(loan)).containsEntry("status", "CANCELLED");
        pod.identity.as("bank-a", "pipeline", "processes:read");
        assertThat(pod.services.openIncidents(disbursement)).singleElement()
                .satisfies(i -> assertThat(i.status()).isEqualTo("OPEN"));
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
        Claim claimed = pod[0].async.claim(16).get(0);

        Optional<StepCompletion.Outcome> outcome = pod[0].async.runNow(claimed);

        // Then TX2 writes only LATE_RESULT_DISCARDED with the attempt id; the instance stays cancelled
        assertThat(outcome).contains(StepCompletion.Outcome.DISCARDED);
        assertThat(pod[0].services.instance(loan).status()).isEqualTo("CANCELLED");
        assertThat(job(loan)).containsEntry("status", "CANCELLED");
        assertThat(history(loan, "TOKEN_CANCELLED")).isEqualTo(1);
        assertThat(TestEngine.jdbc().sql("SELECT attempt_id FROM pre_history WHERE instance_id = ? AND event_type = "
                + "'LATE_RESULT_DISCARDED'").param(loan).query(String.class).single())
                .isEqualTo(ProcessServices.attemptId(claimed.job().id(), 1));
        assertThat(history(loan, "ASYNC_RESULT_ACCEPTED") + history(loan, "ASYNC_ATTEMPT_FAILED")).isZero();
        assertThat(pod[0].services.instance(loan).variables()).doesNotContainKey("bureau");
    }

    // ---- completion token and adapter scope ---------------------------------------------------------------------

    /** Starts a loan whose bureau call is handed off, and returns the call the bureau received. */
    private AdapterCall handedOff(TestEngine engine, String applicationId) {
        apply(engine, applicationId);
        int before = calls.size();
        Claim claimed = engine.async.claim(16).get(0);
        assertThat(engine.async.runNow(claimed)).isEmpty();
        assertThat(calls).hasSize(before + 1);
        return calls.get(before);
    }

    @Test
    void TR_ASY_02_a_derived_attempt_id_without_the_completion_token_is_refused_and_writes_nothing() {
        // Given a bureau call handed off; a client reads the instance and derives the job's current attempt id
        TestEngine pod1 = replica("pod-1", bureau(call -> new AdapterResult.Accepted()));
        deploy(pod1);
        AdapterCall call = handedOff(pod1, "LOAN-2027-0051");
        String derived = ProcessServices.attemptId(call.jobId(), 1);
        int historyBefore = history(call.instanceId());

        // When it posts a forged result with the derived id and a guessed token
        org.assertj.core.api.ThrowableAssert.ThrowingCallable forged = () -> pod1.services.complete(call.jobId(),
                derived, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", score(850));

        // Then the derived id is the real one, yet the completion is refused and nothing changes
        assertThat(derived).isEqualTo(call.attemptId());
        assertThatThrownBy(forged).isInstanceOf(PermissionDeniedException.class);
        assertThatThrownBy(() -> pod1.services.complete(call.jobId(), derived, " ", score(850)))
                .isInstanceOf(InputInvalidException.class);
        assertThat(history(call.instanceId())).isEqualTo(historyBefore);
        assertThat(job(call.instanceId())).containsEntry("status", "CLAIMED").containsEntry("attempt", 1);
        assertThat(tokenOn(pod1, call.instanceId())).isEqualTo("bureau-score:WAITING_ASYNC");
        assertThat(call.completionToken()).hasSize(43).isNotEqualTo(call.attemptId());
        assertThat(call.toString()).doesNotContain(call.completionToken()).contains("completionToken=***");
        byte[] stored = TestEngine.jdbc().sql("SELECT completion_token_hash FROM pre_timer WHERE id = ?")
                .param(call.jobId()).query((rs, row) -> rs.getBytes(1)).single();
        assertThat(stored).hasSize(32);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain(call.completionToken());
    }

    @Test
    void TR_ASY_02_a_token_from_another_job_is_refused() {
        // Given two loans whose bureau calls are both handed off
        TestEngine pod1 = replica("pod-1", bureau(call -> new AdapterResult.Accepted()));
        deploy(pod1);
        AdapterCall first = handedOff(pod1, "LOAN-2027-0052");
        AdapterCall second = handedOff(pod1, "LOAN-2027-0053");

        // When the first loan's step is completed with the second loan's token, then with its own
        org.assertj.core.api.ThrowableAssert.ThrowingCallable swapped = () -> pod1.services.complete(first.jobId(),
                first.attemptId(), second.completionToken(), score(790));

        // Then the swapped token is refused, and the right one is accepted
        assertThatThrownBy(swapped).isInstanceOf(PermissionDeniedException.class);
        assertThat(job(first.instanceId())).containsEntry("status", "CLAIMED");
        assertThat(pod1.services.complete(first.jobId(), first.attemptId(), first.completionToken(), score(790))
                .outcome()).isEqualTo(StepCompletion.Outcome.ACCEPTED);
        assertThat(job(second.instanceId())).containsEntry("status", "CLAIMED");
    }

    @Test
    void TR_ASY_02_a_token_of_an_earlier_attempt_cannot_complete_the_current_one() {
        // Given attempt 1 handed off, its lease ended, and attempt 2 handed off again
        TestEngine pod1 = replica("pod-1", bureau(call -> new AdapterResult.Accepted()));
        deploy(pod1);
        AdapterCall attempt1 = handedOff(pod1, "LOAN-2027-0054");
        clock.advance(Duration.ofSeconds(60));
        Claim reclaimed = pod1.async.claim(16).get(0);
        pod1.async.runNow(reclaimed);
        AdapterCall attempt2 = calls.get(calls.size() - 1);

        // When attempt 2 is reported with attempt 1's token, and attempt 1 is reported late with its own
        org.assertj.core.api.ThrowableAssert.ThrowingCallable oldToken = () -> pod1.services.complete(
                attempt2.jobId(), attempt2.attemptId(), attempt1.completionToken(), score(700));
        StepCompletion late = pod1.services.complete(attempt1.jobId(), attempt1.attemptId(),
                attempt1.completionToken(), score(700));

        // Then the first is refused and the late one is discarded; the attempt 2 token still works
        assertThat(attempt2.completionToken()).isNotEqualTo(attempt1.completionToken());
        assertThat(attempt2.idempotencyKey()).isEqualTo(attempt1.idempotencyKey()).isEqualTo(attempt1.jobId());
        assertThatThrownBy(oldToken).isInstanceOf(PermissionDeniedException.class);
        assertThat(late.outcome()).isEqualTo(StepCompletion.Outcome.DISCARDED);
        assertThat(pod1.services.complete(attempt2.jobId(), attempt2.attemptId(), attempt2.completionToken(),
                score(700)).outcome()).isEqualTo(StepCompletion.Outcome.ACCEPTED);
    }

    @Test
    void TR_SEC_04_a_client_scoped_to_another_adapter_cannot_complete_a_bureau_step() {
        // Given a bureau call handed off, and the sanctions provider's callback client holding the bureau's token
        TestEngine pod1 = replica("pod-1", bureau(call -> new AdapterResult.Accepted()));
        deploy(pod1);
        AdapterCall call = handedOff(pod1, "LOAN-2027-0055");
        int historyBefore = history(call.instanceId());
        pod1.identity.as("bank-a", "sanctions-callback", "processes:step:complete").roles =
                java.util.Set.of(SANCTIONS_CALLBACK);

        // When it posts a cleared result for the bureau step, and a caller with no completer role tries the same
        org.assertj.core.api.ThrowableAssert.ThrowingCallable outOfScope = () -> pod1.services.complete(call.jobId(),
                call.attemptId(), call.completionToken(), score(810));

        // Then both are refused and nothing is written; the bureau's own client completes it
        assertThatThrownBy(outOfScope).isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("completer-roles");
        pod1.identity.roles = java.util.Set.of();
        assertThatThrownBy(outOfScope).isInstanceOf(PermissionDeniedException.class);
        assertThat(history(call.instanceId())).isEqualTo(historyBefore);
        assertThat(job(call.instanceId())).containsEntry("status", "CLAIMED");
        pod1.identity.as("bank-a", "bureau-callback", "processes:step:complete").roles =
                java.util.Set.of(BUREAU_CALLBACK);
        assertThat(pod1.services.complete(call.jobId(), call.attemptId(), call.completionToken(), score(810))
                .outcome()).isEqualTo(StepCompletion.Outcome.ACCEPTED);
    }

    @Test
    void completer_roles_naming_an_unregistered_adapter_stop_the_engine() {
        AsyncSettings typo = new AsyncSettings(AsyncSettings.DEFAULT_LEASE_MARGIN, AsyncSettings.DEFAULT_TIMEOUT, 2,
                Map.of("creditBureau.scor", java.util.Set.of(BUREAU_CALLBACK)));

        assertThatThrownBy(() -> TestEngine.replica("pod-1", clock).async(typo).adapters(bureau(c -> score(1))).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("creditBureau.scor");
    }

    // ---- TR-IDEM-04 -------------------------------------------------------------------------------------------

    @Test
    void TR_IDEM_04_a_retry_after_a_timeout_sends_the_same_idempotency_key() throws Exception {
        // Given a bureau whose first call hangs past the 0.2 s step timeout and whose second answers
        CountDownLatch never = new CountDownLatch(1);
        AtomicInteger n = new AtomicInteger();
        TestEngine pod1 = track(TestEngine.replica("pod-1", clock)
                .async(new AsyncSettings(Duration.ofSeconds(30), Duration.ofSeconds(30), 2))
                .adapters(bureau(call -> {
                    if (n.incrementAndGet() == 1) {
                        try {
                            never.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return score(702);
                })).build());
        byte[] fast = new String(TestEngine.resource("/bpmn/loan-bureau-check.bpmn"), StandardCharsets.UTF_8)
                .replace("horus:timeout=\"PT2S\"", "horus:timeout=\"PT0.2S\"").getBytes(StandardCharsets.UTF_8);
        deploy(pod1, fast);
        String loan = apply(pod1, "LOAN-2027-0056").id();

        // When attempt 1 times out, and attempt 2 runs after the retry backoff
        assertThat(pod1.async.pollOnce()).isEqualTo(1);
        assertThat(pod1.async.awaitIdle(Duration.ofSeconds(30))).isTrue();
        clock.advance(Duration.ofSeconds(10));
        assertThat(pod1.async.pollOnce()).isEqualTo(1);
        assertThat(pod1.async.awaitIdle(Duration.ofSeconds(30))).isTrue();

        // Then both calls carry the job id as idempotency key, while attempt ids and tokens differ
        assertThat(calls).hasSize(2);
        assertThat(calls).extracting(AdapterCall::idempotencyKey).containsOnly(calls.get(0).jobId());
        assertThat(calls.get(0).attemptId()).isNotEqualTo(calls.get(1).attemptId());
        assertThat(calls.get(0).completionToken()).isNotEqualTo(calls.get(1).completionToken());
        assertThat(job(loan)).containsEntry("status", "DONE").containsEntry("attempt", 2);
        assertThat(pod1.services.instance(loan).variables()).containsEntry("bureau", Map.of("score", 702L, "band", "A"));
    }

    // ---- bounded history ----------------------------------------------------------------------------------------

    @Test
    void PRE_041_a_hundred_late_callbacks_on_a_cancelled_step_write_one_history_row() {
        // Given a bureau call handed off, then the loan cancelled
        TestEngine pod1 = replica("pod-1", bureau(call -> new AdapterResult.Accepted()));
        deploy(pod1);
        AdapterCall call = handedOff(pod1, "LOAN-2027-0057");
        ProcessInstance current = pod1.services.instance(call.instanceId());
        pod1.services.cancel(call.instanceId(), current.lockVersion(), "Applicant withdrew", "cancel-0057");

        // When the bureau's callback is repeated 100 times
        List<StepCompletion.Outcome> outcomes = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            outcomes.add(pod1.services.complete(call.jobId(), call.attemptId(), call.completionToken(), score(690))
                    .outcome());
        }

        // Then every one is discarded and exactly one LATE_RESULT_DISCARDED row exists
        assertThat(outcomes).containsOnly(StepCompletion.Outcome.DISCARDED);
        assertThat(history(call.instanceId(), "LATE_RESULT_DISCARDED")).isEqualTo(1);
        assertThat(pod1.services.instance(call.instanceId()).status()).isEqualTo("CANCELLED");
    }

    @Test
    void a_job_claimed_beyond_its_attempts_fails_with_a_history_entry() {
        // Given a bureau step whose attempts keep dying with their replica: claimed 3 times, never run
        TestEngine pod1 = replica("pod-1", bureau(call -> score(1)));
        deploy(pod1);
        String loan = apply(pod1, "LOAN-2027-0058").id();
        for (int i = 0; i < 3; i++) {
            assertThat(pod1.async.claim(16)).hasSize(1);
            clock.advance(Duration.ofSeconds(60));
        }

        // When the fourth claim is run
        Claim fourth = pod1.async.claim(16).get(0);
        Optional<StepCompletion.Outcome> outcome = pod1.async.runNow(fourth);

        // Then the adapter is not called, the job fails and history says why
        assertThat(outcome).isEmpty();
        assertThat(calls).isEmpty();
        assertThat(job(loan)).containsEntry("status", "FAILED").containsEntry("attempt", 4);
        assertThat(TestEngine.jdbc().sql("SELECT details->>'errorCode' || ' final=' || (details->>'final') FROM "
                + "pre_history WHERE instance_id = ? AND event_type = 'ASYNC_ATTEMPT_FAILED'").param(loan)
                .query(String.class).single()).isEqualTo("MAX_ATTEMPTS_REACHED final=true");
        assertThat(TestEngine.jdbc().sql("SELECT error_code || ' retry=' || retry_count || ' ' || status FROM pre_incident "
                + "WHERE instance_id = ?").param(loan).query(String.class).list()).containsExactly("MAX_ATTEMPTS_REACHED retry=3 OPEN");
        assertThat(history(loan, "INCIDENT_RAISED")).isEqualTo(1);
    }
}
