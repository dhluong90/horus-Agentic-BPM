package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.CalendarChange;
import io.github.dhluong90.bpm.process.api.CalendarNotFoundException;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SelfApprovalForbiddenException;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.api.StepFailedException;
import io.github.dhluong90.bpm.process.api.VersionConflictException;
import io.github.dhluong90.bpm.process.api.WorkingCalendar;
import io.github.dhluong90.bpm.process.internal.service.Calendars;
import io.github.dhluong90.bpm.process.spi.ProcessEventPublisher;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * PRE-008 and PRE-114 on PostgreSQL: the bank's calendar {@code vn-banking-hours} (Monday to Friday 08:00 to 17:00
 * Asia/Ho_Chi_Minh, New Year's Day off) is created and changed under four eyes, a credit review with
 * {@code horus:sla="PT4H"} gets its due time from it once, and a later approved change does not move that due time.
 */
class BusinessCalendarPostgresIT {

    private static final String[] MANAGER = {"processes:calendar:manage", "processes:read"};
    private static final String[] OFFICER = {"processes:start", "processes:read", "processes:task:work"};

    private final TestClock clock = TestClock.at("2026-12-31T08:00:00Z");
    private TestEngine engine;

    @BeforeEach
    void setUp() {
        TestEngine.reset();
        engine = TestEngine.replica("pod-1", clock).build();
        for (String tenant : List.of("bank-a", "bank-b")) {
            engine.identity.as(tenant, "pipeline", "processes:deploy", "processes:read");
            engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                    TestEngine.resource("/bpmn/loan-credit-review-sla.bpmn"), null));
        }
    }

    static WorkingCalendar vnBankingHours(String... holidays) {
        Map<DayOfWeek, List<WorkingCalendar.Hours>> week = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY)) {
            week.put(day, List.of(WorkingCalendar.Hours.parse("08:00-17:00")));
        }
        TreeMap<LocalDate, String> dates = new TreeMap<>();
        for (String h : holidays) {
            dates.put(LocalDate.parse(h), "Public holiday");
        }
        return new WorkingCalendar(ZoneId.of("Asia/Ho_Chi_Minh"), week, dates);
    }

    private static Instant at(String offsetTime) {
        return OffsetDateTime.parse(offsetTime).toInstant();
    }

    private void as(String tenant, String caller, String... permissions) {
        engine.identity.as(tenant, caller, permissions);
    }

    /** Kim proposes the calendar for bank-a, Lan approves it: version 1. */
    private void calendarInForce() {
        as("bank-a", "kim", MANAGER);
        CalendarChange proposed = engine.calendars.propose("vn-banking-hours", vnBankingHours("2027-01-01"), 0,
                "Bank calendar 2027");
        as("bank-a", "lan", MANAGER);
        engine.calendars.approve(proposed.id(), "Checked against the 2027 holiday notice");
    }

    private Instant startReview(String businessKey) {
        return engine.services.task(startReviewTask(businessKey)).dueAt();
    }

    /** Starts a credit review for minh of bank-a; returns its task id. */
    private String startReviewTask(String businessKey) {
        as("bank-a", "minh", OFFICER);
        ProcessInstance started = engine.services.start(new StartRequest("loan-credit-review", businessKey,
                Map.of("amount", Map.of("amount", "250000000", "currency", "VND")), null));
        return started.tasks().get(0).id();
    }

    private static int count(String sql, Object... params) {
        return TestEngine.jdbc().sql(sql).params(params).query(Integer.class).single();
    }

    @Test
    void PRE_008_business_calendar_skips_holidays_and_weekends() {
        // Given vn-banking-hours approved under four eyes, and the engine clock at 31 Dec 2026 15:00 +07:00
        calendarInForce();

        // When a credit review with a four-hour SLA is created
        Instant due = startReview("LN-2027-000123");

        // Then two hours count on 31 Dec, 1 Jan and the weekend are skipped, and the task is due Monday 10:00
        assertThat(due).isEqualTo(at("2027-01-04T10:00:00+07:00"));
        assertThat(TestEngine.jdbc().sql("SELECT due_at FROM pre_task WHERE tenant_id = 'bank-a'")
                .query(java.sql.Timestamp.class).single().toInstant()).isEqualTo(due);
    }

    @Test
    void PRE_008_a_later_calendar_change_does_not_move_an_existing_due_time() {
        // Given a review due Monday 4 Jan 10:00 +07:00
        calendarInForce();
        String firstTask = startReviewTask("LN-2027-000123");
        Instant first = engine.services.task(firstTask).dueAt();

        // When Kim proposes 4 Jan as an extra holiday, cannot approve it herself, and Lan approves it
        as("bank-a", "kim", MANAGER);
        CalendarChange change = engine.calendars.propose("vn-banking-hours",
                vnBankingHours("2027-01-01", "2027-01-04"), 1, "Extra Tet bridge day announced");
        int changesBefore = count("SELECT count(*) FROM pre_calendar_change WHERE status = 'APPROVED'");
        assertThatThrownBy(() -> engine.calendars.approve(change.id(), null))
                .isInstanceOf(SelfApprovalForbiddenException.class);
        assertThat(count("SELECT count(*) FROM pre_calendar_change WHERE status = 'APPROVED'")).isEqualTo(changesBefore);
        assertThat(engine.calendars.calendar("vn-banking-hours").version()).isEqualTo(1);
        as("bank-a", "lan", MANAGER);
        CalendarChange approved = engine.calendars.approve(change.id(), "Confirmed with operations");
        Instant second = startReview("LN-2027-000124");

        // Then the first task keeps its due time, and only the new task skips 4 Jan
        assertThat(engine.services.task(firstTask).dueAt()).isEqualTo(first);
        assertThat(TestEngine.jdbc().sql("SELECT due_at FROM pre_task WHERE id = ?").param(firstTask)
                .query(java.sql.Timestamp.class).single().toInstant()).isEqualTo(first);
        assertThat(first).isEqualTo(at("2027-01-04T10:00:00+07:00"));
        assertThat(second).isEqualTo(at("2027-01-05T10:00:00+07:00"));
        // And the record and the event name both people, with the old and the new value
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.proposedBy()).isEqualTo("kim");
        assertThat(approved.decidedBy()).isEqualTo("lan");
        assertThat(approved.previous().holidays()).containsOnlyKeys(LocalDate.parse("2027-01-01"));
        assertThat(approved.proposed().holidays()).containsKeys(LocalDate.parse("2027-01-04"));
        assertThat(engine.calendars.calendar("vn-banking-hours").version()).isEqualTo(2);
        ProcessEventPublisher.Event event = engine.events.stream()
                .filter(e -> e.type().equals("processes.calendar.changed.v1")).reduce((a, b) -> b).orElseThrow();
        assertThat(event.payload()).containsEntry("proposedBy", "kim").containsEntry("approvedBy", "lan")
                .containsEntry("version", 2L).containsEntry("calendarKey", "vn-banking-hours");
    }

    @Test
    void PRE_114_an_approval_over_a_calendar_changed_since_the_proposal_is_refused() {
        // Given two proposals against version 1
        calendarInForce();
        as("bank-a", "kim", MANAGER);
        CalendarChange tet = engine.calendars.propose("vn-banking-hours",
                vnBankingHours("2027-01-01", "2027-02-05"), 1, "Tet 2027");
        CalendarChange other = engine.calendars.propose("vn-banking-hours",
                vnBankingHours("2027-01-01", "2027-04-30"), 1, "Reunification Day");
        as("bank-a", "lan", MANAGER);
        engine.calendars.approve(tet.id(), null);

        // Then the second is stale: refused, and the calendar keeps the first change
        assertThatThrownBy(() -> engine.calendars.approve(other.id(), null))
                .isInstanceOf(VersionConflictException.class);
        assertThat(engine.calendars.calendar("vn-banking-hours").calendar().holidays())
                .containsOnlyKeys(LocalDate.parse("2027-01-01"), LocalDate.parse("2027-02-05"));
        assertThat(engine.calendars.change(other.id()).status()).isEqualTo("PENDING");
        // And a proposal against an old version is refused at once
        as("bank-a", "kim", MANAGER);
        assertThatThrownBy(() -> engine.calendars.propose("vn-banking-hours", vnBankingHours(), 1, "Late"))
                .isInstanceOf(VersionConflictException.class);
    }

    @Test
    void PRE_114_another_tenant_cannot_read_or_approve_a_calendar_and_nothing_is_written() {
        // Given bank-a's calendar and a pending change of it
        calendarInForce();
        as("bank-a", "kim", MANAGER);
        CalendarChange pending = engine.calendars.propose("vn-banking-hours", vnBankingHours(), 1, "No holidays");
        int changes = count("SELECT count(*) FROM pre_calendar_change");

        // When a manager of bank-b reads it, approves the change and starts a review that names the calendar
        as("bank-b", "lan", MANAGER);
        assertThatThrownBy(() -> engine.calendars.calendar("vn-banking-hours"))
                .isInstanceOf(CalendarNotFoundException.class);
        assertThatThrownBy(() -> engine.calendars.approve(pending.id(), null))
                .isInstanceOf(CalendarNotFoundException.class);
        as("bank-b", "minh", OFFICER);
        assertThatThrownBy(() -> engine.services.start(new StartRequest("loan-credit-review", "LN-B-1", Map.of(), null)))
                .isInstanceOfSatisfying(StepFailedException.class, e -> {
                    assertThat(e.nodeId()).isEqualTo("credit-review");
                    assertThat(e.reason()).isEqualTo("CALENDAR_NOT_FOUND");
                });

        // Then nothing is written: no instance or task in bank-b, the change still pending
        assertThat(count("SELECT count(*) FROM pre_instance WHERE tenant_id = 'bank-b'")).isZero();
        assertThat(count("SELECT count(*) FROM pre_task WHERE tenant_id = 'bank-b'")).isZero();
        assertThat(count("SELECT count(*) FROM pre_calendar_change")).isEqualTo(changes);
        as("bank-a", "kim", MANAGER);
        assertThat(engine.calendars.change(pending.id()).status()).isEqualTo("PENDING");
    }

    @Test
    void PRE_114_the_database_refuses_a_four_eyes_change_approved_by_its_proposer() {
        // Given a pending four-eyes proposal by kim
        as("bank-a", "kim", MANAGER);
        CalendarChange proposed = engine.calendars.propose("vn-banking-hours", vnBankingHours(), 0, "New calendar");

        // Then a writer that bypasses the service still cannot record kim as its approver
        assertThatThrownBy(() -> TestEngine.jdbc().sql("UPDATE pre_calendar_change SET status = 'APPROVED', "
                + "decided_by = 'kim', decided_at = now() WHERE id = ?").param(proposed.id()).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_pre_calendar_change_four_eyes");
    }

    @Test
    void PRE_114_single_approval_applies_a_change_at_once_and_records_it() {
        // Given the dev-profile relaxation
        TestEngine dev = TestEngine.replica("pod-dev", clock).calendarApproval(Calendars.Approval.SINGLE).build();
        dev.identity.as("bank-a", "kim", MANAGER);

        // When kim proposes a calendar
        CalendarChange applied = dev.calendars.propose("vn-banking-hours", vnBankingHours("2027-01-01"), 0,
                "Dev calendar");

        // Then it is in force, and the record says one person applied it
        assertThat(applied.status()).isEqualTo("APPROVED");
        assertThat(applied.approval()).isEqualTo("SINGLE");
        assertThat(applied.decidedBy()).isEqualTo("kim");
        assertThat(dev.calendars.calendar("vn-banking-hours").approvedBy()).isEqualTo("kim");
    }
}
