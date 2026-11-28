package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.internal.service.AsyncStepExecutor.Claim;
import io.github.dhluong90.bpm.process.internal.store.VariableVault;
import io.github.dhluong90.bpm.process.spi.AdapterResult;
import io.github.dhluong90.bpm.process.spi.HandlerResult;
import io.github.dhluong90.bpm.process.spi.ServiceAdapter;
import io.github.dhluong90.bpm.process.spi.ServiceTaskHandler;
import io.github.dhluong90.bpm.process.spi.VariableEncryptionRules;
import io.github.dhluong90.bpm.process.spi.VariableKeyring;
import io.github.dhluong90.bpm.process.spi.VariableKeyring.Key;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PRE-525 on a flow that reaches the stores a start and a task do not: a transfer of 25,000,000 VND from account
 * 0011-223344 that holds the funds (a local step with an undo), debits the source (a remote step), fails to credit the
 * target and is undone. The amount and the account are protected; after the flow no column of any {@code pre_*} table
 * holds either in the clear, and the engine still reads the instance back whole.
 */
class VariableEncryptionCoverageIT {

    private static final String AMOUNT = "25000000";
    private static final String SOURCE = "0011-223344";
    private static final Key K1 = new Key("k1", filled(1));
    private final TestClock clock = TestClock.at(Instant.parse("2026-11-21T03:00:00Z"));
    private final List<TestEngine> engines = new ArrayList<>();

    private static byte[] filled(int seed) {
        byte[] b = new byte[32];
        java.util.Arrays.fill(b, (byte) seed);
        return b;
    }

    private static VariableVault vault() {
        return new VariableVault(new VariableKeyring() {
            @Override
            public Optional<Key> current() {
                return Optional.of(K1);
            }
        }, new VariableEncryptionRules(Set.of("amountMinor", "sourceAccount", "hold", "debit"), Map.of()));
    }

    @BeforeEach
    void clean() {
        TestEngine.reset();
    }

    @AfterEach
    void stop() {
        engines.forEach(TestEngine::close);
    }

    private static ServiceTaskHandler handler(String name, Function<io.github.dhluong90.bpm.process.spi.ServiceCall,
            HandlerResult> body) {
        return new ServiceTaskHandler() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public HandlerResult handle(io.github.dhluong90.bpm.process.spi.ServiceCall call) {
                return body.apply(call);
            }
        };
    }

    private static ServiceAdapter adapter(String name, Function<io.github.dhluong90.bpm.process.spi.AdapterCall,
            AdapterResult> body) {
        return new ServiceAdapter() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public AdapterResult execute(io.github.dhluong90.bpm.process.spi.AdapterCall call) {
                return body.apply(call);
            }
        };
    }

    private TestEngine engine(VariableVault vault) {
        TestEngine engine = TestEngine.replica("pod-1", clock).autoApprove(true).variableVault(vault)
                .handlers(handler("place-hold", call -> HandlerResult.completed(Map.of("holdId", "HLD-1"))),
                        handler("release-hold", call -> HandlerResult.completed(Map.of("released", true))))
                .adapters(adapter("core-banking", call -> "debit-source".equals(call.nodeId())
                        ? AdapterResult.completed(Map.of("postingRef", "FT26325001"))
                        : new AdapterResult.BusinessError("ACCOUNT_CLOSED", "account 0099-887766 is closed")),
                        adapter("credit-back", call -> AdapterResult.completed(Map.of("reversalRef", "FT26325002"))))
                .build();
        engines.add(engine);
        engine.identity.as("bank-a", "teller.hoa", "processes:deploy", "processes:start", "processes:read",
                "processes:step:complete");
        return engine;
    }

    private static void drain(TestEngine engine) {
        for (int round = 0; round < 20; round++) {
            List<Claim> claimed = engine.async.claim(10);
            if (claimed.isEmpty()) {
                return;
            }
            claimed.forEach(engine.async::runNow);
        }
    }

    private ProcessInstance failedTransfer(TestEngine engine) {
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                TestEngine.resource("/bpmn/fund-transfer.bpmn"), null));
        ProcessInstance started = engine.services.start(new StartRequest("fund-transfer", "TRF-2026-0001",
                Map.of("amountMinor", Long.parseLong(AMOUNT), "currency", "VND", "sourceAccount", SOURCE,
                        "targetAccount", "0099-887766"), null));
        drain(engine);
        return started;
    }

    @Test
    void PRE_525_no_column_of_any_table_holds_the_amount_after_a_remote_step_an_undo_and_the_history() {
        TestEngine engine = engine(vault());

        ProcessInstance started = failedTransfer(engine);

        // the flow reached the stores the claim covers: the stack, the jobs, the history and the step results
        assertThat(count("pre_compensation_stack")).isPositive();
        assertThat(count("pre_timer")).isPositive();
        assertThat(count("pre_history")).isPositive();
        for (String table : TestEngine.jdbc().sql("SELECT tablename FROM pg_tables WHERE schemaname = current_schema() "
                + "AND tablename LIKE 'pre\\_%'").query(String.class).list()) {
            for (String clear : List.of(AMOUNT, SOURCE)) {
                assertThat(TestEngine.jdbc().sql("SELECT count(*) FROM " + table + " t WHERE t::text LIKE ?")
                        .param("%" + clear + "%").query(Long.class).single()).as(table + " holds " + clear).isZero();
            }
        }
        engine.identity.as("bank-a", "teller.hoa", "processes:read");
        assertThat(engine.services.instance(started.id()).variables()).containsEntry("sourceAccount", SOURCE);
    }

    @Test
    void PRE_525_without_a_protected_variable_the_same_flow_stores_the_amount_in_the_clear() {
        // control: the scan above finds something to find, so it cannot pass by looking at the wrong text
        TestEngine engine = engine(VariableVault.NONE);

        failedTransfer(engine);

        assertThat(TestEngine.jdbc().sql("SELECT count(*) FROM pre_compensation_stack t WHERE t::text LIKE ?")
                .param("%" + AMOUNT + "%").query(Long.class).single()).isPositive();
        assertThat(TestEngine.jdbc().sql("SELECT count(*) FROM pre_timer t WHERE t::text LIKE ?")
                .param("%" + AMOUNT + "%").query(Long.class).single()).isPositive();
    }

    @Test
    void PRE_525_a_history_member_a_start_arrival_a_step_result_and_a_job_payload_are_sealed_and_read_back() {
        TestEngine engine = engine(vault());
        ProcessInstance started = failedTransfer(engine);
        String definitionId = TestEngine.jdbc().sql("SELECT definition_id FROM pre_instance WHERE id = ?")
                .param(started.id()).query(String.class).single();
        JdbcProcessStore store = new JdbcProcessStore(TestEngine.dataSource(),
                java.time.Duration.ofSeconds(2), vault());
        Instant at = Instant.parse("2026-11-21T04:00:00Z");

        store.appendHistory("bank-a", started.id(), definitionId, List.of(new io.github.dhluong90.bpm.process.internal.exec.HistoryEntry(
                "TASK_COMPLETED", "approve", null, null, "officer-lan", at,
                Map.of("submission", Map.of("limit", 31_000_000), "output", Map.of("score", 770_000_001),
                        "changes", Map.of("limit", Map.of("from", 41_000_000, "to", 42_000_000)), "jobId", "J1"))));
        store.putStartArrival("bank-a", "loan-start", "MESSAGE", "application-received", "LN-1",
                "{\"amountMinor\":52000000}", at);
        store.recordStepResult(new io.github.dhluong90.bpm.process.internal.store.ProcessStore.StepResultRow("bank-a",
                started.id(), "assess", definitionId, "{\"score\":63000000}", "ANSWER", "A1", at));

        String history = TestEngine.jdbc().sql("SELECT details::text FROM pre_history WHERE actor_id = 'officer-lan'")
                .query(String.class).single();
        assertThat(history).contains("$enc", "\"jobId\"").doesNotContain("31000000", "770000001", "41000000");
        assertThat(store.historyEntries("bank-a", started.id(), at.minusSeconds(60), at.plusSeconds(60), 10))
                .anySatisfy(e -> assertThat(e.submission()).containsEntry("limit", 31_000_000L));
        assertThat(rawText("pre_start_arrival")).contains("$enc").doesNotContain("52000000");
        assertThat(store.startArrival("bank-a", "loan-start", "MESSAGE", "application-received", "LN-1"))
                .contains("{\"amountMinor\":52000000}");
        assertThat(rawText("pre_step_result")).contains("$enc").doesNotContain("63000000");
        assertThat(store.stepResults("bank-a", started.id(), List.of("assess"))).containsEntry("assess",
                "{\"score\":63000000}");
    }

    @Test
    void PRE_525_a_job_payload_is_sealed_whole_and_a_schema_retry_counts_inside_the_seal() {
        TestEngine engine = engine(vault());
        JdbcJobStore jobs = new JdbcJobStore(TestEngine.dataSource(), vault());
        Instant at = Instant.parse("2026-11-21T04:00:00Z");
        io.github.dhluong90.bpm.process.internal.store.JobStore.JobRow row =
                io.github.dhluong90.bpm.process.internal.store.JobStore.JobRow.due("bank-a", "J-SEALED", null, null, null,
                        "DEFINITION_EFFECTIVE", null, at, java.time.Duration.ofSeconds(30), 3,
                        "{\"inputs\":{\"amountMinor\":48000000}}");

        jobs.insertJob(row, at);
        TestEngine.jdbc().sql("UPDATE pre_timer SET status = 'CLAIMED', locked_by = 'pod-1', "
                + "locked_until = now() + interval '1 minute', attempt = 1 WHERE id = 'J-SEALED'").update();
        boolean retried = jobs.retrySchema("bank-a", "J-SEALED", "pod-1", 1, at.plusSeconds(10));

        assertThat(retried).isTrue();
        assertThat(rawText("pre_timer")).contains("$enc").doesNotContain("48000000", "schemaRetries");
        assertThat(jobs.findJob("bank-a", "J-SEALED").orElseThrow().payloadJson())
                .contains("48000000").contains("\"" + io.github.dhluong90.bpm.process.internal.store.JobStore.SCHEMA_RETRIES
                        + "\":1");
        assertThat(engine).isNotNull();
    }

    private static String rawText(String table) {
        return TestEngine.jdbc().sql("SELECT string_agg(t::text, ' ') FROM " + table + " t").query(String.class).single();
    }

    private static long count(String table) {
        return TestEngine.jdbc().sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
