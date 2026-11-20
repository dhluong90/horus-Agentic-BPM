package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.internal.service.DecisionCaller;
import io.github.dhluong90.bpm.process.internal.service.DecisionKeys;
import io.github.dhluong90.bpm.process.internal.service.DecisionOutcome;
import io.github.dhluong90.bpm.process.test.TestClock;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A decision step evaluates in the instance's tenant, as the engine on behalf of the user who triggered it, on
 * PostgreSQL with the real Rule Engine (ADR-0027, PRE-046). Two banks share the database: bank-g prices the card fee
 * at 20,000 VND and bank-h at 35,000 VND; the instances run in bank-g. The tenants are this test's own, because the
 * Rule Engine tables are shared by every test of the module.
 */
class DecisionStepIdentityTest {

    private static final Instant REQUESTED = Instant.parse("2027-03-01T02:00:00Z");
    private static final String[] PIPELINE = {"processes:deploy", "processes:start", "processes:read",
            "processes:task:work"};

    private final TestClock clock = TestClock.at(REQUESTED);

    @BeforeEach
    void clean() {
        TestEngine.reset();
    }

    private static void publishFee(TestEngine engine, String tenant, String yaml) {
        engine.ruleIdentity.as(tenant, "lan");
        engine.rules.versionService().importRuleSet(io.github.dhluong90.bpm.rules.api.ImportRequest.yaml(
                TestEngine.resource(yaml)));
        long requested = engine.rules.versionService().requestPublish("card-reminder-fee", 1, WriteOptions.none())
                .lockVersion();
        engine.ruleIdentity.as(tenant, "minh");
        engine.rules.versionService().approve("card-reminder-fee", 1, new WriteOptions(null, requested, null));
    }

    private static ProcessInstance start(TestEngine engine, String bpmn, String key, String card) {
        engine.identity.as("bank-g", "pipeline", PIPELINE);
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML, TestEngine.resource(bpmn), null));
        return engine.services.start(new StartRequest(key, card, Map.of("tier", "CLASSIC"), null));
    }

    private static String text(String sql, Object... params) {
        return TestEngine.jdbc().sql(sql).params(params).query(String.class).single();
    }

    private static int count(String sql, Object... params) {
        return TestEngine.jdbc().sql(sql).params(params).query(Integer.class).single();
    }

    private static String decisionRow(String instanceId) {
        return text("SELECT tenant_id || ' ' || caller_id || ' ' || coalesce(on_behalf_of, '-') || ' ' "
                + "|| (outputs->'fee'->>'amount') FROM rle_decision WHERE correlation_id = ?", instanceId);
    }

    @Test
    void PRE_046_decision_step_uses_the_instance_tenant_and_the_engine_identity() {
        // Given card-reminder-fee in bank-g (20,000 VND) and bank-h (35,000 VND)
        TestEngine pod = TestEngine.replica("pod-1", clock).withRuleEngine().build();
        publishFee(pod, "bank-g", "/rules/card-reminder-fee-bank-a.yaml");
        publishFee(pod, "bank-h", "/rules/card-reminder-fee-bank-b.yaml");

        // (a) When tuan, a card officer of bank-g without any rules permission, completes the review in his request
        ProcessInstance reviewed = start(pod, "/bpmn/card-fee-review.bpmn", "card-fee-review", "CARD-A-4111");
        ProcessInstance.Task review = reviewed.tasks().get(0);
        pod.identity.as("bank-g", "tuan", "processes:task:work", "processes:read").roles = Set.of("CARD_OFFICER");
        // The Rule Engine's own view of the thread names another tenant and no caller: it must not matter.
        pod.ruleIdentity.as("bank-fallback", null);
        long claimed = pod.services.claim(review.id(), review.lockVersion(), "claim-a").lockVersion();
        pod.services.complete(review.id(), Map.of("approved", true), claimed, "complete-a");

        // Then the decision used bank-g's rule set, as the engine, on behalf of tuan
        assertThat(decisionRow(reviewed.id())).isEqualTo("bank-g system:process-engine tuan 20000");

        // When the step is retried with the same attempt (same instance, node and attempt id, a later clock)
        String attempt = text("SELECT attempt_id FROM pre_history WHERE instance_id = ? "
                + "AND event_type = 'DECISION_EVALUATED'", reviewed.id());
        String decisionId = text("SELECT id FROM rle_decision WHERE correlation_id = ?", reviewed.id());
        clock.set(REQUESTED.plus(Duration.ofMinutes(5)));
        DecisionOutcome retried = pod.decisions.orElseThrow().evaluate("bank-g",
                new DecisionCaller.Request("bank-g", "tuan"), "card-reminder-fee", Map.of("tier", "CLASSIC"),
                clock.instant(), DecisionKeys.forStep(reviewed.id(), "price", attempt), reviewed.id());

        // Then the logged decision is replayed, with its original evaluation time, and no second row is written
        assertThat(retried).isInstanceOfSatisfying(DecisionOutcome.Matched.class,
                m -> assertThat(m.decisionId()).isEqualTo(decisionId));
        assertThat(count("SELECT count(*) FROM rle_decision WHERE correlation_id = ?", reviewed.id())).isEqualTo(1);
        assertThat(TestEngine.jdbc().sql("SELECT eval_clock FROM rle_decision WHERE id = ?").params(decisionId)
                .query(java.sql.Timestamp.class).single().toInstant()).isEqualTo(REQUESTED);

        // (b) When a step is reached on a thread with no principal: the timer of an overdue card fee, three days later
        ProcessInstance overdue = start(pod, "/bpmn/card-fee-reminder.bpmn", "card-fee-reminder", "CARD-A-5111");
        clock.set(REQUESTED.plus(Duration.ofDays(4)));
        pod.identity.as("bank-fallback", null);
        pod.ruleIdentity.as("bank-fallback", null);
        assertThat(pod.jobs.pollOnce()).isEqualTo(1);

        // Then the decision used bank-g's rule set, as the engine, on behalf of nobody
        assertThat(decisionRow(overdue.id())).isEqualTo("bank-g system:process-engine - 20000");
        // Other tests share the database, so only this test's instances and tenants are counted.
        assertThat(count("SELECT count(*) FROM rle_decision WHERE tenant_id <> 'bank-g' AND correlation_id IN (?, ?)",
                reviewed.id(), overdue.id())).isZero();
        assertThat(count("SELECT count(*) FROM rle_decision WHERE tenant_id = 'bank-h'")).isZero();

    }
}
