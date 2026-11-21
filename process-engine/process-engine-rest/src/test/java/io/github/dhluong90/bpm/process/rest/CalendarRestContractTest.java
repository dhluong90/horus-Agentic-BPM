package io.github.dhluong90.bpm.process.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.dhluong90.bpm.process.api.CalendarChange;
import io.github.dhluong90.bpm.process.api.CalendarNotFoundException;
import io.github.dhluong90.bpm.process.api.CalendarService;
import io.github.dhluong90.bpm.process.api.CalendarSummary;
import io.github.dhluong90.bpm.process.api.CalendarView;
import io.github.dhluong90.bpm.process.api.IdempotencyKeyReuseException;
import io.github.dhluong90.bpm.process.api.Page;
import io.github.dhluong90.bpm.process.api.PageRequest;
import io.github.dhluong90.bpm.process.api.QueryInvalidException;
import io.github.dhluong90.bpm.process.api.SelfApprovalForbiddenException;
import io.github.dhluong90.bpm.process.api.VersionConflictException;
import io.github.dhluong90.bpm.process.api.WorkingCalendar;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The calendar endpoints (#409, PRE-181, PRE-182), as the controller and the problem handler write them, validate
 * against {@code process-engine/api/openapi.yaml}. The calendar service is a test double that answers like the engine,
 * including the replay of an idempotency key; the controller, the JSON views and the problem details are the real ones.
 */
class CalendarRestContractTest {

    private static final OpenApiBodies BODIES = OpenApiBodies.load(ProcessRestContractTest.CONTRACT);
    private static final String BASE = ProcessController.BASE;
    private static final Instant NOW = Instant.parse("2026-12-31T08:00:00Z");
    private static final String CHANGE = "01JD0000000000000000000C01";
    private static final String PROPOSAL = """
            {"reason":"Tet holiday 2027 added",
             "calendar":{"zone":"Asia/Ho_Chi_Minh",
               "week":{"MONDAY":["08:00-17:00"],"TUESDAY":["08:00-17:00"],"WEDNESDAY":["08:00-17:00"],
                       "THURSDAY":["08:00-17:00"],"FRIDAY":["08:00-17:00"]},
               "holidays":{"2027-02-08":"Tet"}}}""";

    private final FakeCalendars calendars = new FakeCalendars();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new CalendarController(calendars))
            .setControllerAdvice(new ProcessProblemDetails()).build();

    private static WorkingCalendar hours(String... holidays) {
        Map<DayOfWeek, List<WorkingCalendar.Hours>> week = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY)) {
            week.put(day, List.of(WorkingCalendar.Hours.parse("08:00-17:00")));
        }
        TreeMap<LocalDate, String> dates = new TreeMap<>();
        for (String h : holidays) {
            dates.put(LocalDate.parse(h), "Tet");
        }
        return new WorkingCalendar(ZoneId.of("Asia/Ho_Chi_Minh"), week, dates);
    }

    @Test
    void PRE_181_the_calendar_list_one_calendar_and_the_changes_match_the_contract() throws Exception {
        MvcResult list = exchange(get(BASE + "/calendars").param("limit", "50"));
        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        assertThat(list.getResponse().getContentAsString()).contains("\"key\":\"vn-banking-hours\"")
                .contains("\"zone\":\"Asia/Ho_Chi_Minh\"").contains("\"hasNext\":false").doesNotContain("holidays");

        MvcResult one = exchange(get(BASE + "/calendars/vn-banking-hours"));
        assertThat(one.getResponse().getHeader("ETag")).isEqualTo("\"3\"");
        assertThat(one.getResponse().getContentAsString()).contains("\"MONDAY\":[\"08:00-17:00\"]")
                .contains("\"2027-01-01\":\"Tet\"").contains("\"approvedBy\":\"lan\"");
        assertProblem(get(BASE + "/calendars/card-centre"), 404, "CALENDAR_NOT_FOUND");

        MvcResult changes = exchange(get(BASE + "/calendar-changes").param("status", "PENDING"));
        assertThat(changes.getResponse().getContentAsString()).contains("\"status\":\"PENDING\"")
                .contains("\"previous\":null").contains("\"decidedBy\":null");
        assertThat(calendars.statuses).containsExactly("PENDING");
        assertThat(exchange(get(BASE + "/calendar-changes/" + CHANGE)).getResponse().getContentAsString())
                .contains("\"proposedBy\":\"kim\"");
        assertProblem(get(BASE + "/calendar-changes").param("status", "DONE"), 400, "QUERY_INVALID");
    }

    @Test
    void PRE_182_a_proposal_takes_if_match_and_an_idempotency_key_and_a_repeat_returns_the_first_answer() throws Exception {
        MvcResult first = exchange(post(BASE + "/calendars/vn-banking-hours/changes")
                .contentType(MediaType.APPLICATION_JSON).header("If-Match", "\"3\"").header("Idempotency-Key", "k-1")
                .content(PROPOSAL));
        MvcResult repeat = exchange(post(BASE + "/calendars/vn-banking-hours/changes")
                .contentType(MediaType.APPLICATION_JSON).header("If-Match", "\"3\"").header("Idempotency-Key", "k-1")
                .content(PROPOSAL));

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(first.getResponse().getHeader("Location")).isEqualTo(BASE + "/calendar-changes/" + CHANGE);
        assertThat(first.getResponse().getContentAsString()).contains("\"status\":\"PENDING\"")
                .contains("\"approval\":\"FOUR_EYES\"").contains("\"baseVersion\":3").contains("\"2027-02-08\":\"Tet\"");
        assertThat(repeat.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(calendars.created).isEqualTo(1);
        assertThat(calendars.expectedVersions).containsExactly(3L, 3L);
        assertProblem(post(BASE + "/calendars/vn-banking-hours/changes").contentType(MediaType.APPLICATION_JSON)
                .header("If-Match", "\"3\"").header("Idempotency-Key", "k-1")
                .content(PROPOSAL.replace("Tet holiday 2027 added", "Another reason")), 422,
                "IDEMPOTENCY_KEY_REUSE_MISMATCH");
    }

    @Test
    void PRE_182_approve_and_reject_take_an_idempotency_key_and_a_repeat_returns_the_first_answer() throws Exception {
        MvcResult approved = exchange(post(BASE + "/calendar-changes/" + CHANGE + ":approve")
                .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", "a-1")
                .content("{\"comment\":\"Checked against the State Bank notice\"}"));
        MvcResult repeat = exchange(post(BASE + "/calendar-changes/" + CHANGE + ":approve")
                .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", "a-1")
                .content("{\"comment\":\"Checked against the State Bank notice\"}"));
        assertThat(approved.getResponse().getContentAsString()).contains("\"status\":\"APPROVED\"")
                .contains("\"decidedBy\":\"lan\"");
        assertThat(repeat.getResponse().getContentAsString()).isEqualTo(approved.getResponse().getContentAsString());
        assertThat(calendars.decisions).isEqualTo(1);

        MvcResult rejected = exchange(post(BASE + "/calendar-changes/01JD0000000000000000000C02:reject")
                .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", "j-1")
                .content("{\"comment\":\"The date is not a bank holiday\"}"));
        assertThat(rejected.getResponse().getContentAsString()).contains("\"status\":\"REJECTED\"");
    }

    @Test
    void PRE_182_TR_SEC_08_every_calendar_problem_matches_the_contract() throws Exception {
        // 428: no If-Match, then no Idempotency-Key, on a proposal and on a decision
        assertProblem(post(BASE + "/calendars/vn-banking-hours/changes").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "k-2").content(PROPOSAL), 428, "PRECONDITION_REQUIRED");
        assertProblem(post(BASE + "/calendars/vn-banking-hours/changes").contentType(MediaType.APPLICATION_JSON)
                .header("If-Match", "\"3\"").content(PROPOSAL), 428, "PRECONDITION_REQUIRED");
        assertProblem(post(BASE + "/calendar-changes/" + CHANGE + ":approve").contentType(MediaType.APPLICATION_JSON)
                .content("{}"), 428, "PRECONDITION_REQUIRED");
        // 400: a field the contract does not have (a tenant is never read), a calendar that is not valid
        MvcResult tenant = mvc.perform(post(BASE + "/calendars/vn-banking-hours/changes")
                .contentType(MediaType.APPLICATION_JSON).header("If-Match", "\"3\"").header("Idempotency-Key", "k-3")
                .content(PROPOSAL.replace("{\"reason\"", "{\"tenantId\":\"bank-b\",\"reason\""))).andReturn();
        assertThat(tenant.getResponse().getStatus()).isEqualTo(400);
        assertThat(tenant.getResponse().getContentAsString()).contains("\"code\":\"INPUT_INVALID\"");
        assertThat(calendars.created).isZero();
        MvcResult badHours = mvc.perform(post(BASE + "/calendars/vn-banking-hours/changes")
                .contentType(MediaType.APPLICATION_JSON).header("If-Match", "\"3\"").header("Idempotency-Key", "k-4")
                .content(PROPOSAL.replace("08:00-17:00", "8 to 5"))).andReturn();
        assertThat(badHours.getResponse().getStatus()).isEqualTo(400);
        assertThat(badHours.getResponse().getContentAsString()).contains("\"code\":\"INPUT_INVALID\"")
                .contains("calendar is not valid");
        assertProblem(post(BASE + "/calendars/vn-banking-hours/changes").contentType(MediaType.APPLICATION_JSON)
                .header("If-Match", "three").header("Idempotency-Key", "k-5").content(PROPOSAL), 400, "INPUT_INVALID");
        // 409: a stale calendar; 403: the proposer approves
        assertProblem(post(BASE + "/calendars/vn-banking-hours/changes").contentType(MediaType.APPLICATION_JSON)
                .header("If-Match", "\"2\"").header("Idempotency-Key", "k-6").content(PROPOSAL), 409, "VERSION_CONFLICT");
        assertProblem(post(BASE + "/calendar-changes/" + CHANGE + ":approve").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "a-self").content("{\"comment\":\"mine\"}"), 403, "SELF_APPROVAL_FORBIDDEN");
        assertProblem(post(BASE + "/calendar-changes/01JD0000000000000000000C09:approve")
                .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", "a-9").content("{}"), 404,
                "CALENDAR_NOT_FOUND");
    }

    private MvcResult assertProblem(RequestBuilder request, int status, String code) throws Exception {
        MvcResult result = exchange(request);
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(result.getResponse().getContentAsString()).contains("\"code\":\"" + code + "\"");
        return result;
    }

    private MvcResult exchange(RequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        BODIES.assertExchange(result);
        return result;
    }

    /** bank-a's {@code vn-banking-hours} at version 3, a pending change by kim, and lan as the caller. */
    static final class FakeCalendars implements CalendarService {

        final List<String> statuses = new ArrayList<>();
        final List<Long> expectedVersions = new ArrayList<>();
        final Map<String, CalendarChange> replays = new HashMap<>();
        final Map<String, String> hashes = new HashMap<>();
        int created;
        int decisions;

        private static CalendarChange change(String id, String status, String decidedBy) {
            return new CalendarChange(id, "vn-banking-hours", status, "FOUR_EYES", 3, null, hours("2027-02-08"),
                    "Tet holiday 2027 added", "kim", NOW, decidedBy, decidedBy == null ? null : NOW.plusSeconds(60),
                    null);
        }

        @Override
        public CalendarView calendar(String key) {
            if (!key.equals("vn-banking-hours")) {
                throw new CalendarNotFoundException("No calendar " + key + " in this tenant");
            }
            return new CalendarView(key, 3, hours("2027-01-01"), "kim", "lan", NOW);
        }

        @Override
        public CalendarChange propose(String key, WorkingCalendar calendar, long expectedVersion, String reason) {
            return propose(key, calendar, expectedVersion, reason, null);
        }

        @Override
        public CalendarChange propose(String key, WorkingCalendar calendar, long expectedVersion, String reason,
                String idempotencyKey) {
            expectedVersions.add(expectedVersion);
            String hash = key + expectedVersion + reason;
            if (replays.containsKey(idempotencyKey)) {
                if (!hashes.get(idempotencyKey).equals(hash)) {
                    throw new IdempotencyKeyReuseException("The Idempotency-Key was used with a different request");
                }
                return replays.get(idempotencyKey);
            }
            if (expectedVersion != 3) {
                throw new VersionConflictException("Calendar " + key + " is at version 3, not " + expectedVersion);
            }
            created++;
            CalendarChange made = change(CHANGE, "PENDING", null);
            replays.put(idempotencyKey, made);
            hashes.put(idempotencyKey, hash);
            return made;
        }

        @Override
        public CalendarChange approve(String changeId, String comment) {
            return approve(changeId, comment, null);
        }

        @Override
        public CalendarChange approve(String changeId, String comment, String idempotencyKey) {
            if (!changeId.equals(CHANGE)) {
                throw new CalendarNotFoundException("No calendar change " + changeId);
            }
            if ("mine".equals(comment)) {
                throw new SelfApprovalForbiddenException("The proposer of a calendar change cannot approve it");
            }
            if (replays.containsKey(idempotencyKey)) {
                return replays.get(idempotencyKey);
            }
            decisions++;
            CalendarChange done = change(CHANGE, "APPROVED", "lan");
            replays.put(idempotencyKey, done);
            return done;
        }

        @Override
        public CalendarChange reject(String changeId, String comment) {
            return reject(changeId, comment, null);
        }

        @Override
        public CalendarChange reject(String changeId, String comment, String idempotencyKey) {
            return change(changeId, "REJECTED", "lan");
        }

        @Override
        public CalendarChange change(String changeId) {
            return change(changeId, "PENDING", null);
        }

        @Override
        public Page<CalendarSummary> calendars(PageRequest page) {
            return new Page<>(List.of(new CalendarSummary("vn-banking-hours", 3, "Asia/Ho_Chi_Minh", "kim", "lan",
                    NOW)), null);
        }

        @Override
        public Page<CalendarChange> changes(String status, PageRequest page) {
            if (!List.of("PENDING", "APPROVED", "REJECTED").contains(status)) {
                throw new QueryInvalidException("status must be one of PENDING, APPROVED, REJECTED");
            }
            statuses.add(status);
            return new Page<>(List.of(change(CHANGE, status, null)), null);
        }
    }
}
