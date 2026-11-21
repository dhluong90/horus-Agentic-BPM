package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.SelfApprovalForbiddenException;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.api.TaskView;
import io.github.dhluong90.bpm.process.spi.ProcessEventPublisher;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.assertj.core.api.Assertions;

/**
 * PRE-178 to PRE-180 on PostgreSQL: the credit review {@code review-application} is for {@code role:credit-officer}
 * on the bank's calendar (Monday to Friday 08:00 to 17:00 Asia/Ho_Chi_Minh), due 8 working hours after creation, and
 * escalates to the branch supervisor at its deadline and to the regional manager 4 working hours later. Every test
 * drives the engine clock; none waits.
 */
class TaskEscalationPostgresIT {

    private static final String[] MANAGER = {"processes:calendar:manage", "processes:read"};
    private static final String[] OFFICER = {"processes:start", "processes:read", "processes:task:work"};
    private static final String EVENT = "processes.task.escalated.v1";

    /** Thursday 3 December 2026, 15:00 in Ho Chi Minh City. */
    private final TestClock clock = TestClock.at("2026-12-03T08:00:00Z");
    private TestEngine engine;

    @BeforeEach
    void setUp() {
        TestEngine.reset();
        engine = TestEngine.replica("pod-1", clock).build();
        for (String tenant : List.of("bank-a", "bank-b")) {
            engine.identity.as(tenant, "kim", MANAGER);
            var proposed = engine.calendars.propose("vn-banking-hours", BusinessCalendarPostgresIT.vnBankingHours(), 0,
                    "Bank calendar");
            engine.identity.as(tenant, "lan", MANAGER);
            engine.calendars.approve(proposed.id(), null);
            engine.identity.as(tenant, "pipeline", "processes:deploy", "processes:read");
            for (String file : List.of("loan-credit-escalation", "loan-credit-escalation-keep",
                    "loan-credit-escalation-maker")) {
                engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                        TestEngine.resource("/bpmn/" + file + ".bpmn"), null));
            }
        }
    }

    private static Instant ict(String dateTime) {
        return java.time.OffsetDateTime.parse(dateTime + "+07:00").toInstant();
    }

    /** minh of the tenant starts the process; returns its review task id. */
    private String start(String tenant, String process, String businessKey) {
        engine.identity.as(tenant, "minh", OFFICER);
        return engine.services.start(new StartRequest(process, businessKey, Map.of(), null)).tasks().get(0).id();
    }

    private Map<String, Object> row(String taskId) {
        return TestEngine.jdbc().sql("SELECT status, assignee_role, assignee_user, claimed_by, escalation_level, "
                + "next_escalation_at, lock_version FROM pre_task WHERE id = ?").param(taskId).query().singleRow();
    }

    private List<ProcessEventPublisher.Event> escalations() {
        return engine.events.stream().filter(e -> e.type().equals(EVENT)).toList();
    }

    private List<Map<String, Object>> history(String taskId, String type) {
        return TestEngine.jdbc().sql("SELECT actor_id, details::text AS details FROM pre_history WHERE event_type = ? "
                + "AND details->>'taskId' = ? ORDER BY occurred_at, id").params(type, taskId).query().listOfRows();
    }

    private void claim(String taskId, String claimant, String key) {
        engine.identity.as("bank-a", claimant, "processes:task:work", "processes:read");
        engine.identity.roles = java.util.Set.of("credit-officer");
        engine.services.claim(taskId, engine.services.task(taskId).lockVersion(), key);
    }

    @Test
    void PRE_178_an_overdue_task_moves_to_the_escalation_role_at_its_deadline_keeping_four_eyes() {
        // Given a review created Thu 3 Dec 15:00 ICT, due Fri 4 Dec 14:00 ICT: 2 hours on Thursday and 6 on Friday
        String task = start("bank-a", "loan-credit-escalation", "LN-2026-000501");
        Instant due = ict("2026-12-04T14:00:00");
        assertThat(((java.sql.Timestamp) row(task).get("next_escalation_at")).toInstant()).isEqualTo(due);

        // When the clock is one second short of the deadline, the sweep changes nothing
        clock.set(due.minusSeconds(1));
        assertThat(engine.services.escalateOverdue()).isZero();
        assertThat(row(task)).containsEntry("assignee_role", "credit-officer").containsEntry("escalation_level", 0);

        // And at 14:00 it moves the task to the supervisor, open, as the timer
        clock.set(due);
        assertThat(engine.services.escalateOverdue()).isEqualTo(1);
        assertThat(row(task)).containsEntry("status", "OPEN").containsEntry("assignee_role", "branch-supervisor")
                .containsEntry("escalation_level", 1);
        assertThat(history(task, "TASK_ESCALATED")).singleElement().satisfies(h -> {
            assertThat(h).containsEntry("actor_id", "system:timer");
            assertThat((String) h.get("details")).contains("\"level\": 1").contains("\"from\": \"role:credit-officer\"")
                    .contains("\"to\": \"role:branch-supervisor\"").contains("\"dueAt\": \"2026-12-04T07:00:00Z\"");
        });
        assertThat(escalations()).singleElement().satisfies(e -> {
            assertThat(e.tenantId()).isEqualTo("bank-a");
            assertThat(e.payload()).containsEntry("taskId", task).containsEntry("level", 1)
                    .containsEntry("from", "role:credit-officer").containsEntry("to", "role:branch-supervisor")
                    .containsKeys("instanceId", "nodeId");
        });

        // And the maker, minh, is a supervisor too but still cannot claim the task it started
        engine.identity.as("bank-a", "minh", "processes:task:work", "processes:read");
        engine.identity.roles = java.util.Set.of("branch-supervisor");
        Assertions.assertThatThrownBy(() -> engine.services.claim(task, engine.services.task(task).lockVersion(),
                "claim-minh")).isInstanceOf(SelfApprovalForbiddenException.class);
        // while another supervisor can
        engine.identity.as("bank-a", "ha", "processes:task:work", "processes:read");
        engine.identity.roles = java.util.Set.of("branch-supervisor");
        TaskView claimed = engine.services.claim(task, engine.services.task(task).lockVersion(), "claim-ha");
        assertThat(claimed.claimedBy()).isEqualTo("ha");
    }

    @Test
    void PRE_178_a_task_completed_before_its_deadline_is_never_escalated() {
        // Given a review claimed and completed by tt.binh at 13:59:59 on the due day
        String task = start("bank-a", "loan-credit-escalation", "LN-2026-000502");
        Instant due = ict("2026-12-04T14:00:00");
        clock.set(due.minusSeconds(1));
        claim(task, "tt.binh", "claim-1");
        engine.services.complete(task, Map.of("approved", true), engine.services.task(task).lockVersion(), "done-1");

        // When the clock reaches the deadline
        clock.set(due);

        // Then nothing is escalated
        assertThat(engine.services.escalateOverdue()).isZero();
        assertThat(escalations()).isEmpty();
        assertThat(row(task)).containsEntry("status", "COMPLETED").containsEntry("assignee_role", "credit-officer");
    }

    @Test
    void PRE_178_the_sweep_is_tenant_scoped_idempotent_and_applies_a_level_once_across_replicas() throws Exception {
        // Given overdue reviews in two banks, and a second replica on the same database
        String a = start("bank-a", "loan-credit-escalation", "LN-2026-000503");
        String b = start("bank-b", "loan-credit-escalation", "LN-2026-000503");
        TestEngine second = TestEngine.replica("pod-2", clock).build();
        clock.set(ict("2026-12-04T14:00:00"));

        // When both replicas sweep at the same moment
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> one = pool.submit(() -> {
                go.await();
                return engine.services.escalateOverdue();
            });
            Future<Integer> two = pool.submit(() -> {
                go.await();
                return second.services.escalateOverdue();
            });
            go.countDown();

            // Then each level is applied once in all: 2 tasks, 2 levels
            assertThat(one.get() + two.get()).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
        assertThat(engine.services.escalateOverdue() + second.services.escalateOverdue()).isZero();
        assertThat(history(a, "TASK_ESCALATED")).hasSize(1);
        assertThat(history(b, "TASK_ESCALATED")).hasSize(1);
        // And each event carries the tenant of its own task, never the other's
        List<ProcessEventPublisher.Event> all = new java.util.ArrayList<>(escalations());
        all.addAll(second.events.stream().filter(e -> e.type().equals(EVENT)).toList());
        assertThat(all).extracting(ProcessEventPublisher.Event::tenantId).containsExactlyInAnyOrder("bank-a", "bank-b");
        assertThat(all).filteredOn(e -> e.tenantId().equals("bank-a")).singleElement()
                .satisfies(e -> assertThat(e.payload()).containsEntry("taskId", a));
    }

    @Test
    void PRE_179_a_second_level_fires_on_business_time_and_a_claimed_task_is_released() {
        // Given a review claimed by tt.binh at 10:00 on Friday, level 1 due Fri 14:00 and level 2 Mon 7 Dec 09:00
        String task = start("bank-a", "loan-credit-escalation", "LN-2026-000601");
        clock.set(ict("2026-12-04T10:00:00"));
        claim(task, "tt.binh", "claim-1");
        assertThat(row(task)).containsEntry("status", "CLAIMED");

        // When level 1 fires (default onClaimed: release)
        clock.set(ict("2026-12-04T14:00:00"));
        assertThat(engine.services.escalateOverdue()).isEqualTo(1);

        // Then the claim is released and the event names the released claimant
        assertThat(row(task)).containsEntry("status", "OPEN").containsEntry("assignee_role", "branch-supervisor")
                .containsEntry("claimed_by", null);
        assertThat(escalations().get(0).payload()).containsEntry("releasedClaimant", "tt.binh");
        // And level 2 is Monday 09:00, not Saturday 02:00
        assertThat(((java.sql.Timestamp) row(task).get("next_escalation_at")).toInstant())
                .isEqualTo(ict("2026-12-07T09:00:00"));
        clock.set(ict("2026-12-05T02:00:00"));
        assertThat(engine.services.escalateOverdue()).isZero();

        // And a calendar change made after the deadline was computed does not move it
        engine.identity.as("bank-a", "kim", MANAGER);
        var change = engine.calendars.propose("vn-banking-hours",
                BusinessCalendarPostgresIT.vnBankingHours("2026-12-07"), 1, "Extra day off");
        engine.identity.as("bank-a", "lan", MANAGER);
        engine.calendars.approve(change.id(), null);
        clock.set(ict("2026-12-07T09:00:00"));
        assertThat(engine.services.escalateOverdue()).isEqualTo(1);
        assertThat(row(task)).containsEntry("assignee_role", "regional-manager").containsEntry("escalation_level", 2)
                .containsEntry("next_escalation_at", null);
        assertThat(escalations()).extracting(e -> e.payload().get("to")).containsExactly("role:branch-supervisor",
                "role:regional-manager");
        assertThat(escalations().get(1).payload()).containsEntry("level", 2);
    }

    @Test
    void PRE_179_a_claimant_who_keeps_the_claim_still_completes_the_task() {
        // Given a review declared onClaimed keep, claimed by tt.binh at 10:00 on Friday
        String task = start("bank-a", "loan-credit-escalation-keep", "LN-2026-000602");
        clock.set(ict("2026-12-04T10:00:00"));
        claim(task, "tt.binh", "claim-1");

        // When level 1 fires at 14:00
        clock.set(ict("2026-12-04T14:00:00"));
        engine.services.escalateOverdue();

        // Then tt.binh keeps the claim while the role is the supervisor's, and still completes it
        assertThat(row(task)).containsEntry("status", "CLAIMED").containsEntry("claimed_by", "tt.binh")
                .containsEntry("assignee_role", "branch-supervisor");
        assertThat(escalations().get(0).payload()).doesNotContainKey("releasedClaimant");
        engine.identity.as("bank-a", "tt.binh", "processes:task:work", "processes:read");
        engine.services.complete(task, Map.of("approved", true), engine.services.task(task).lockVersion(), "done-1");
        assertThat(row(task)).containsEntry("status", "COMPLETED");
    }

    @Test
    void PRE_180_a_level_that_targets_the_maker_is_recorded_with_an_incident_and_changes_nothing() {
        // Given a four-eyes review started by minh whose escalation names minh
        String task = start("bank-a", "loan-credit-escalation-maker", "LN-2026-000701");

        // When the deadline passes
        clock.set(ict("2026-12-04T14:00:00"));
        assertThat(engine.services.escalateOverdue()).isEqualTo(1);

        // Then the assignment is unchanged, the refusal is in history with its code, and an incident is open
        assertThat(row(task)).containsEntry("assignee_role", "credit-officer").containsEntry("assignee_user", null)
                .containsEntry("escalation_level", 1);
        assertThat(history(task, "ESCALATION_REFUSED")).singleElement().satisfies(h -> assertThat((String) h.get("details"))
                .contains("\"code\": \"ESCALATION_TARGET_FORBIDDEN\"").contains("\"to\": \"user:minh\""));
        assertThat(TestEngine.jdbc().sql("SELECT error_code FROM pre_incident WHERE tenant_id = 'bank-a' "
                + "AND status = 'OPEN'").query(String.class).list()).containsExactly("ESCALATION_TARGET_FORBIDDEN");
        assertThat(escalations()).isEmpty();
        // And the sweep does not try it again
        assertThat(engine.services.escalateOverdue()).isZero();
        assertThat(history(task, "ESCALATION_REFUSED")).hasSize(1);
    }

    @Test
    void PRE_180_a_cancelled_instance_is_not_escalated_and_another_tenants_task_is_never_touched() {
        // Given two overdue reviews, one of them cancelled before its deadline
        String cancelled = start("bank-a", "loan-credit-escalation", "LN-2026-000801");
        String instance = TestEngine.jdbc().sql("SELECT instance_id FROM pre_task WHERE id = ?").param(cancelled)
                .query(String.class).single();
        engine.identity.as("bank-a", "ops", "processes:cancel", "processes:read");
        long lock = TestEngine.jdbc().sql("SELECT lock_version FROM pre_instance WHERE id = ?").param(instance)
                .query(Long.class).single();
        engine.services.cancel(instance, lock, "Customer withdrew the application", "cancel-1");
        String open = start("bank-b", "loan-credit-escalation", "LN-2026-000801");
        clock.set(ict("2026-12-04T14:00:00"));

        // When the sweep runs
        assertThat(engine.services.escalateOverdue()).isEqualTo(1);

        // Then only the open one moved, in its own tenant
        assertThat(row(cancelled)).containsEntry("status", "CANCELLED").containsEntry("assignee_role", "credit-officer");
        assertThat(row(open)).containsEntry("assignee_role", "branch-supervisor");
        assertThat(escalations()).singleElement().satisfies(e -> assertThat(e.tenantId()).isEqualTo("bank-b"));
    }
}
