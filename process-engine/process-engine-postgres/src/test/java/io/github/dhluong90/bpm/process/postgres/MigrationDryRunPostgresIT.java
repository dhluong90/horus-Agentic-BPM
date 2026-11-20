package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.process.api.DefinitionNotFoundException;
import io.github.dhluong90.bpm.process.api.IdempotencyKeyReuseException;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.InputInvalidException;
import io.github.dhluong90.bpm.process.api.InvalidStateException;
import io.github.dhluong90.bpm.process.api.MigrationDryRun;
import io.github.dhluong90.bpm.process.api.MigrationNotFoundException;
import io.github.dhluong90.bpm.process.api.MigrationPlan;
import io.github.dhluong90.bpm.process.api.MigrationPlanRequest;
import io.github.dhluong90.bpm.process.api.PermissionDeniedException;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.internal.service.MigrationSettings;
import io.github.dhluong90.bpm.process.internal.service.Migrations;
import io.github.dhluong90.bpm.process.spi.ProcessEventPublisher;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Migration plans and their dry run on PostgreSQL (#74, PRE-014, PRE-174, PRE-175). Loan review v1 sends a loan of
 * 5 billion VND or more to {@code legacy-review} and every other loan to {@code review}; v2 removes
 * {@code legacy-review}; v3 keeps its id as a waiting timer. A dry run only reads: every engine table holds the same
 * rows before and after it.
 */
class MigrationDryRunPostgresIT {

    private static final String KEY = "loan-review";
    private static final String[] PIPELINE = {"processes:deploy", "processes:start", "processes:read"};
    private static final String MANAGE = "processes:migration:manage";
    /** Every table a dry run could write, and the plan tables. */
    private static final List<String> TABLES = List.of("pre_definition", "pre_definition_history", "pre_instance",
            "pre_token", "pre_task", "pre_timer", "pre_history", "pre_idempotency", "pre_incident", "pre_migration",
            "pre_migration_history");

    private static final String V1 = """
            <bpmn:startEvent id="start" name="Loan received"/>
            <bpmn:exclusiveGateway id="size-check" name="Large loan?" default="size-check__review"/>
            <bpmn:userTask id="legacy-review" name="Legacy committee review" horus:assignee="role:CREDIT_COMMITTEE" horus:resultVariable="committee"/>
            <bpmn:userTask id="review" name="Officer review" horus:assignee="role:LOAN_OFFICER" horus:resultVariable="review"/>
            <bpmn:endEvent id="end" name="Decided"/>
            <bpmn:sequenceFlow id="start__size" sourceRef="start" targetRef="size-check"/>
            <bpmn:sequenceFlow id="size-check__legacy" sourceRef="size-check" targetRef="legacy-review">
              <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">amount &gt;= 5000000000 VND</bpmn:conditionExpression>
            </bpmn:sequenceFlow>
            <bpmn:sequenceFlow id="size-check__review" sourceRef="size-check" targetRef="review"/>
            <bpmn:sequenceFlow id="legacy__end" sourceRef="legacy-review" targetRef="end"/>
            <bpmn:sequenceFlow id="review__end" sourceRef="review" targetRef="end"/>
            """;
    /** v2: the legacy committee review is gone; every loan goes to the officer. */
    private static final String V2 = """
            <bpmn:startEvent id="start" name="Loan received"/>
            <bpmn:userTask id="review" name="Officer review" horus:assignee="role:LOAN_OFFICER" horus:resultVariable="review"/>
            <bpmn:endEvent id="end" name="Decided"/>
            <bpmn:sequenceFlow id="start__review" sourceRef="start" targetRef="review"/>
            <bpmn:sequenceFlow id="review__end" sourceRef="review" targetRef="end"/>
            """;
    /** v3: {@code legacy-review} keeps its id but is now a one-day wait. */
    private static final String V3 = V1.replace("""
            <bpmn:userTask id="legacy-review" name="Legacy committee review" horus:assignee="role:CREDIT_COMMITTEE" horus:resultVariable="committee"/>
            """, """
            <bpmn:intermediateCatchEvent id="legacy-review" name="Cooling-off day">
              <bpmn:timerEventDefinition id="legacy-review-timer">
                <bpmn:timeDuration xsi:type="bpmn:tFormalExpression">P1D</bpmn:timeDuration>
              </bpmn:timerEventDefinition>
            </bpmn:intermediateCatchEvent>
            """);

    private final TestClock clock = TestClock.at("2027-03-01T02:00:00Z");
    private TestEngine engine;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        TestEngine.reset();
        engine = TestEngine.replica("pod-1", clock).build();
        jdbc = TestEngine.jdbc();
    }

    static byte[] bpmn(String process) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                  xmlns:horus="urn:horus-bpm:bpmn:extensions:v1"
                                  id="Definitions_loan-review" targetNamespace="urn:horus-bpm:examples"
                                  expressionLanguage="urn:horus-bpm:expr:v1">
                  <bpmn:process id="loan-review" name="Loan review" isExecutable="true">
                %s
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(process).getBytes(StandardCharsets.UTF_8);
    }

    private void importAs(String tenant, String process) {
        engine.identity.as(tenant, "pipeline", PIPELINE);
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML, bpmn(process), null));
    }

    /** Starts loans on the effective version: {@code large} of 6 billion VND and {@code small} of 300 million. */
    private void startLoans(String tenant, int large, int small) {
        engine.identity.as(tenant, "officer-lan", PIPELINE);
        for (int i = 0; i < large + small; i++) {
            long amount = i < large ? 6_000_000_000L : 300_000_000L;
            engine.services.start(new StartRequest(KEY, "LN-2027-" + String.format("%06d", i),
                    Map.of("amount", Money.ofMinor("VND", amount)), null));
        }
    }

    private Migrations migrations() {
        return engine.migrations(MigrationSettings.DEFAULTS);
    }

    private MigrationPlan plan(Migrations migrations, String tenant, int from, int to, String idempotencyKey) {
        engine.identity.as(tenant, "ops-lead-thu", MANAGE, "processes:read");
        return migrations.plan(new MigrationPlanRequest(KEY, from, to, idempotencyKey));
    }

    /** One fingerprint per table over every column of every row. */
    private Map<String, String> snapshot() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String table : TABLES) {
            out.put(table, jdbc.sql("SELECT count(*) || ':' || coalesce(md5(string_agg(t::text, '|' ORDER BY t::text)), '') "
                    + "FROM " + table + " t").query(String.class).single());
        }
        return out;
    }

    @Test
    void PRE_014_migration_validate_reports_unmapped_steps_without_mutation() {
        // Given 500 loans running on v1, 50 of them paused on legacy-review, which v2 removes
        importAs("bank-a", V1);
        startLoans("bank-a", 50, 450);
        importAs("bank-a", V2);
        Migrations migrations = migrations();
        MigrationPlan plan = plan(migrations, "bank-a", 1, 2, null);
        List<String> legacyLoans = jdbc.sql("SELECT instance_id FROM pre_token WHERE tenant_id = 'bank-a' "
                + "AND current_node_id = 'legacy-review' AND status = 'WAITING_USER' ORDER BY instance_id")
                .query(String.class).list();
        int events = engine.events.size();
        Map<String, String> before = snapshot();

        // When the dry run runs without a mapping for legacy-review
        MigrationDryRun report = migrations.validate(plan.id());

        // Then 50 loans are listed at legacy-review, the plan is invalid, and no row of any table changed
        assertThat(report.valid()).isFalse();
        assertThat(report.instances()).isEqualTo(new MigrationDryRun.Instances(500, 450, 50));
        assertThat(report.conflicts()).singleElement().satisfies(c -> {
            assertThat(c.code()).isEqualTo(MigrationDryRun.STEP_NOT_IN_TARGET);
            assertThat(c.nodeId()).isEqualTo("legacy-review");
            assertThat(c.name()).isEqualTo("Legacy committee review");
            assertThat(c.affected().count()).isEqualTo(50);
            assertThat(c.affected().instanceIds()).hasSize(50).isEqualTo(legacyLoans);
            assertThat(c.affected().truncated()).isFalse();
        });
        assertThat(report.nodes()).extracting(MigrationDryRun.NodeMapping::nodeId, MigrationDryRun.NodeMapping::liveTokens,
                MigrationDryRun.NodeMapping::mapping, MigrationDryRun.NodeMapping::targetNodeId)
                .containsExactly(tuple("legacy-review", 50L, "STEP_NOT_IN_TARGET", null),
                        tuple("review", 450L, "SAME_ID", "review"));
        assertThat(report.movable().count()).isEqualTo(450);
        assertThat(report.movable().instanceIds()).hasSize(100).isSorted().doesNotContainAnyElementsOf(legacyLoans);
        assertThat(report.movable().truncated()).isTrue();
        assertThat(report.planHash()).isEqualTo(plan.planHash());
        assertThat(report.checkedAt()).isEqualTo(clock.instant());
        assertThat(report.validUntil()).isEqualTo(clock.instant().plus(Duration.ofHours(1)));
        assertThat(snapshot()).as("0 rows of pre_instance, or of any other table, are modified").isEqualTo(before);
        assertThat(engine.events).hasSize(events);
        assertThat(jdbc.sql("SELECT count(*) FROM pre_instance WHERE tenant_id = 'bank-a' AND definition_ver = 1 "
                + "AND status = 'RUNNING'").query(Long.class).single()).isEqualTo(500);
    }

    @Test
    void PRE_014_a_step_kept_under_its_id_as_another_step_type_is_a_conflict() {
        importAs("bank-a", V1);
        startLoans("bank-a", 2, 3);
        importAs("bank-a", V3);
        Migrations migrations = migrations();

        MigrationDryRun report = migrations.validate(plan(migrations, "bank-a", 1, 2, null).id());

        assertThat(report.valid()).isFalse();
        assertThat(report.conflicts()).extracting(MigrationDryRun.Conflict::code, MigrationDryRun.Conflict::nodeId)
                .containsExactly(tuple(MigrationDryRun.STEP_TYPE_CHANGED, "legacy-review"));
        assertThat(report.nodes()).filteredOn(n -> n.nodeId().equals("legacy-review")).singleElement()
                .satisfies(n -> assertThat(n.type() + " -> " + n.targetType()).isEqualTo("userTask -> timer"));
        assertThat(report.instances()).isEqualTo(new MigrationDryRun.Instances(5, 3, 2));
    }

    @Test
    void PRE_014_a_plan_whose_steps_all_map_is_valid_and_lists_every_instance_that_would_move() {
        importAs("bank-a", V1);
        startLoans("bank-a", 2, 3);
        importAs("bank-a", V1.replace("Officer review", "Loan officer review"));
        Migrations migrations = migrations();

        MigrationDryRun report = migrations.validate(plan(migrations, "bank-a", 1, 2, null).id());

        assertThat(report.valid()).isTrue();
        assertThat(report.conflicts()).isEmpty();
        assertThat(report.instances()).isEqualTo(new MigrationDryRun.Instances(5, 5, 0));
        assertThat(report.movable().instanceIds()).hasSize(5).isSorted();
        assertThat(report.movable().truncated()).isFalse();
    }

    @Test
    void PRE_174_a_plan_records_four_eyes_with_its_history_row_and_event_and_moves_nothing() {
        importAs("bank-a", V1);
        startLoans("bank-a", 1, 1);
        importAs("bank-a", V2);
        Migrations migrations = migrations();
        Map<String, String> before = snapshot();

        MigrationPlan plan = plan(migrations, "bank-a", 1, 2, "plan-loan-review-1-2");

        assertThat(plan.approval()).isEqualTo(MigrationPlan.Approval.FOUR_EYES);
        assertThat(plan.approval().needsSecondPerson()).isTrue();
        assertThat(plan.status()).isEqualTo("PLANNED");
        assertThat(plan.createdBy()).isEqualTo("ops-lead-thu");
        assertThat(migrations.validate(plan.id()).approval()).isEqualTo(MigrationPlan.Approval.FOUR_EYES);
        Map<String, Object> row = jdbc.sql("SELECT approval, status, source_version, target_version FROM pre_migration "
                + "WHERE tenant_id = 'bank-a' AND id = ?").param(plan.id()).query().singleRow();
        assertThat(row).containsEntry("approval", "four-eyes").containsEntry("status", "PLANNED")
                .containsEntry("source_version", 1).containsEntry("target_version", 2);
        assertThat(jdbc.sql("SELECT action || ' ' || actor_id || ' ' || (details->>'approval') FROM pre_migration_history "
                + "WHERE tenant_id = 'bank-a' AND migration_id = ?").param(plan.id()).query(String.class).list())
                .containsExactly("PLANNED ops-lead-thu four-eyes");
        assertThat(engine.events).filteredOn(e -> e.type().equals("processes.migration.planned.v1")).singleElement()
                .satisfies(e -> assertThat(e.payload()).containsEntry("migrationId", plan.id())
                        .containsEntry("approval", "four-eyes").containsEntry("createdBy", "ops-lead-thu"));
        // Planning moves nothing: instances, tokens and history are unchanged.
        Map<String, String> after = snapshot();
        for (String table : List.of("pre_instance", "pre_token", "pre_task", "pre_history")) {
            assertThat(after.get(table)).as(table).isEqualTo(before.get(table));
        }

        // A repeat with the same key returns the first plan and writes nothing; another request with it is refused.
        Map<String, String> planned = snapshot();
        assertThat(plan(migrations, "bank-a", 1, 2, "plan-loan-review-1-2")).isEqualTo(plan);
        assertThat(snapshot()).isEqualTo(planned);
        assertThatThrownBy(() -> plan(migrations, "bank-a", 2, 1, "plan-loan-review-1-2"))
                .isInstanceOf(IdempotencyKeyReuseException.class);
    }

    @Test
    void PRE_174_a_single_person_plan_is_recorded_only_with_the_dev_setting() {
        importAs("bank-a", V1);
        importAs("bank-a", V2);
        Migrations single = engine.migrations(new MigrationSettings(MigrationPlan.Approval.SINGLE, 100,
                Duration.ofHours(1)));

        MigrationPlan plan = plan(single, "bank-a", 1, 2, null);

        assertThat(plan.approval()).isEqualTo(MigrationPlan.Approval.SINGLE);
        assertThat(plan.approval().needsSecondPerson()).isFalse();
        assertThat(jdbc.sql("SELECT approval FROM pre_migration WHERE id = ?").param(plan.id()).query(String.class)
                .single()).isEqualTo("single");
    }

    @Test
    void PRE_174_a_plan_needs_an_active_target_another_version_and_the_permission() {
        importAs("bank-a", V1);
        importAs("bank-a", V2);
        Migrations migrations = migrations();

        assertThatThrownBy(() -> plan(migrations, "bank-a", 2, 1, null)).as("v1 was retired by v2")
                .isInstanceOf(InvalidStateException.class).hasMessageContaining("active version");
        assertThatThrownBy(() -> plan(migrations, "bank-a", 2, 2, null)).isInstanceOf(InputInvalidException.class);
        assertThatThrownBy(() -> plan(migrations, "bank-a", 1, 9, null)).isInstanceOf(DefinitionNotFoundException.class);
        engine.identity.as("bank-a", "viewer-hoa", "processes:read");
        assertThatThrownBy(() -> migrations.plan(new MigrationPlanRequest(KEY, 1, 2, null)))
                .isInstanceOf(PermissionDeniedException.class).hasMessageContaining(MANAGE);
        assertThat(jdbc.sql("SELECT count(*) FROM pre_migration").query(Long.class).single()).isZero();
    }

    @Test
    void PRE_175_another_tenant_can_neither_plan_dry_run_nor_read_a_plan_and_nothing_is_written() {
        // Given bank-a's plan over running loans
        importAs("bank-a", V1);
        startLoans("bank-a", 2, 2);
        importAs("bank-a", V2);
        Migrations migrations = migrations();
        MigrationPlan plan = plan(migrations, "bank-a", 1, 2, null);
        int events = engine.events.size();
        Map<String, String> before = snapshot();

        // When a client of bank-b with every migration permission asks for it
        engine.identity.as("bank-b", "ops-lead-binh", MANAGE, "processes:read");

        // Then it is not found, and nothing is written
        assertThatThrownBy(() -> migrations.find(plan.id())).isInstanceOf(MigrationNotFoundException.class);
        assertThatThrownBy(() -> migrations.validate(plan.id())).isInstanceOf(MigrationNotFoundException.class);
        assertThatThrownBy(() -> migrations.plan(new MigrationPlanRequest(KEY, 1, 2, "bank-b-key")))
                .isInstanceOf(DefinitionNotFoundException.class);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(engine.events).hasSize(events);

        // bank-a reads its own plan with processes:read alone, and cannot dry-run it without the permission
        engine.identity.as("bank-a", "viewer-hoa", "processes:read");
        assertThat(migrations.find(plan.id())).isEqualTo(plan);
        assertThatThrownBy(() -> migrations.validate(plan.id())).isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void PRE_175_each_tenant_dry_runs_only_its_own_instances() {
        importAs("bank-a", V1);
        startLoans("bank-a", 3, 1);
        importAs("bank-a", V2);
        importAs("bank-b", V1);
        startLoans("bank-b", 1, 6);
        importAs("bank-b", V2);
        Migrations migrations = migrations();
        MigrationPlan a = plan(migrations, "bank-a", 1, 2, null);
        MigrationPlan b = plan(migrations, "bank-b", 1, 2, null);

        engine.identity.as("bank-a", "ops-lead-thu", MANAGE);
        MigrationDryRun reportA = migrations.validate(a.id());
        engine.identity.as("bank-b", "ops-lead-binh", MANAGE);
        MigrationDryRun reportB = migrations.validate(b.id());

        assertThat(reportA.instances()).isEqualTo(new MigrationDryRun.Instances(4, 1, 3));
        assertThat(reportB.instances()).isEqualTo(new MigrationDryRun.Instances(7, 6, 1));
        List<String> idsA = new ArrayList<>(reportA.conflicts().get(0).affected().instanceIds());
        idsA.addAll(reportA.movable().instanceIds());
        assertThat(jdbc.sql("SELECT DISTINCT tenant_id FROM pre_instance WHERE id = ANY (?::text[])")
                .param(idsA.toArray(String[]::new)).query(String.class).list()).containsExactly("bank-a");
        assertThat(engine.events).extracting(ProcessEventPublisher.Event::tenantId)
                .contains("bank-a", "bank-b");
    }
}
