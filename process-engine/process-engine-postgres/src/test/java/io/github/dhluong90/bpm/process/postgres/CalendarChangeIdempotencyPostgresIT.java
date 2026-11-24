package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.CalendarChange;
import io.github.dhluong90.bpm.process.api.CalendarSummary;
import io.github.dhluong90.bpm.process.api.ContentChangedException;
import io.github.dhluong90.bpm.process.api.ContentHashRequiredException;
import io.github.dhluong90.bpm.process.api.IdempotencyKeyReuseException;
import io.github.dhluong90.bpm.process.api.Page;
import io.github.dhluong90.bpm.process.api.PageRequest;
import io.github.dhluong90.bpm.process.api.PermissionDeniedException;
import io.github.dhluong90.bpm.process.api.QueryInvalidException;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PRE-181 and PRE-182 on PostgreSQL: the calendar service answers a repeated change once per idempotency key, and
 * lists the tenant's calendars and changes in bounded pages. Bank A's calendar {@code vn-banking-hours} is changed
 * for the 2027 Tet holidays by Kim and approved by Lan.
 */
class CalendarChangeIdempotencyPostgresIT {

    private static final String[] MANAGER = {"processes:calendar:manage", "processes:read"};

    private final TestClock clock = TestClock.at("2026-12-31T08:00:00Z");
    private TestEngine engine;

    @BeforeEach
    void setUp() {
        TestEngine.reset();
        engine = TestEngine.replica("pod-1", clock).build();
    }

    private static int count(String sql, Object... params) {
        return TestEngine.jdbc().sql(sql).params(params).query(Integer.class).single();
    }

    private long changedEvents() {
        return engine.events.stream().filter(e -> e.type().equals("processes.calendar.changed.v1")).count();
    }

    @Test
    void PRE_182_a_proposal_repeated_with_the_same_key_returns_the_first_answer_and_writes_nothing_more() {
        // Given Kim proposes the first calendar of bank-a under key k-1
        engine.identity.as("bank-a", "kim", MANAGER);
        CalendarChange first = engine.calendars.propose("vn-banking-hours",
                BusinessCalendarPostgresIT.vnBankingHours("2027-02-08"), 0, "Tet 2027", "k-1");

        // When the same request is sent again, as after a lost response
        CalendarChange again = engine.calendars.propose("vn-banking-hours",
                BusinessCalendarPostgresIT.vnBankingHours("2027-02-08"), 0, "Tet 2027", "k-1");

        // Then it is the same change, once
        assertThat(again).isEqualTo(first);
        assertThat(count("SELECT count(*) FROM pre_calendar_change WHERE tenant_id = 'bank-a'")).isEqualTo(1);

        // And the key with another request is refused and writes nothing
        assertThatThrownBy(() -> engine.calendars.propose("vn-banking-hours",
                BusinessCalendarPostgresIT.vnBankingHours("2027-02-09"), 0, "Tet 2027", "k-1"))
                .isInstanceOf(IdempotencyKeyReuseException.class);
        assertThat(count("SELECT count(*) FROM pre_calendar_change WHERE tenant_id = 'bank-a'")).isEqualTo(1);

        // And a repeat after Lan approved still answers as the first call did: PENDING, with no second change
        engine.identity.as("bank-a", "lan", MANAGER);
        engine.calendars.approve(first.id(), "Checked", "a-1");
        engine.identity.as("bank-a", "kim", MANAGER);
        CalendarChange replay = engine.calendars.propose("vn-banking-hours",
                BusinessCalendarPostgresIT.vnBankingHours("2027-02-08"), 0, "Tet 2027", "k-1");
        assertThat(replay.status()).isEqualTo("PENDING");
        assertThat(count("SELECT count(*) FROM pre_calendar_change WHERE tenant_id = 'bank-a'")).isEqualTo(1);
    }

    @Test
    void PRE_182_an_approval_repeated_with_the_same_key_applies_once_and_emits_one_event() {
        // Given a pending change and Lan's approval under key a-1
        engine.identity.as("bank-a", "kim", MANAGER);
        CalendarChange pending = engine.calendars.propose("vn-banking-hours",
                BusinessCalendarPostgresIT.vnBankingHours("2027-02-08"), 0, "Tet 2027", "k-1");
        engine.identity.as("bank-a", "lan", MANAGER);
        CalendarChange approved = engine.calendars.approve(pending.id(), "Checked", "a-1");

        // When the approval is sent again, and again with another key
        CalendarChange replay = engine.calendars.approve(pending.id(), "Checked", "a-1");

        // Then the replay is the first answer, the calendar is at version 1 and one event was published
        assertThat(replay).isEqualTo(approved);
        assertThat(engine.calendars.calendar("vn-banking-hours").version()).isEqualTo(1);
        assertThat(changedEvents()).isEqualTo(1);
        // while a new key meets the real state: the change is no longer pending
        assertThatThrownBy(() -> engine.calendars.approve(pending.id(), "Checked", "a-2"))
                .isInstanceOf(io.github.dhluong90.bpm.process.api.InvalidStateException.class);
    }

    @Test
    void PRE_182_two_concurrent_proposals_with_one_key_create_one_change_and_a_key_is_per_tenant() throws Exception {
        // Given two requests with the same key arriving together
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<CalendarChange>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                TestEngine replica = TestEngine.replica("pod-c" + i, clock).build();
                replica.identity.as("bank-a", "kim", MANAGER);
                results.add(pool.submit(() -> {
                    go.await();
                    return replica.calendars.propose("vn-banking-hours",
                            BusinessCalendarPostgresIT.vnBankingHours("2027-02-08"), 0, "Tet 2027", "k-race");
                }));
            }
            go.countDown();

            // Then both get the same change and one row exists
            assertThat(results.get(0).get()).isEqualTo(results.get(1).get());
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM pre_calendar_change WHERE tenant_id = 'bank-a'")).isEqualTo(1);
        // And bank-b may use the same key for its own change
        engine.identity.as("bank-b", "kim", MANAGER);
        CalendarChange other = engine.calendars.propose("vn-banking-hours",
                BusinessCalendarPostgresIT.vnBankingHours("2027-02-08"), 0, "Tet 2027", "k-race");
        assertThat(other.id()).isNotEqualTo(results.get(0).get().id());
        assertThat(count("SELECT count(*) FROM pre_calendar_change")).isEqualTo(2);
    }

    @Test
    void PRE_182_a_proposal_without_the_permission_is_refused_before_any_key_is_claimed() {
        engine.identity.as("bank-a", "minh", "processes:read");

        assertThatThrownBy(() -> engine.calendars.propose("vn-banking-hours",
                BusinessCalendarPostgresIT.vnBankingHours(), 0, "No right", "k-9"))
                .isInstanceOf(PermissionDeniedException.class);
        assertThat(count("SELECT count(*) FROM pre_idempotency")).isZero();
    }

    @Test
    void PRE_181_calendars_and_changes_are_listed_in_pages_for_the_callers_tenant_only() {
        // Given bank-a with three calendars (one with a pending change) and bank-b with one
        engine.identity.as("bank-a", "kim", MANAGER);
        List<CalendarChange> proposed = new ArrayList<>();
        for (String key : List.of("branch-hours", "card-centre", "vn-banking-hours")) {
            engine.identity.as("bank-a", "kim", MANAGER);
            proposed.add(engine.calendars.propose(key, BusinessCalendarPostgresIT.vnBankingHours(), 0, "Calendar " + key,
                    "p-" + key));
            clock.advance(java.time.Duration.ofMinutes(1));
        }
        engine.identity.as("bank-a", "lan", MANAGER);
        proposed.subList(0, 2).forEach(c -> engine.calendars.approve(c.id(), null, "a-" + c.calendarKey()));
        engine.identity.as("bank-b", "kim", MANAGER);
        engine.calendars.propose("bank-b-hours", BusinessCalendarPostgresIT.vnBankingHours(), 0, "Other bank", "p-b");

        // When bank-a lists calendars two at a time
        engine.identity.as("bank-a", "lan", MANAGER);
        Page<CalendarSummary> first = engine.calendars.calendars(new PageRequest(1, null));
        Page<CalendarSummary> second = engine.calendars.calendars(new PageRequest(1, first.nextCursor()));

        // Then the keys come in order and bank-b's calendar never appears
        assertThat(first.items()).extracting(CalendarSummary::key).containsExactly("branch-hours");
        assertThat(second.items()).extracting(CalendarSummary::key).containsExactly("card-centre");
        assertThat(second.hasNext()).isFalse();
        // And the pending changes of bank-a are the one not yet decided, the approved ones newest first
        assertThat(engine.calendars.changes("PENDING", PageRequest.first()).items())
                .extracting(CalendarChange::calendarKey).containsExactly("vn-banking-hours");
        Page<CalendarChange> approvedFirst = engine.calendars.changes("APPROVED", new PageRequest(1, null));
        assertThat(approvedFirst.items()).extracting(CalendarChange::calendarKey).containsExactly("card-centre");
        assertThat(engine.calendars.changes("APPROVED", new PageRequest(1, approvedFirst.nextCursor())).items())
                .extracting(CalendarChange::calendarKey).containsExactly("branch-hours");
        // And a cursor of another tenant, an unknown status and an oversized limit are refused
        engine.identity.as("bank-b", "kim", MANAGER);
        assertThatThrownBy(() -> engine.calendars.calendars(new PageRequest(1, first.nextCursor())))
                .isInstanceOf(QueryInvalidException.class);
        assertThatThrownBy(() -> engine.calendars.changes("DONE", PageRequest.first()))
                .isInstanceOf(QueryInvalidException.class);
        assertThatThrownBy(() -> engine.calendars.calendars(new PageRequest(201, null)))
                .isInstanceOf(QueryInvalidException.class);
    }

    @Test
    void PRE_370_a_calendar_change_approval_must_name_the_reviewed_change_hash() {
        // Given Kim's pending holiday change for vn-banking-hours, and an engine run with the shipped default
        TestEngine strict = TestEngine.replica("pod-strict", clock).strictApprovalHashes(true).build();
        engine.identity.as("bank-a", "kim", MANAGER);
        CalendarChange pending = engine.calendars.propose("vn-banking-hours",
                BusinessCalendarPostgresIT.vnBankingHours("2027-02-08"), 0, "Tet 2027", "k-1");
        strict.identity.as("bank-a", "lan", MANAGER);

        // When Lan approves without a hash, then with the hash of another change
        assertThatThrownBy(() -> strict.calendars.approve(pending.id(), "Checked", "a-1", null))
                .isInstanceOfSatisfying(ContentHashRequiredException.class,
                        e -> assertThat(e.code()).isEqualTo("CONTENT_HASH_REQUIRED"));
        assertThatThrownBy(() -> strict.calendars.approve(pending.id(), "Checked", "a-2", "0".repeat(64)))
                .isInstanceOf(ContentChangedException.class);

        // Then the change is still pending and no calendar exists
        assertThat(count("SELECT count(*) FROM pre_calendar_change WHERE tenant_id = 'bank-a' AND status = 'PENDING'"))
                .isEqualTo(1);
        assertThat(changedEvents()).isZero();

        // And with the hash she reviewed (the one the change shows as contentHash) it is applied
        String reviewed = io.github.dhluong90.bpm.process.internal.service.Calendars.changeHash("vn-banking-hours", 0,
                pending.proposed());
        assertThat(strict.calendars.approve(pending.id(), "Checked", "a-3", reviewed).status()).isEqualTo("APPROVED");
    }
}
