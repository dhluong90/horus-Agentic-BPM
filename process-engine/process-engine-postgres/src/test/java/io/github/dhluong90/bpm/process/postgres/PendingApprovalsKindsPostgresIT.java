package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.CalendarChange;
import io.github.dhluong90.bpm.process.api.ContentChangedException;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.MigrationExecution;
import io.github.dhluong90.bpm.process.api.MigrationPlan;
import io.github.dhluong90.bpm.process.api.MigrationPlanRequest;
import io.github.dhluong90.bpm.process.api.MigrationStepMapping;
import io.github.dhluong90.bpm.process.api.Page;
import io.github.dhluong90.bpm.process.api.PageRequest;
import io.github.dhluong90.bpm.process.api.PendingApproval;
import io.github.dhluong90.bpm.process.api.SelfApprovalForbiddenException;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.internal.service.MigrationExecutions;
import io.github.dhluong90.bpm.process.internal.service.MigrationSettings;
import io.github.dhluong90.bpm.process.internal.service.Migrations;
import io.github.dhluong90.bpm.process.internal.service.PendingApprovalSource;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PRE-190 with migration plans and calendar changes (#473, PD-139) on PostgreSQL. At bank-a thu plans the move of
 * loan-origination v1 to v2 (the step credit-review becomes credit-assessment) and proposes its execution at 02:00 on
 * 1 March 2027; at 03:00 she also proposes the holiday 1 January 2028 for vn-banking-hours. lan holds both manage
 * permissions, dan only reads, and bank-b checks tenant isolation.
 */
class PendingApprovalsKindsPostgresIT {

    private static final String MIGRATE = "processes:migration:manage";
    private static final String CALENDAR = "processes:calendar:manage";
    private static final MigrationSettings SETTINGS = new MigrationSettings(MigrationPlan.Approval.FOUR_EYES, 100,
            Duration.ofHours(1), 100, Duration.ofMinutes(2));
    private static final String V1 = """
            <bpmn:startEvent id="start" name="Application received"/>
            <bpmn:userTask id="credit-review" name="Credit review" horus:assignee="role:CREDIT_OFFICER" horus:resultVariable="review"/>
            <bpmn:endEvent id="end" name="Decided"/>
            <bpmn:sequenceFlow id="a" sourceRef="start" targetRef="credit-review"/>
            <bpmn:sequenceFlow id="b" sourceRef="credit-review" targetRef="end"/>
            """;

    private final TestClock clock = TestClock.at("2027-03-01T02:00:00Z");
    private TestEngine engine;
    private Migrations plans;
    private MigrationExecutions executions;
    private MigrationExecution proposal;
    private MigrationPlan plan;
    private CalendarChange change;

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

    @BeforeEach
    void setUp() {
        TestEngine.reset();
        engine = TestEngine.replica("pod-1", clock).build();
        plans = engine.migrations(SETTINGS);
        executions = engine.migrationExecutions(SETTINGS);
        engine.identity.as("bank-a", "pipeline", "processes:deploy", "processes:read");
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML, bpmn(V1), null));
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                bpmn(V1.replace("credit-review", "credit-assessment")), null));
        engine.identity.as("bank-a", "thu", MIGRATE, CALENDAR, "processes:read");
        plan = plans.plan(new MigrationPlanRequest("loan-origination", 1, 2, null,
                List.of(new MigrationStepMapping("credit-review", "credit-assessment")), List.of()));
        proposal = executions.propose(plan.id(), "Rename the credit step, change CHG-2090", null);
        clock.advance(Duration.ofHours(1));
        change = engine.calendars.propose("vn-banking-hours", BusinessCalendarPostgresIT.vnBankingHours("2028-01-01"),
                0, "New Year 2028 is a holiday", null);
    }

    private Page<PendingApproval> list(PageRequest page, String tenant, String user, String... permissions) {
        engine.identity.as(tenant, user, permissions);
        List<PendingApprovalSource> sources = List.of(engine.calendars, executions);
        return engine.services.pendingApprovals(page, sources);
    }

    @Test
    void PRE_254_a_pending_migration_plan_and_calendar_change_are_listed_oldest_first_with_their_hash() {
        Page<PendingApproval> page = list(PageRequest.first(), "bank-a", "lan", MIGRATE, CALENDAR, "processes:read");

        assertThat(page.items()).extracting(PendingApproval::kind).containsExactly("MIGRATION_PLAN", "CALENDAR_CHANGE");
        PendingApproval migration = page.items().get(0);
        assertThat(migration.id()).isEqualTo(plan.id());
        assertThat(migration.key()).isEqualTo("loan-origination");
        assertThat(migration.sourceVersion()).isEqualTo(1);
        assertThat(migration.version()).isEqualTo(2);
        assertThat(migration.contentHash()).isEqualTo(plan.planHash());
        assertThat(migration.lockVersion()).isEqualTo(proposal.lockVersion());
        assertThat(migration.reason()).isEqualTo("Rename the credit step, change CHG-2090");
        assertThat(migration.allowedActions()).containsExactly("approve", "reject");
        PendingApproval calendar = page.items().get(1);
        assertThat(calendar.id()).isEqualTo(change.id());
        assertThat(calendar.key()).isEqualTo("vn-banking-hours");
        assertThat(calendar.contentHash()).matches("[0-9a-f]{64}");
        assertThat(calendar.requestedBy()).isEqualTo("thu");
        assertThat(calendar.allowedActions()).containsExactly("approve", "reject");
    }

    @Test
    void PRE_254_the_proposer_may_not_approve_and_a_reader_sees_calendar_changes_only_with_permission_required() {
        List<PendingApproval> own = list(PageRequest.first(), "bank-a", "thu", MIGRATE, CALENDAR, "processes:read")
                .items();
        assertThat(own.get(0).allowedActions()).isEmpty();
        assertThat(own.get(0).deniedActions()).containsEntry("approve", "SELF_APPROVAL_FORBIDDEN")
                .containsEntry("reject", "SELF_APPROVAL_FORBIDDEN");
        // the proposer of a calendar change may withdraw it with reject, not approve it
        assertThat(own.get(1).allowedActions()).containsExactly("reject");
        assertThat(own.get(1).deniedActions()).containsEntry("approve", "SELF_APPROVAL_FORBIDDEN");

        List<PendingApproval> reader = list(PageRequest.first(), "bank-a", "dan", "processes:read").items();
        assertThat(reader).extracting(PendingApproval::kind).containsExactly("CALENDAR_CHANGE");
        assertThat(reader.get(0).deniedActions()).containsEntry("approve", "PERMISSION_REQUIRED");

        assertThat(list(PageRequest.first(), "bank-b", "eve", MIGRATE, CALENDAR, "processes:read").items()).isEmpty();
    }

    @Test
    void PRE_254_the_cursor_continues_across_kinds() {
        Page<PendingApproval> first = list(new PageRequest(1, null), "bank-a", "lan", MIGRATE, CALENDAR,
                "processes:read");
        assertThat(first.items()).extracting(PendingApproval::kind).containsExactly("MIGRATION_PLAN");
        assertThat(first.nextCursor()).isNotNull();
        Page<PendingApproval> second = list(new PageRequest(1, first.nextCursor()), "bank-a", "lan", MIGRATE,
                CALENDAR, "processes:read");
        assertThat(second.items()).extracting(PendingApproval::kind).containsExactly("CALENDAR_CHANGE");
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    void PRE_254_an_approval_names_the_reviewed_hash_and_another_hash_writes_nothing() {
        String stale = "0".repeat(64);
        engine.identity.as("bank-a", "lan", MIGRATE, CALENDAR, "processes:read");

        assertThatThrownBy(() -> executions.approve(plan.id(), proposal.lockVersion(), null, stale))
                .isInstanceOf(ContentChangedException.class);
        assertThatThrownBy(() -> engine.calendars.approve(change.id(), null, null, stale))
                .isInstanceOf(ContentChangedException.class);
        assertThat(list(PageRequest.first(), "bank-a", "lan", MIGRATE, CALENDAR, "processes:read").items()).hasSize(2);

        // the hashes the list showed are accepted, and the approved proposals leave the list
        List<PendingApproval> items = list(PageRequest.first(), "bank-a", "lan", MIGRATE, CALENDAR, "processes:read")
                .items();
        engine.identity.as("bank-a", "lan", MIGRATE, CALENDAR, "processes:read");
        assertThat(executions.approve(plan.id(), proposal.lockVersion(), null, items.get(0).contentHash()).status())
                .isEqualTo("APPROVED");
        assertThat(engine.calendars.approve(change.id(), "Checked", null, items.get(1).contentHash()).status())
                .isEqualTo("APPROVED");
        assertThat(list(PageRequest.first(), "bank-a", "lan", MIGRATE, CALENDAR, "processes:read").items()).isEmpty();
    }

    @Test
    void PRE_254_the_proposer_cannot_approve_with_the_right_hash() {
        PendingApproval item = list(PageRequest.first(), "bank-a", "thu", MIGRATE, CALENDAR, "processes:read").items()
                .get(1);
        engine.identity.as("bank-a", "thu", MIGRATE, CALENDAR, "processes:read");
        assertThatThrownBy(() -> engine.calendars.approve(change.id(), null, null, item.contentHash()))
                .isInstanceOf(SelfApprovalForbiddenException.class);
        assertThatThrownBy(() -> executions.approve(plan.id(), proposal.lockVersion(), null, plan.planHash()))
                .isInstanceOf(SelfApprovalForbiddenException.class);
    }
}
