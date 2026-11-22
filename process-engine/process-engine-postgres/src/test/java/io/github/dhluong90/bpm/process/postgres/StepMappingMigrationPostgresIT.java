package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.MigrationDryRun;
import io.github.dhluong90.bpm.process.api.MigrationExecution;
import io.github.dhluong90.bpm.process.api.MigrationNotFoundException;
import io.github.dhluong90.bpm.process.api.MigrationPlan;
import io.github.dhluong90.bpm.process.api.MigrationPlanInvalidException;
import io.github.dhluong90.bpm.process.api.MigrationPlanRequest;
import io.github.dhluong90.bpm.process.api.MigrationStepCancellation;
import io.github.dhluong90.bpm.process.api.MigrationStepMapping;
import io.github.dhluong90.bpm.process.api.PermissionDeniedException;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.api.VersionConflictException;
import io.github.dhluong90.bpm.process.internal.service.MigrationExecutions;
import io.github.dhluong90.bpm.process.internal.service.MigrationRunner;
import io.github.dhluong90.bpm.process.internal.service.MigrationSettings;
import io.github.dhluong90.bpm.process.internal.service.Migrations;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Step instructions of a migration plan on PostgreSQL (#106, PRE-207 to PRE-210, {@code TR-MIG-03}): a renamed step is
 * mapped and its tasks keep their claim, due time and four-eyes rule; a removed step's work is cancelled with a reason and
 * the token continues where the plan says; a plan that cannot be carried out is refused; and every command is
 * permissioned, idempotent, locked and tenant-bound.
 */
class StepMappingMigrationPostgresIT {

    private static final String KEY = "loan-origination";
    private static final String[] PIPELINE = {"processes:deploy", "processes:start", "processes:read"};
    private static final String MANAGE = "processes:migration:manage";
    private static final MigrationSettings SETTINGS = new MigrationSettings(MigrationPlan.Approval.FOUR_EYES, 100,
            Duration.ofHours(1), 100, Duration.ofMinutes(2));

    /** v1: one credit review, by a four-eyes task with an eight-hour SLA. */
    private static final String RENAME_V1 = """
            <bpmn:startEvent id="start" name="Application received"/>
            <bpmn:userTask id="credit-review" name="Credit review" horus:assignee="role:CREDIT_OFFICER" horus:fourEyes="true" horus:sla="PT8H" horus:resultVariable="review"/>
            <bpmn:endEvent id="end" name="Decided"/>
            <bpmn:sequenceFlow id="start__review" sourceRef="start" targetRef="credit-review"/>
            <bpmn:sequenceFlow id="review__end" sourceRef="credit-review" targetRef="end"/>
            """;
    /** v2: the step is renamed {@code credit-assessment}, with the same task kind. */
    private static final String RENAME_V2 = RENAME_V1.replace("credit-review", "credit-assessment");

    /** v1: large loans go to a manual check, small ones to a remote score; both steps are removed by v2. */
    private static final String REMOVE_V1 = """
            <bpmn:startEvent id="start" name="Application received"/>
            <bpmn:exclusiveGateway id="size-check" name="Large loan?" default="size-check__score"/>
            <bpmn:userTask id="legacy-manual-check" name="Manual check" horus:assignee="role:CREDIT_OFFICER" horus:resultVariable="check"/>
            <bpmn:serviceTask id="legacy-score" name="Legacy score" horus:handler="bureau" horus:mode="remote" horus:resultVariable="score"/>
            <bpmn:endEvent id="end" name="Decided"/>
            <bpmn:sequenceFlow id="start__size" sourceRef="start" targetRef="size-check"/>
            <bpmn:sequenceFlow id="size__manual" sourceRef="size-check" targetRef="legacy-manual-check">
              <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">amount &gt;= 5000000000 VND</bpmn:conditionExpression>
            </bpmn:sequenceFlow>
            <bpmn:sequenceFlow id="size-check__score" sourceRef="size-check" targetRef="legacy-score"/>
            <bpmn:sequenceFlow id="manual__end" sourceRef="legacy-manual-check" targetRef="end"/>
            <bpmn:sequenceFlow id="score__end" sourceRef="legacy-score" targetRef="end"/>
            """;
    private static final String REMOVE_V2 = """
            <bpmn:startEvent id="start" name="Application received"/>
            <bpmn:userTask id="credit-assessment" name="Credit assessment" horus:assignee="role:CREDIT_OFFICER" horus:resultVariable="assessment"/>
            <bpmn:serviceTask id="bureau-score" name="Bureau score" horus:handler="bureau" horus:mode="remote" horus:resultVariable="score"/>
            <bpmn:endEvent id="end" name="Decided"/>
            <bpmn:sequenceFlow id="start__assess" sourceRef="start" targetRef="credit-assessment"/>
            <bpmn:sequenceFlow id="assess__score" sourceRef="credit-assessment" targetRef="bureau-score"/>
            <bpmn:sequenceFlow id="score__end" sourceRef="bureau-score" targetRef="end"/>
            """;

    private final TestClock clock = TestClock.at("2027-03-01T02:00:00Z");
    private TestEngine engine;
    private JdbcClient jdbc;
    private Migrations plans;
    private MigrationExecutions executions;

    @BeforeEach
    void setUp() {
        TestEngine.reset();
        engine = TestEngine.replica("pod-1", clock).runners(Set.of("bureau")).build();
        jdbc = TestEngine.jdbc();
        plans = engine.migrations(SETTINGS);
        executions = engine.migrationExecutions(SETTINGS);
    }

    private static byte[] bpmn(String process) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                  xmlns:horus="urn:horus-bpm:bpmn:extensions:v1"
                                  id="Definitions_loan-origination" targetNamespace="urn:horus-bpm:examples"
                                  expressionLanguage="urn:horus-bpm:expr:v1">
                  <bpmn:process id="loan-origination" name="Loan origination" isExecutable="true">
                %s
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(process).getBytes(StandardCharsets.UTF_8);
    }

    private void importAs(String tenant, String process) {
        engine.identity.as(tenant, "pipeline", PIPELINE);
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML, bpmn(process), null));
    }

    /** Starts {@code large} loans of 6 billion VND and {@code small} of 300 million. */
    private void startLoans(String tenant, int large, int small) {
        engine.identity.as(tenant, "officer-lan", PIPELINE);
        for (int i = 0; i < large + small; i++) {
            long amount = i < large ? 6_000_000_000L : 300_000_000L;
            engine.services.start(new StartRequest(KEY, tenant + "-LN-" + String.format("%06d", i),
                    Map.of("amount", Money.ofMinor("VND", amount)), null));
        }
    }

    private void as(String tenant, String caller, String... permissions) {
        engine.identity.as(tenant, caller, permissions);
    }

    private MigrationPlan plan(String tenant, List<MigrationStepMapping> mappings,
            List<MigrationStepCancellation> cancellations, String key) {
        as(tenant, "thu", MANAGE, "processes:read");
        return plans.plan(new MigrationPlanRequest(KEY, 1, 2, key, mappings, cancellations));
    }

    /** Proposes as thu, approves as lan, and runs the runner until the plan is done. */
    private void execute(String tenant, MigrationPlan plan) {
        as(tenant, "thu", MANAGE, "processes:read");
        MigrationExecution proposal = executions.propose(plan.id(), "Rename and retire steps, change CHG-2090", null);
        as(tenant, "lan", MANAGE, "processes:read");
        executions.approve(plan.id(), proposal.lockVersion(), null);
        engine.migrationRunner(SETTINGS, "pod-1", MigrationRunner.Checkpoint.NONE).runDue(20);
    }

    private long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    @Test
    void PRE_207_a_renamed_step_is_mapped_and_its_tasks_keep_their_claim_deadline_and_four_eyes() {
        // Given 40 loans waiting on credit-review of v1, 15 of the tasks claimed, and v2 which renames the step
        importAs("bank-a", RENAME_V1);
        startLoans("bank-a", 0, 40);
        engine.identity.as("bank-a", "officer-minh", "processes:task:work", "processes:read").roles =
                Set.of("CREDIT_OFFICER");
        List<String> taskIds = jdbc.sql("SELECT id FROM pre_task WHERE tenant_id = 'bank-a' ORDER BY id")
                .query(String.class).list();
        for (String id : taskIds.subList(0, 15)) {
            long lock = count("SELECT lock_version FROM pre_task WHERE tenant_id = 'bank-a' AND id = ?", id);
            engine.services.claim(id, lock, "claim-" + id);
        }
        String dueBefore = jdbc.sql("SELECT string_agg(id || '@' || due_at::text, ',' ORDER BY id) FROM pre_task "
                + "WHERE tenant_id = 'bank-a'").query(String.class).single();
        importAs("bank-a", RENAME_V2);
        MigrationPlan plan = plan("bank-a", List.of(new MigrationStepMapping("credit-review", "credit-assessment")),
                List.of(), null);

        // When the plan is validated
        MigrationDryRun dry = plans.validate(plan.id());

        // Then it reports 40 mapped, 0 unmapped, no conflicts, and changes nothing
        assertThat(dry.valid()).isTrue();
        assertThat(dry.conflicts()).isEmpty();
        assertThat(dry.impact().mapped()).isEqualTo(40);
        assertThat(dry.impact().unmapped()).isZero();
        assertThat(dry.nodes()).singleElement().satisfies(n -> {
            assertThat(n.mapping()).isEqualTo("MAPPED");
            assertThat(n.nodeId()).isEqualTo("credit-review");
            assertThat(n.targetNodeId()).isEqualTo("credit-assessment");
        });
        assertThat(count("SELECT count(*) FROM pre_token WHERE tenant_id = 'bank-a' AND current_node_id = "
                + "'credit-review'")).isEqualTo(40);

        // When it is proposed, approved and executed
        execute("bank-a", plan);

        // Then every token and task is on credit-assessment, with the same task ids, claims, due times and four-eyes
        assertThat(count("SELECT count(*) FROM pre_token WHERE tenant_id = 'bank-a' AND current_node_id = "
                + "'credit-assessment' AND status = 'WAITING_USER'")).isEqualTo(40);
        assertThat(count("SELECT count(*) FROM pre_task WHERE tenant_id = 'bank-a' AND node_id = 'credit-assessment'"))
                .isEqualTo(40);
        assertThat(jdbc.sql("SELECT string_agg(id || '@' || due_at::text, ',' ORDER BY id) FROM pre_task "
                + "WHERE tenant_id = 'bank-a'").query(String.class).single()).isEqualTo(dueBefore);
        assertThat(count("SELECT count(*) FROM pre_task WHERE tenant_id = 'bank-a' AND status = 'CLAIMED'"))
                .isEqualTo(15);
        assertThat(count("SELECT count(*) FROM pre_task WHERE tenant_id = 'bank-a' AND four_eyes")).isEqualTo(40);
        // and each instance has a MIGRATED row naming the rename
        assertThat(count("SELECT count(*) FROM pre_history WHERE tenant_id = 'bank-a' AND event_type = 'MIGRATED' "
                + "AND details->'steps'->>0 = 'credit-review -> credit-assessment'")).isEqualTo(40);
        // and a claimed task is still completable on the new step
        engine.identity.as("bank-a", "officer-minh", "processes:task:work", "processes:read").roles =
                Set.of("CREDIT_OFFICER");
        long lock = count("SELECT lock_version FROM pre_task WHERE tenant_id = 'bank-a' AND id = ?", taskIds.get(0));
        engine.services.complete(taskIds.get(0), Map.of("verdict", "approved"), lock, "complete-1");
        assertThat(count("SELECT count(*) FROM pre_task WHERE tenant_id = 'bank-a' AND id = ? AND status = 'COMPLETED'",
                taskIds.get(0))).isEqualTo(1);
    }

    @Test
    void PRE_207_a_mapping_to_another_kind_of_step_or_to_a_missing_step_is_refused_naming_the_pair() {
        // Given v1 with a user task and a v2 that has a timer step and no credit-assessment
        importAs("bank-a", RENAME_V1);
        importAs("bank-a", RENAME_V1.replace("credit-review", "cooling-off").replace(
                "<bpmn:userTask id=\"cooling-off\" name=\"Credit review\" horus:assignee=\"role:CREDIT_OFFICER\" "
                        + "horus:fourEyes=\"true\" horus:sla=\"PT8H\" horus:resultVariable=\"review\"/>",
                "<bpmn:intermediateCatchEvent id=\"cooling-off\" name=\"Wait\"><bpmn:timerEventDefinition "
                        + "id=\"t1\"><bpmn:timeDuration xsi:type=\"bpmn:tFormalExpression\">P1D</bpmn:timeDuration>"
                        + "</bpmn:timerEventDefinition></bpmn:intermediateCatchEvent>"));
        long plansBefore = count("SELECT count(*) FROM pre_migration");
        as("bank-a", "thu", MANAGE, "processes:read");

        // When a user task is mapped to a timer, and to a step v2 does not have
        // Then 422 MIGRATION_PLAN_INVALID names the pair, and no plan is written
        assertThatThrownBy(() -> plans.plan(new MigrationPlanRequest(KEY, 1, 2, null,
                List.of(new MigrationStepMapping("credit-review", "cooling-off")), List.of())))
                .isInstanceOfSatisfying(MigrationPlanInvalidException.class, e -> {
                    assertThat(e.code()).isEqualTo("MIGRATION_PLAN_INVALID");
                    assertThat(e.getMessage()).contains("credit-review -> cooling-off");
                });
        assertThatThrownBy(() -> plans.plan(new MigrationPlanRequest(KEY, 1, 2, null,
                List.of(new MigrationStepMapping("credit-review", "credit-assessment")), List.of())))
                .isInstanceOfSatisfying(MigrationPlanInvalidException.class,
                        e -> assertThat(e.getMessage()).contains("credit-review -> credit-assessment"));
        assertThat(count("SELECT count(*) FROM pre_migration")).isEqualTo(plansBefore);
    }

    private MigrationPlan removalPlan(String key) {
        return plan("bank-a", List.of(), List.of(
                new MigrationStepCancellation("legacy-manual-check", "credit-assessment",
                        "Check merged into credit assessment"),
                new MigrationStepCancellation("legacy-score", "bureau-score", "Replaced by bureau score")), key);
    }

    @Test
    void PRE_208_a_removed_steps_work_is_cancelled_with_a_reason_and_the_token_continues_where_the_plan_says() {
        // Given 12 loans on a manual check, 3 on a remote score (2 due, 1 with a call in flight), and v2 without both
        importAs("bank-a", REMOVE_V1);
        startLoans("bank-a", 12, 3);
        List<String> scoreInstances = jdbc.sql("SELECT instance_id FROM pre_timer WHERE tenant_id = 'bank-a' AND "
                + "node_id = 'legacy-score' ORDER BY instance_id").query(String.class).list();
        String inFlight = scoreInstances.get(2);
        jdbc.sql("UPDATE pre_timer SET status = 'CLAIMED', locked_by = 'pod-9', locked_until = now() + interval "
                + "'5 minutes', attempt = 1 WHERE tenant_id = 'bank-a' AND instance_id = ?").param(inFlight).update();
        importAs("bank-a", REMOVE_V2);
        MigrationPlan plan = removalPlan(null);

        // When the plan is validated
        MigrationDryRun dry = plans.validate(plan.id());

        // Then it reports 12 + 2 to cancel and one conflict STEP_IN_FLIGHT, and the plan is still valid
        assertThat(dry.impact().cancelled()).isEqualTo(14);
        assertThat(dry.impact().inFlight()).isEqualTo(1);
        assertThat(dry.valid()).isTrue();
        assertThat(dry.conflicts()).singleElement().satisfies(c -> {
            assertThat(c.code()).isEqualTo("STEP_IN_FLIGHT");
            assertThat(c.nodeId()).isEqualTo("legacy-score");
            assertThat(c.affected().instanceIds()).containsExactly(inFlight);
        });
        assertThat(dry.instances().movable()).isEqualTo(14);

        // When it is executed
        execute("bank-a", plan);

        // Then 12 tasks are cancelled with the reason, 2 jobs are cancelled, and each token continues at its step
        assertThat(count("SELECT count(*) FROM pre_history WHERE tenant_id = 'bank-a' AND event_type = 'TASK_CANCELLED' "
                + "AND details->>'reason' = 'Check merged into credit assessment'")).isEqualTo(12);
        assertThat(count("SELECT count(*) FROM pre_history WHERE tenant_id = 'bank-a' AND event_type = 'JOB_CANCELLED' "
                + "AND details->>'reason' = 'Replaced by bureau score'")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM pre_task WHERE tenant_id = 'bank-a' AND node_id = 'legacy-manual-check' "
                + "AND status = 'CANCELLED'")).isEqualTo(12);
        assertThat(count("SELECT count(*) FROM pre_task WHERE tenant_id = 'bank-a' AND node_id = 'credit-assessment' "
                + "AND status = 'OPEN'")).isEqualTo(12);
        assertThat(count("SELECT count(*) FROM pre_timer WHERE tenant_id = 'bank-a' AND node_id = 'legacy-score' "
                + "AND status = 'CANCELLED'")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM pre_timer WHERE tenant_id = 'bank-a' AND node_id = 'bureau-score' "
                + "AND status = 'DUE'")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM pre_history WHERE tenant_id = 'bank-a' AND event_type = 'MIGRATED'"))
                .isEqualTo(14);
        // and the instance with a call in flight is left on v1, listed as skipped
        assertThat(count("SELECT definition_ver FROM pre_instance WHERE tenant_id = 'bank-a' AND id = ?", inFlight))
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT code FROM pre_migration_instance WHERE tenant_id = 'bank-a' AND instance_id = ?")
                .param(inFlight).query(String.class).single()).isEqualTo("STEP_IN_FLIGHT");

        // When its call has ended and a later plan for the same versions runs
        jdbc.sql("UPDATE pre_timer SET status = 'DUE', locked_by = NULL, locked_until = NULL WHERE tenant_id = 'bank-a' "
                + "AND instance_id = ?").param(inFlight).update();
        execute("bank-a", removalPlan("later-run"));

        // Then it is migrated once its job is no longer in flight
        assertThat(count("SELECT definition_ver FROM pre_instance WHERE tenant_id = 'bank-a' AND id = ?", inFlight))
                .isEqualTo(2);
    }

    @Test
    void PRE_209_a_plan_that_names_a_step_twice_or_cancels_without_a_reason_is_refused_and_an_unhandled_step_is_listed() {
        // Given v1 with a removed step legacy-manual-check that no instruction handles, and v2
        importAs("bank-a", REMOVE_V1);
        startLoans("bank-a", 5, 0);
        importAs("bank-a", REMOVE_V2);
        MigrationPlan unhandled = plan("bank-a", List.of(), List.of(), null);
        long plansBefore = count("SELECT count(*) FROM pre_migration");

        // When each plan is validated
        MigrationDryRun dry = plans.validate(unhandled.id());

        // Then the first is invalid with the step and its instance count listed
        assertThat(dry.valid()).isFalse();
        assertThat(dry.conflicts()).singleElement().satisfies(c -> {
            assertThat(c.code()).isEqualTo("STEP_NOT_IN_TARGET");
            assertThat(c.nodeId()).isEqualTo("legacy-manual-check");
            assertThat(c.affected().count()).isEqualTo(5);
        });
        // and a step both mapped and cancelled, and a cancellation without reason or moveTo, are 422
        as("bank-a", "thu", MANAGE, "processes:read");
        assertThatThrownBy(() -> plans.plan(new MigrationPlanRequest(KEY, 1, 2, null,
                List.of(new MigrationStepMapping("legacy-manual-check", "credit-assessment")),
                List.of(new MigrationStepCancellation("legacy-manual-check", "credit-assessment", "merged")))))
                .isInstanceOfSatisfying(MigrationPlanInvalidException.class,
                        e -> assertThat(e.getMessage()).contains("legacy-manual-check is handled twice"));
        assertThatThrownBy(() -> plans.plan(new MigrationPlanRequest(KEY, 1, 2, null, List.of(),
                List.of(new MigrationStepCancellation("legacy-manual-check", "credit-assessment", " ")))))
                .isInstanceOf(MigrationPlanInvalidException.class).hasMessageContaining("needs a reason");
        assertThatThrownBy(() -> plans.plan(new MigrationPlanRequest(KEY, 1, 2, null, List.of(),
                List.of(new MigrationStepCancellation("legacy-manual-check", "", "merged")))))
                .isInstanceOf(MigrationPlanInvalidException.class).hasMessageContaining("moveTo");
        assertThatThrownBy(() -> plans.plan(new MigrationPlanRequest(KEY, 1, 2, null, List.of(),
                List.of(new MigrationStepCancellation("legacy-manual-check", "ghost", "merged")))))
                .isInstanceOf(MigrationPlanInvalidException.class).hasMessageContaining("ghost");
        assertThat(count("SELECT count(*) FROM pre_migration")).isEqualTo(plansBefore);
        assertThat(count("SELECT count(*) FROM pre_instance WHERE definition_ver = 1")).isEqualTo(5);
    }

    @Test
    void PRE_210_migration_commands_with_instructions_are_permissioned_idempotent_locked_and_tenant_bound() {
        // Given plan M1 of bank-a with a mapping, proposed by thu
        importAs("bank-a", RENAME_V1);
        startLoans("bank-a", 0, 3);
        importAs("bank-a", RENAME_V2);
        List<MigrationStepMapping> mapping = List.of(new MigrationStepMapping("credit-review", "credit-assessment"));
        MigrationPlan m1 = plan("bank-a", mapping, List.of(), "plan-key");
        as("bank-a", "thu", MANAGE, "processes:read");
        MigrationExecution proposal = executions.propose(m1.id(), "Rename the credit step, change CHG-2091", "propose-1");

        // When the same key repeats, a user without the permission plans, and a bank-b user reads and approves
        MigrationPlan again = plan("bank-a", mapping, List.of(), "plan-key");
        as("bank-a", "viewer", "processes:read");
        assertThatThrownBy(() -> plans.plan(new MigrationPlanRequest(KEY, 1, 2, null, mapping, List.of())))
                .isInstanceOf(PermissionDeniedException.class);
        as("bank-b", "omar", MANAGE, "processes:read");

        // Then the replay returns the first plan with its instructions and writes no second plan
        assertThat(again.id()).isEqualTo(m1.id());
        assertThat(again.mappings()).isEqualTo(mapping);
        assertThat(count("SELECT count(*) FROM pre_migration WHERE tenant_id = 'bank-a'")).isEqualTo(1);
        // and bank-b gets MIGRATION_NOT_FOUND and nothing is written
        assertThatThrownBy(() -> plans.find(m1.id())).isInstanceOf(MigrationNotFoundException.class);
        assertThatThrownBy(() -> executions.approve(m1.id(), proposal.lockVersion(), null))
                .isInstanceOf(MigrationNotFoundException.class);
        assertThat(count("SELECT count(*) FROM pre_instance WHERE definition_ver = 2")).isZero();
        // and a stale lock version is a conflict, with no instance touched
        as("bank-a", "lan", MANAGE, "processes:read");
        assertThatThrownBy(() -> executions.approve(m1.id(), proposal.lockVersion() - 1, null))
                .isInstanceOf(VersionConflictException.class);
        assertThat(count("SELECT count(*) FROM pre_instance WHERE definition_ver = 2")).isZero();
        // and the history of the plan names the steps and who planned it
        assertThat(jdbc.sql("SELECT actor_id || ':' || (details->'mappings'->>0) FROM pre_migration_history "
                + "WHERE migration_id = ? AND action = 'PLANNED'").param(m1.id()).query(String.class).single())
                .isEqualTo("thu:credit-review -> credit-assessment");
    }
}
