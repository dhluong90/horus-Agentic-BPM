package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import io.github.dhluong90.bpm.process.api.AsyncStepNotFoundException;
import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.DefinitionVersion;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.InstanceNotFoundException;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.api.StepCompletion;
import io.github.dhluong90.bpm.process.api.ValidationFailedException;
import io.github.dhluong90.bpm.process.internal.service.AsyncStepExecutor.Claim;
import io.github.dhluong90.bpm.process.internal.service.LocalHandlerException;
import io.github.dhluong90.bpm.process.spi.AdapterCall;
import io.github.dhluong90.bpm.process.spi.AdapterResult;
import io.github.dhluong90.bpm.process.spi.HandlerResult;
import io.github.dhluong90.bpm.process.spi.ServiceAdapter;
import io.github.dhluong90.bpm.process.spi.ServiceCall;
import io.github.dhluong90.bpm.process.spi.ServiceTaskHandler;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Local and remote service steps in one process (#63, PRE-070 to PRE-072, TR-ASY-01): a loan disbursement reserves a
 * 50,000,000 VND limit in the host's own table inside the step transaction, then posts to core banking through a
 * remote adapter that runs with no transaction open and no connection held. One {@link TestClock}; no test sleeps.
 */
class ServiceStepModesTest {

    private static final Instant T0 = Instant.parse("2026-11-20T04:00:00Z");
    private static final String KEY = "loan-disbursement";
    private static final long AMOUNT_VND = 50_000_000L;

    private final TestClock clock = TestClock.at(T0);
    private final List<TestEngine> engines = new ArrayList<>();
    /** Every call limits.reserve received. */
    private final List<ServiceCall> reservations = new CopyOnWriteArrayList<>();
    /** Every call core banking received. */
    private final List<AdapterCall> postings = new CopyOnWriteArrayList<>();

    @BeforeEach
    void clean() {
        TestEngine.reset();
        TestEngine.jdbc().sql("CREATE TABLE IF NOT EXISTS host_limit_reservation (tenant_id TEXT NOT NULL, "
                + "instance_id TEXT NOT NULL, attempt_id TEXT NOT NULL, amount_minor BIGINT NOT NULL, "
                + "currency CHAR(3) NOT NULL, PRIMARY KEY (tenant_id, instance_id))").update();
        TestEngine.jdbc().sql("TRUNCATE host_limit_reservation").update();
    }

    @AfterEach
    void stop() {
        engines.forEach(TestEngine::close);
    }

    /**
     * The host's limit service: writes the reservation row through the engine's transaction manager, then answers,
     * or throws when {@code fail} is set.
     */
    private ServiceTaskHandler limits(DataSource dataSource, AtomicBoolean fail) {
        return new ServiceTaskHandler() {
            @Override
            public String name() {
                return "limits.reserve";
            }

            @Override
            public HandlerResult handle(ServiceCall call) {
                reservations.add(call);
                JdbcClient.create(dataSource).sql("INSERT INTO host_limit_reservation VALUES (?, ?, ?, ?, ?)")
                        .params(call.tenantId(), call.instanceId(), call.attemptId(),
                                ((Number) call.inputs().get("amountMinor")).longValue(), call.inputs().get("currency"))
                        .update();
                if (fail.get()) {
                    throw new IllegalStateException("limit of customer 079088001234 is frozen");
                }
                return HandlerResult.completed(Map.of("reservationId", "RSV-" + call.instanceId().substring(20)));
            }
        };
    }

    private ServiceAdapter coreBanking(Function<AdapterCall, AdapterResult> answer) {
        return new ServiceAdapter() {
            @Override
            public String name() {
                return "core-banking";
            }

            @Override
            public AdapterResult execute(AdapterCall call) {
                postings.add(call);
                return answer.apply(call);
            }
        };
    }

    private TestEngine engine(DataSource dataSource, AtomicBoolean fail, Function<AdapterCall, AdapterResult> answer,
            boolean autoApprove) {
        TestEngine.Builder builder = TestEngine.replica("pod-1", clock).autoApprove(autoApprove)
                .handlers(limits(dataSource == null ? TestEngine.dataSource() : dataSource, fail))
                .adapters(coreBanking(answer));
        if (dataSource != null) {
            builder.dataSource(dataSource);
        }
        TestEngine engine = builder.build();
        engines.add(engine);
        engine.identity.as("bank-a", "pipeline", "processes:deploy", "processes:start", "processes:read",
                "processes:step:complete");
        return engine;
    }

    private static ProcessInstance disburse(TestEngine engine, String key, String businessKey) {
        return engine.services.start(new StartRequest(key, businessKey, Map.of("amountMinor", AMOUNT_VND,
                "currency", "VND", "tenantId", "bank-b"), null));
    }

    private static List<String> history(String instanceId) {
        return TestEngine.jdbc().sql("SELECT event_type || ':' || coalesce(node_id, '-') FROM pre_history "
                + "WHERE instance_id = ? ORDER BY occurred_at, id").param(instanceId).query(String.class).list();
    }

    private static int count(String sql, Object... params) {
        return TestEngine.jdbc().sql(sql).params(params).query(Integer.class).single();
    }

    private static Map<String, Object> job(String instanceId) {
        return TestEngine.jdbc().sql("SELECT id, status, attempt, job_kind, next_fire_at FROM pre_timer "
                + "WHERE instance_id = ?").param(instanceId).query().singleRow();
    }

    private static String tokenOn(TestEngine engine, String instanceId) {
        return engine.services.instance(instanceId).tokens().stream().filter(t -> t.kind().equals("FLOW")
                && !t.status().equals("COMPLETED")).map(t -> t.nodeId() + ":" + t.status()).findFirst().orElse("-");
    }

    private static byte[] bpmn(String name) {
        return TestEngine.resource("/bpmn/" + name + ".bpmn");
    }

    private static DefinitionVersion deploy(TestEngine engine, byte[] source) {
        return engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML, source, null));
    }

    // ---- PRE-070 ----------------------------------------------------------------------------------------------

    @Test
    void PRE_070_one_process_runs_a_local_step_in_its_transaction_and_a_remote_step_outside_any_transaction()
            throws Exception {
        // Given loan-disbursement v1 in bank-a on a 2-connection pool; core banking answers after 2 s, first with a
        // retryable failure and then with posting FT26324001
        AtomicInteger connectionsDuringCall = new AtomicInteger(-1);
        AtomicInteger insideTransaction = new AtomicInteger();
        try (HikariDataSource pool = TestEngine.pool(2, Duration.ofMillis(500))) {
            AtomicInteger calls = new AtomicInteger();
            TestEngine host = engine(pool, new AtomicBoolean(), call -> {
                if (TransactionSynchronizationManager.isActualTransactionActive()) {
                    insideTransaction.incrementAndGet();
                }
                connectionsDuringCall.set(pool.getHikariPoolMXBean().getActiveConnections());
                clock.advance(Duration.ofSeconds(2));
                return calls.incrementAndGet() == 1 ? new AdapterResult.Failed("CORE_BUSY", "busy", true)
                        : AdapterResult.completed(Map.of("postingRef", "FT26324001"));
            }, true);
            deploy(host, bpmn(KEY));

            // When an instance starts with business key LN-2026-0042 and 50,000,000 VND
            ProcessInstance loan = disburse(host, KEY, "LN-2026-0042");

            // Then the start transaction called limits.reserve with the instance's tenant, id, step, business key and
            // attempt id, and its reservation row committed with the token's move to post-to-core
            assertThat(reservations).singleElement().satisfies(call -> {
                assertThat(call.tenantId()).isEqualTo("bank-a");
                assertThat(call.instanceId()).isEqualTo(loan.id());
                assertThat(call.nodeId()).isEqualTo("reserve-limit");
                assertThat(call.businessKey()).isEqualTo("LN-2026-0042");
                assertThat(call.attemptId()).isNotBlank();
                assertThat(call.inputs()).isEqualTo(Map.of("amountMinor", AMOUNT_VND, "currency", "VND"));
            });
            assertThat(count("SELECT count(*) FROM host_limit_reservation WHERE tenant_id = 'bank-a' AND instance_id = ?"
                    + " AND amount_minor = 50000000 AND currency = 'VND'", loan.id())).isEqualTo(1);
            assertThat(tokenOn(host, loan.id())).isEqualTo("post-to-core:WAITING_ASYNC");
            assertThat(job(loan.id())).containsEntry("job_kind", "ASYNC_SERVICE").containsEntry("status", "DUE");
            String jobId = (String) job(loan.id()).get("id");

            // When the posting is claimed, fails retryably, and is claimed again after the backoff
            List<Claim> first = host.async.claim(16);
            assertThat(first).hasSize(1);
            host.async.runNow(first.get(0));
            clock.advance(Duration.ofSeconds(30));
            List<Claim> second = host.async.claim(16);
            assertThat(second).hasSize(1);
            assertThat(host.async.runNow(second.get(0))).contains(StepCompletion.Outcome.ACCEPTED);

            // Then the adapter ran with no engine transaction on its thread and no connection held, keyed by the job
            // id on both attempts, with a new attempt id per claim, in the instance's tenant (never the body's)
            assertThat(insideTransaction).hasValue(0);
            assertThat(connectionsDuringCall).hasValue(0);
            assertThat(postings).hasSize(2).allSatisfy(call -> {
                assertThat(call.idempotencyKey()).isEqualTo(jobId);
                assertThat(call.tenantId()).isEqualTo("bank-a");
                assertThat(call.inputs()).containsEntry("reservationId", "RSV-" + loan.id().substring(20));
            });
            assertThat(postings.get(0).attemptId()).isNotEqualTo(postings.get(1).attemptId());
            assertThat(postings.get(0).completionToken()).isNotEqualTo(postings.get(1).completionToken());
            // The completion token is kept only as its hash
            assertThat(count("SELECT count(*) FROM pre_timer WHERE payload::text LIKE ? OR completion_token_hash = "
                    + "convert_to(?, 'UTF8')", "%" + postings.get(1).completionToken() + "%",
                    postings.get(1).completionToken())).isZero();
            assertThat(count("SELECT count(*) FROM pre_history WHERE details::text LIKE ?",
                    "%" + postings.get(1).completionToken() + "%")).isZero();

            // And TX2 stored postingRef and ended the instance; history is in order
            ProcessInstance ended = host.services.instance(loan.id());
            assertThat(ended.status()).isEqualTo("COMPLETED");
            assertThat(ended.variables()).containsEntry("posting", Map.of("postingRef", "FT26324001"));
            List<String> steps = history(loan.id());
            assertThat(steps).containsSubsequence("NODE_COMPLETED:reserve-limit", "ASYNC_STEP_SCHEDULED:post-to-core",
                    "ASYNC_RESULT_ACCEPTED:post-to-core", "INSTANCE_ENDED:-");
        }
    }

    @Test
    void PRE_070_another_tenant_can_neither_read_the_instance_nor_complete_its_remote_step_and_nothing_is_written() {
        TestEngine host = engine(null, new AtomicBoolean(), call -> new AdapterResult.Accepted(), true);
        deploy(host, bpmn(KEY));
        ProcessInstance loan = disburse(host, KEY, "LN-2026-0043");
        Claim claim = host.async.claim(16).get(0);
        host.async.runNow(claim);
        AdapterCall handedOff = postings.get(0);
        int before = history(loan.id()).size();

        host.identity.as("bank-b", "partner-b", "processes:read", "processes:step:complete");
        assertThatThrownBy(() -> host.services.instance(loan.id())).isInstanceOf(InstanceNotFoundException.class);
        assertThatThrownBy(() -> host.services.complete(handedOff.jobId(), handedOff.attemptId(),
                handedOff.completionToken(), AdapterResult.completed(Map.of("postingRef", "FT-OTHER"))))
                .isInstanceOf(AsyncStepNotFoundException.class);

        assertThat(history(loan.id())).hasSize(before);
        assertThat(job(loan.id())).containsEntry("status", "CLAIMED");
    }

    // ---- PRE-071 ----------------------------------------------------------------------------------------------

    @Test
    void PRE_071_a_local_handler_that_throws_on_start_rolls_back_with_its_step_and_leaves_nothing() {
        // Given limits.reserve writes its reservation row and then throws
        TestEngine host = engine(null, new AtomicBoolean(true), call -> AdapterResult.completed(Map.of()), true);
        deploy(host, bpmn(KEY));

        // When an instance is started
        assertThatThrownBy(() -> disburse(host, KEY, "LN-2026-0044"))
                .isInstanceOfSatisfying(LocalHandlerException.class, e -> {
                    assertThat(e.getCause()).isInstanceOf(IllegalStateException.class);
                    assertThat(e.getMessage()).doesNotContain("079088001234");
                });

        // Then nothing committed: no instance, no reservation, no history, no job
        assertThat(reservations).hasSize(1);
        assertThat(count("SELECT count(*) FROM pre_instance")).isZero();
        assertThat(count("SELECT count(*) FROM host_limit_reservation")).isZero();
        assertThat(count("SELECT count(*) FROM pre_history")).isZero();
        assertThat(count("SELECT count(*) FROM pre_timer")).isZero();
    }

    @Test
    void PRE_071_a_local_handler_that_throws_after_a_timer_rolls_the_firing_back_and_retries_until_failed() {
        // Given the scheduled disbursement waits one hour for its value date, and limits.reserve throws
        TestEngine host = engine(null, new AtomicBoolean(true), call -> AdapterResult.completed(Map.of()), true);
        deploy(host, bpmn("loan-disbursement-scheduled"));
        ProcessInstance loan = disburse(host, "loan-disbursement-scheduled", "LN-2026-0045");
        assertThat(tokenOn(host, loan.id())).isEqualTo("value-date:WAITING_TIMER");
        int historyBefore = history(loan.id()).size();

        // When the timer fires at T0 + 1 h, and again after each backoff (jobs.retry-backoff 10 s times the attempt)
        clock.set(T0.plus(Duration.ofHours(1)));
        assertThat(host.jobs.pollOnce()).as("a failed firing is not counted as fired").isZero();
        Map<String, Object> afterFirst = job(loan.id());
        clock.advance(Duration.ofSeconds(9));
        host.jobs.pollOnce();
        int early = reservations.size() - 1;
        clock.advance(Duration.ofSeconds(1));
        assertThat(host.jobs.pollOnce()).isZero();
        Map<String, Object> afterSecond = job(loan.id());
        clock.advance(Duration.ofSeconds(20));
        assertThat(host.jobs.pollOnce()).isZero();
        clock.advance(Duration.ofHours(1));
        host.jobs.pollOnce();
        int afterFailed = reservations.size() - 3;

        // Then each firing rolled back: due again after 10 s, then 20 s, FAILED after jobs.max-attempts (3)
        assertThat(afterFirst).containsEntry("status", "DUE").containsEntry("attempt", 1);
        assertThat(((Timestamp) afterFirst.get("next_fire_at")).toInstant())
                .isEqualTo(T0.plus(Duration.ofHours(1)).plusSeconds(10));
        assertThat(early).isZero();
        assertThat(afterSecond).containsEntry("status", "DUE").containsEntry("attempt", 2);
        assertThat(((Timestamp) afterSecond.get("next_fire_at")).toInstant())
                .isEqualTo(T0.plus(Duration.ofHours(1)).plusSeconds(10 + 20));
        assertThat(job(loan.id())).containsEntry("status", "FAILED").containsEntry("attempt", 3);
        assertThat(afterFailed).isZero();
        // And the handler ran once per firing and none of its rows committed. Its attempt id is derived from the
        // instance's committed state, which a rolled-back firing leaves unchanged, so every firing presents the same
        // one: it correlates, and keys nothing outside the transaction
        assertThat(reservations).hasSize(3);
        assertThat(reservations.stream().map(ServiceCall::attemptId).distinct()).hasSize(1);
        assertThat(count("SELECT count(*) FROM host_limit_reservation")).isZero();
        assertThat(history(loan.id())).hasSize(historyBefore);
        assertThat(tokenOn(host, loan.id())).isEqualTo("value-date:WAITING_TIMER");
    }

    // ---- PRE-072 ----------------------------------------------------------------------------------------------

    @Test
    void PRE_072_an_unknown_mode_and_a_timeout_on_a_local_step_are_refused_at_import_by_step_and_line() {
        TestEngine host = engine(null, new AtomicBoolean(), call -> AdapterResult.completed(Map.of()), false);

        assertThatThrownBy(() -> deploy(host, bpmn("loan-disbursement-faults")))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> {
                    assertThat(e.code()).isEqualTo("VALIDATION_FAILED");
                    assertThat(e.problems()).extracting(DefinitionProblem::code, DefinitionProblem::elementId)
                            .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("MODE_INVALID",
                                    "post-to-core"), org.assertj.core.groups.Tuple.tuple("TIMEOUT_INVALID",
                                    "check-cutoff"));
                    assertThat(e.problems()).filteredOn(p -> p.elementId().equals("post-to-core")).singleElement()
                            .satisfies(p -> assertThat(p.line()).isEqualTo(31));
                    assertThat(e.problems()).allSatisfy(p -> assertThat(p.line()).isPositive());
                });

        assertThat(count("SELECT count(*) FROM pre_definition")).isZero();
    }

    @Test
    void PRE_072_unregistered_handler_and_adapter_pass_import_and_are_refused_at_request_approval_and_approval() {
        // Given the corrected draft, which still names handler limits.reserv and adapter partner-hub
        TestEngine host = engine(null, new AtomicBoolean(), call -> AdapterResult.completed(Map.of()), false);
        String corrected = new String(bpmn("loan-disbursement-faults"), StandardCharsets.UTF_8)
                .replace("horus:mode=\"batch\"", "horus:mode=\"remote\"")
                .replace("horus:timeout=\"PT5S\"", "");

        // When it is imported: a draft, because handler and adapter names are not checked at import
        DefinitionVersion draft = deploy(host, corrected.getBytes(StandardCharsets.UTF_8));
        assertThat(draft.status()).isEqualTo("DRAFT");

        // Then request-approval refuses it, naming each step and the name it uses, and the version stays a draft
        host.identity.as("bank-a", "alice", "processes:author", "processes:read");
        assertThatThrownBy(() -> host.services.requestApproval(draft.ref(), draft.lockVersion(), null, null))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> {
                    assertThat(e.code()).isEqualTo("VALIDATION_FAILED");
                    assertThat(e.problems()).extracting(DefinitionProblem::code).containsExactlyInAnyOrder(
                            "HANDLER_NOT_REGISTERED", "ADAPTER_NOT_REGISTERED");
                    assertThat(e.problems()).extracting(DefinitionProblem::message).anySatisfy(m -> assertThat(m)
                            .startsWith("reserve-limit -> limits.reserv")).anySatisfy(m -> assertThat(m)
                            .startsWith("notify-partner -> partner-hub"));
                });
        host.identity.as("bank-a", "auditor", "processes:read");
        assertThat(host.services.find(draft.ref()).orElseThrow().status()).isEqualTo("DRAFT");
    }

    @Test
    void PRE_072_approval_refuses_a_pending_version_whose_handler_the_host_no_longer_registers() {
        // Given loan-disbursement pending approval on a host that registers limits.reserve and core-banking
        TestEngine maker = engine(null, new AtomicBoolean(), call -> AdapterResult.completed(Map.of()), false);
        DefinitionVersion draft = deploy(maker, bpmn(KEY));
        maker.identity.as("bank-a", "alice", "processes:author", "processes:read");
        DefinitionVersion pending = maker.services.requestApproval(draft.ref(), draft.lockVersion(), null, null);

        // When the approver's host (a replica without core-banking) approves it
        TestEngine approver = TestEngine.replica("pod-2", clock).autoApprove(false)
                .handlers(limits(TestEngine.dataSource(), new AtomicBoolean())).build();
        engines.add(approver);
        approver.identity.as("bank-a", "bob", "processes:approve", "processes:read");

        // Then approval is refused with the step and adapter named, and the version stays pending
        assertThatThrownBy(() -> approver.services.approve(pending.ref(), pending.lockVersion(),
                pending.definitionHash(), "checked", null))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.problems())
                        .singleElement().satisfies(p -> {
                            assertThat(p.code()).isEqualTo("ADAPTER_NOT_REGISTERED");
                            assertThat(p.message()).startsWith("post-to-core -> core-banking");
                        }));
        assertThat(approver.services.find(pending.ref()).orElseThrow().status()).isEqualTo("PENDING_APPROVAL");
        assertThat(Set.of(pending.status())).containsExactly("PENDING_APPROVAL");
    }
}
