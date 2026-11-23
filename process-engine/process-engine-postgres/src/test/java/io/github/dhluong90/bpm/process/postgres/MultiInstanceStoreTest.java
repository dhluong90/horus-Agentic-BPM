package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.api.TaskView;
import io.github.dhluong90.bpm.process.spi.HandlerResult;
import io.github.dhluong90.bpm.process.spi.ServiceTaskHandler;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Multi-instance (#496, PRE-290 to PRE-293) on PostgreSQL: the loan {@code loan-cosigning.bpmn} opens one task per
 * co-signer under a multi-instance body, ends as soon as two of three have approved, cancels the third task, and keeps
 * the decisions in co-signer order. A collection over the cap opens an incident and writes no task.
 */
class MultiInstanceStoreTest {

    private final TestClock clock = TestClock.at(Instant.parse("2026-11-23T03:00:00Z"));
    private final List<TestEngine> engines = new ArrayList<>();

    @BeforeEach
    void clean() {
        TestEngine.reset();
    }

    @AfterEach
    void stop() {
        engines.forEach(TestEngine::close);
    }

    private TestEngine engine() {
        TestEngine engine = TestEngine.replica("pod-1", clock).autoApprove(true).handlers(new ServiceTaskHandler() {
            @Override
            public String name() {
                return "book-loan";
            }

            @Override
            public HandlerResult handle(io.github.dhluong90.bpm.process.spi.ServiceCall call) {
                return HandlerResult.completed(Map.of("loanId", "LN-2026-0042"));
            }
        }).build();
        engines.add(engine);
        engine.identity.as("bank-a", "pipeline", "processes:deploy", "processes:start", "processes:read",
                "processes:task:work");
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                TestEngine.resource("/bpmn/loan-cosigning.bpmn"), null));
        return engine;
    }

    private static ProcessInstance.Task open(ProcessInstance instance, int index) {
        return instance.openTasks().get(index);
    }

    private static ProcessInstance approve(TestEngine engine, ProcessInstance.Task task, String officer,
            String decision) {
        engine.identity.as("bank-a", officer, "processes:task:work", "processes:read");
        engine.identity.roles = Set.of("CREDIT_OFFICER");
        TaskView claimed = engine.services.claim(task.id(), task.lockVersion(), officer + "-claim-" + task.id());
        return engine.services.complete(task.id(), Map.of("decision", decision), claimed.lockVersion(),
                officer + "-done-" + task.id());
    }

    private static long count(String sql, Object... params) {
        return TestEngine.jdbc().sql(sql).params(params).query(Long.class).single();
    }

    @Test
    void PRE_290_one_task_per_co_signer_is_written_under_a_body_and_the_decisions_keep_co_signer_order() {
        // Given three co-signers of a 450,000,000 VND loan
        TestEngine engine = engine();
        engine.identity.as("bank-a", "officer.lan", "processes:start", "processes:read");
        ProcessInstance started = engine.services.start(new StartRequest("loan-cosigning", "LN-2026-0042",
                Map.of("cosigners", List.of("Tran Van An", "Le Thi Binh", "Pham Quoc Cuong")), null));
        String id = started.id();

        // Then three tasks are open and the tokens are one body, three repetitions under it and one flow token each
        assertThat(started.openTasks()).hasSize(3);
        assertThat(count("SELECT count(*) FROM pre_token WHERE instance_id = ? AND token_kind = 'MI_BODY'", id))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM pre_token i JOIN pre_token b ON b.id = i.scope_token_id AND "
                + "b.token_kind = 'MI_BODY' WHERE i.instance_id = ? AND i.token_kind = 'SCOPE'", id)).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM pre_token f JOIN pre_token i ON i.id = f.scope_token_id AND "
                + "i.token_kind = 'SCOPE' WHERE f.instance_id = ? AND f.token_kind = 'FLOW' AND "
                + "f.status = 'WAITING_USER'", id)).isEqualTo(3);

        // When two of the three approve, whichever tasks they are
        approve(engine, open(started, 2), "officer.cuong", "APPROVE");
        ProcessInstance after = approve(engine, open(started, 0), "officer.an", "APPROVE");

        // Then the loan was booked once and the task of the third was cancelled
        assertThat(after.status()).isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM pre_task WHERE instance_id = ? AND status = 'CANCELLED'", id))
                .isEqualTo(1);

        // And the output has its gap where that co-signer sits in the list, not where the answers came
        long gap = count("SELECT (s.locals->>'loopCounter')::bigint FROM pre_task t JOIN pre_token f ON f.id = "
                + "t.token_id JOIN pre_token s ON s.id = f.scope_token_id WHERE t.instance_id = ? AND "
                + "t.status = 'CANCELLED'", id);
        List<String> expected = new ArrayList<>(Arrays.asList("APPROVE", "APPROVE", "APPROVE"));
        expected.set((int) gap - 1, null);
        assertThat(after.variables().get("verdicts")).isEqualTo(expected);
        assertThat(count("SELECT count(*) FROM pre_token WHERE instance_id = ? AND token_kind = 'MI_BODY' AND "
                + "status = 'COMPLETED'", id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM pre_token WHERE instance_id = ? AND status NOT IN ('COMPLETED', "
                + "'CANCELLED')", id)).isZero();

        // And the history carries the counters of each repetition
        assertThat(TestEngine.jdbc().sql("SELECT details->>'nrOfCompletedInstances' FROM pre_history WHERE "
                + "instance_id = ? AND event_type = 'MI_INSTANCE_COMPLETED' ORDER BY occurred_at, id").param(id)
                .query(String.class).list()).containsExactly("1", "2");
    }

    @Test
    void PRE_293_a_collection_over_the_cap_opens_an_incident_and_writes_no_task() {
        // Given a cap of two repetitions per activity
        TestEngine engine = engine();
        engine.services.useMaxLoopInstances(2);
        engine.identity.as("bank-a", "officer.lan", "processes:start", "processes:read");

        // When a loan with three co-signers starts
        ProcessInstance started = engine.services.start(new StartRequest("loan-cosigning", "LN-2026-0043",
                Map.of("cosigners", List.of("An", "Binh", "Cuong")), null));

        // Then an incident is open on the step, no task and no body exist, and the instance still runs
        assertThat(started.status()).isEqualTo("RUNNING");
        assertThat(started.openTasks()).isEmpty();
        assertThat(count("SELECT count(*) FROM pre_task WHERE instance_id = ?", started.id())).isZero();
        assertThat(count("SELECT count(*) FROM pre_token WHERE instance_id = ? AND token_kind = 'MI_BODY'",
                started.id())).isZero();
        assertThat(TestEngine.jdbc().sql("SELECT error_code || ':' || node_id || ':' || status FROM pre_incident "
                + "WHERE instance_id = ?").param(started.id()).query(String.class).list())
                .containsExactly("MULTI_INSTANCE_LIMIT:cosign:OPEN");
    }
}
