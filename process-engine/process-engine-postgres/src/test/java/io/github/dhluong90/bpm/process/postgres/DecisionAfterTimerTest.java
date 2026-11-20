package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.test.TestClock;
import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionRequest;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.RuleEngine;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A decision step reached right after a timer, on PostgreSQL with the real Rule Engine (ADR-0027). The timer fires
 * on the job poller with no one signed in; the decision is evaluated in the tenant of the stored instance row, as the
 * engine, and never in whatever tenant the thread's security context happens to resolve. The example is an overdue
 * card fee: three days later the reminder fee is priced by the bank's own {@code card-reminder-fee} rule set
 * (20,000 VND at one bank, 35,000 VND at the other) and the collections team gets a task.
 */
class DecisionAfterTimerTest {

    private static final Instant OVERDUE = Instant.parse("2027-03-01T02:00:00Z");
    private static final Instant DUE = OVERDUE.plus(Duration.ofDays(3));
    private static final String[] PIPELINE = {"processes:deploy", "processes:start", "processes:read",
            "processes:task:work"};

    private final TestClock clock = TestClock.at(OVERDUE);

    @BeforeEach
    void clean() {
        TestEngine.reset();
    }

    private static void publishReminderFee(TestEngine engine, String tenant, String yaml) {
        engine.ruleIdentity.as(tenant, "lan");
        engine.rules.versionService().importRuleSet(io.github.dhluong90.bpm.rules.api.ImportRequest.yaml(
                TestEngine.resource(yaml)));
        engine.rules.versionService().requestPublish("card-reminder-fee", 1, WriteOptions.none());
        engine.ruleIdentity.as(tenant, "minh");
        engine.rules.versionService().approve("card-reminder-fee", 1, WriteOptions.none());
    }

    private static ProcessInstance overdue(TestEngine engine, String tenant, String card) {
        engine.identity.as(tenant, "pipeline", PIPELINE);
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                TestEngine.resource("/bpmn/card-fee-reminder.bpmn"), null));
        return engine.services.start(new StartRequest("card-fee-reminder", card, Map.of("tier", "CLASSIC"), null));
    }

    /** What the poller thread of a careless host would hold: a fallback tenant and no caller. */
    private static void pollerThreadContext(TestEngine engine) {
        engine.identity.as("bank-fallback", null);
        engine.ruleIdentity.as("bank-fallback", null);
    }

    private static int count(String sql, Object... params) {
        return TestEngine.jdbc().sql(sql).params(params).query(Integer.class).single();
    }

    private static String text(String sql, Object... params) {
        return TestEngine.jdbc().sql(sql).params(params).query(String.class).single();
    }

    @Test
    void PRE_055_decision_after_a_timer_runs_in_the_instance_tenant_as_the_engine() {
        // Given card-reminder-fee in bank-a (20,000 VND) and bank-b (35,000 VND), and one overdue card in each bank
        TestEngine pod = TestEngine.replica("pod-1", clock).withRuleEngine().build();
        publishReminderFee(pod, "bank-a", "/rules/card-reminder-fee-bank-a.yaml");
        publishReminderFee(pod, "bank-b", "/rules/card-reminder-fee-bank-b.yaml");
        ProcessInstance inBankA = overdue(pod, "bank-a", "CARD-A-4111");
        ProcessInstance inBankB = overdue(pod, "bank-b", "CARD-B-4111");

        // When both timers are due at the same instant and one poll fires them, on a thread with no caller
        clock.set(DUE);
        pollerThreadContext(pod);
        int fired = pod.jobs.pollOnce();

        // Then each decision used its own tenant's rule set, as the engine, and each instance moved on
        assertThat(fired).isEqualTo(2);
        assertThat(text("SELECT tenant_id || ' ' || caller_id || ' ' || outputs::text FROM rle_decision "
                + "WHERE correlation_id = ?", inBankA.id()))
                .isEqualTo("bank-a system:process-engine {\"fee\": {\"amount\": 20000, \"currency\": \"VND\"}}");
        assertThat(text("SELECT tenant_id || ' ' || caller_id || ' ' || outputs::text FROM rle_decision "
                + "WHERE correlation_id = ?", inBankB.id()))
                .isEqualTo("bank-b system:process-engine {\"fee\": {\"amount\": 35000, \"currency\": \"VND\"}}");
        assertThat(count("SELECT count(*) FROM rle_decision WHERE tenant_id = 'bank-fallback'")).isZero();
        assertThat(text("SELECT variables->'pricing'->'fee'->>'amount' FROM pre_instance WHERE tenant_id = 'bank-a' "
                + "AND id = ?", inBankA.id())).isEqualTo("20000");
        assertThat(text("SELECT variables->'pricing'->'fee'->>'amount' FROM pre_instance WHERE tenant_id = 'bank-b' "
                + "AND id = ?", inBankB.id())).isEqualTo("35000");
        assertThat(text("SELECT variables->'pricing'->>'decisionId' FROM pre_instance WHERE tenant_id = 'bank-a' "
                + "AND id = ?", inBankA.id())).isEqualTo(text("SELECT id FROM rle_decision WHERE correlation_id = ?",
                inBankA.id()));
        for (ProcessInstance instance : new ProcessInstance[] {inBankA, inBankB}) {
            assertThat(text("SELECT tenant_id || ' ' || node_id || ' ' || status FROM pre_task WHERE instance_id = ?",
                    instance.id())).endsWith(" collect-fee OPEN");
            assertThat(count("SELECT count(*) FROM pre_history WHERE instance_id = ? "
                    + "AND event_type = 'DECISION_EVALUATED'", instance.id())).isEqualTo(1);
            assertThat(text("SELECT status FROM pre_timer WHERE instance_id = ?", instance.id())).isEqualTo("DONE");
        }
        assertThat(text("SELECT tenant_id FROM pre_task WHERE instance_id = ?", inBankB.id())).isEqualTo("bank-b");
    }

    @Test
    void PRE_056_decision_after_a_timer_never_reads_another_tenants_rule_set() {
        // Given card-reminder-fee exists only in bank-d, and an overdue card in bank-c
        TestEngine pod = TestEngine.replica("pod-1", clock).withRuleEngine().retryBackoff(Duration.ofSeconds(30)).build();
        publishReminderFee(pod, "bank-d", "/rules/card-reminder-fee-bank-b.yaml");
        ProcessInstance inBankC = overdue(pod, "bank-c", "CARD-C-4111");

        // When the timer fires on a thread whose context even resolves to bank-d
        clock.set(DUE);
        pod.identity.as("bank-d", null);
        pod.ruleIdentity.as("bank-d", null);
        int fired = pod.jobs.pollOnce();

        // Then the decision failed closed and the whole firing rolled back: nothing was written in either tenant
        assertThat(fired).isZero();
        assertThat(count("SELECT count(*) FROM rle_decision WHERE tenant_id IN ('bank-c', 'bank-d')")).isZero();
        assertThat(count("SELECT count(*) FROM pre_task WHERE instance_id = ?", inBankC.id())).isZero();
        assertThat(count("SELECT count(*) FROM pre_history WHERE instance_id = ? AND event_type IN "
                + "('TIMER_FIRED', 'DECISION_EVALUATED')", inBankC.id())).isZero();
        assertThat(text("SELECT status FROM pre_token WHERE tenant_id = 'bank-c' AND instance_id = ? "
                + "AND current_node_id = 'wait-three-days'", inBankC.id())).isEqualTo("WAITING_TIMER");
        assertThat(text("SELECT status || ' ' || attempt FROM pre_timer WHERE instance_id = ?", inBankC.id()))
                .isEqualTo("DUE 1");
        assertThat(text("SELECT variables::text FROM pre_instance WHERE tenant_id = 'bank-c' AND id = ?",
                inBankC.id())).doesNotContain("pricing");
    }

    @Test
    void PRE_056_a_rule_engine_without_an_explicit_tenant_cannot_run_a_decision_after_a_timer() {
        // Given a host Rule Engine that can only resolve its tenant from the thread, and an overdue card in bank-e
        AtomicInteger evaluations = new AtomicInteger();
        RuleEngine threadBound = new RuleEngine() {
            @Override
            public DecisionResult evaluate(String ruleSet, DecisionInput input) {
                return evaluate(DecisionRequest.of(ruleSet, input));
            }

            @Override
            public DecisionResult evaluate(DecisionRequest request) {
                evaluations.incrementAndGet();
                throw new IllegalStateException("must not be called for engine-owned work");
            }
        };
        TestEngine pod = TestEngine.replica("pod-1", clock).ruleEngine(threadBound).build();
        ProcessInstance inBankE = overdue(pod, "bank-e", "CARD-E-4111");

        // When the timer fires
        clock.set(DUE);
        pollerThreadContext(pod);
        int fired = pod.jobs.pollOnce();

        // Then the step failed closed without calling the Rule Engine, and nothing moved
        assertThat(fired).isZero();
        assertThat(evaluations).hasValue(0);
        assertThat(count("SELECT count(*) FROM pre_task WHERE instance_id = ?", inBankE.id())).isZero();
        assertThat(text("SELECT status || ' ' || attempt FROM pre_timer WHERE instance_id = ?", inBankE.id()))
                .isEqualTo("DUE 1");
    }
}
