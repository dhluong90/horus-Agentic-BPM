package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.Page;
import io.github.dhluong90.bpm.process.api.PageRequest;
import io.github.dhluong90.bpm.process.api.PermissionDeniedException;
import io.github.dhluong90.bpm.process.api.QueryInvalidException;
import io.github.dhluong90.bpm.process.api.RoleFanOutExceededException;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.TaskQuery;
import io.github.dhluong90.bpm.process.api.TaskSummary;
import io.github.dhluong90.bpm.process.postgres.ProcessListQueries.TaskRange;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * PRE-030 on PostgreSQL. Credit officer {@code minh} holds roles {@code credit-officer} and {@code ops} and group
 * {@code branch-hcm-01}. Tenant {@code bank-a} has 30,000 open {@code credit-officer} tasks, 3 of them overdue, 12
 * tasks claimed by {@code minh}, open tasks for {@code ops} and {@code branch-hcm-01}, and branch tasks without a
 * due time. Tenant {@code bank-b} has its own tasks for the same names, which bank-a never sees.
 */
class TaskQueryPostgresIT {

    private static final Instant NOW = Instant.parse("2027-01-04T03:00:00Z");
    private static final String[] OFFICER = {"processes:read", "processes:task:work"};
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Comparator<TaskSummary> ORDER = Comparator.comparing((TaskSummary t) -> t.dueAt() == null)
            .thenComparing(t -> t.dueAt() == null ? Instant.MIN : t.dueAt()).thenComparing(TaskSummary::id);

    private final TestClock clock = TestClock.at(NOW);
    private TestEngine engine;

    @BeforeAll
    static void seed() {
        TestEngine.reset();
        TestEngine setup = TestEngine.replica("seed", TestClock.at(NOW)).build();
        for (String tenant : List.of("bank-a", "bank-b")) {
            setup.identity.as(tenant, "pipeline", "processes:deploy", "processes:read");
            setup.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                    TestEngine.resource("/bpmn/loan-credit-review-sla.bpmn"), null));
        }
        Timestamp now = Timestamp.from(NOW);
        // bank-a: 30,000 open credit-officer tasks due one minute apart, the first 3 overdue by 1 to 3 hours
        tasks("bank-a", "CO", 30_000, "assignee_role", "credit-officer", "OPEN", null,
                "CASE WHEN g <= 3 THEN ?::timestamptz - (g || ' hours')::interval "
                        + "ELSE ?::timestamptz + ((g - 3) || ' minutes')::interval END", now, now);
        // 12 credit-officer tasks claimed by minh, due tomorrow
        tasks("bank-a", "MC", 12, "assignee_role", "credit-officer", "CLAIMED", "minh",
                "?::timestamptz + interval '1 day' + (g || ' minutes')::interval", now);
        // 40 open ops tasks due every two minutes, 30 branch tasks every three, 4 branch tasks without a due time
        tasks("bank-a", "OP", 40, "assignee_role", "ops", "OPEN", null,
                "?::timestamptz + (g * 2 || ' minutes')::interval + interval '30 seconds'", now);
        tasks("bank-a", "BR", 30, "assignee_group", "branch-hcm-01", "OPEN", null,
                "?::timestamptz + (g * 3 || ' minutes')::interval + interval '15 seconds'", now);
        tasks("bank-a", "BU", 4, "assignee_group", "branch-hcm-01", "OPEN", null, "NULL::timestamptz");
        // bank-b: the same names, every task overdue
        tasks("bank-b", "XC", 200, "assignee_role", "credit-officer", "OPEN", null,
                "?::timestamptz - (g || ' minutes')::interval", now);
        tasks("bank-b", "XM", 12, "assignee_role", "credit-officer", "CLAIMED", "minh",
                "?::timestamptz - (g || ' minutes')::interval", now);
        TestEngine.jdbc().sql("ANALYZE pre_task").update();
        TestEngine.jdbc().sql("ANALYZE pre_instance").update();
    }

    /** {@code count} instances of loan-credit-review, each with one task of the given assignee and due time. */
    private static void tasks(String tenant, String prefix, int count, String column, String value, String status,
            String claimedBy, String dueAt, Object... dueParams) {
        String definition = TestEngine.jdbc().sql("SELECT id FROM pre_definition WHERE tenant_id = ? AND key_name = "
                + "'loan-credit-review'").param(tenant).query(String.class).single();
        Timestamp now = Timestamp.from(NOW);
        String id = "? || lpad(g::text, 24, '0')";
        TestEngine.jdbc().sql("INSERT INTO pre_instance (tenant_id, id, definition_id, definition_key, definition_ver, "
                        + "business_key, status, variables, started_at) SELECT ?, " + id + ", ?, 'loan-credit-review', 1, "
                        + "? || '-' || g, 'RUNNING', '{}', ? FROM generate_series(1, ?) g")
                .params(tenant, prefix, definition, "LN-" + prefix, now, count).update();
        TestEngine.jdbc().sql("INSERT INTO pre_token (tenant_id, id, instance_id, definition_id, token_kind, "
                        + "current_node_id, status, entered_at, created_at) SELECT ?, " + id.replace("? ||", "'T' || ? ||")
                        .replace("24", "23") + ", " + id + ", ?, 'SCOPE', 'loan-credit-review', 'WAITING_SCOPE', ?, ? "
                        + "FROM generate_series(1, ?) g")
                .params(tenant, prefix, prefix, definition, now, now, count).update();
        List<Object> params = new ArrayList<>(List.of(tenant, prefix, prefix, prefix, definition, value));
        params.add(status);
        params.add(claimedBy);
        params.add(claimedBy == null ? null : now);
        params.add(now);
        params.addAll(List.of(dueParams));
        params.add(count);
        TestEngine.jdbc().sql("INSERT INTO pre_task (tenant_id, id, instance_id, token_id, definition_id, node_id, "
                        + "name, " + column + ", status, claimed_by, claimed_at, created_at, due_at) SELECT ?, "
                        + id.replace("? ||", "'K' || ? ||").replace("24", "23") + ", " + id + ", "
                        + id.replace("? ||", "'T' || ? ||").replace("24", "23") + ", ?, 'credit-review', 'Credit review', "
                        + "?, ?, ?::text, ?::timestamptz, ?, " + dueAt + " FROM generate_series(1, ?) g")
                .params(params).update();
    }

    @BeforeEach
    void setUp() {
        engine = TestEngine.replica("pod-1", clock).build();
        asMinh("bank-a");
    }

    private void asMinh(String tenant) {
        engine.identity.as(tenant, "minh", OFFICER);
        engine.identity.roles = Set.of("credit-officer", "ops");
        engine.identity.groups = Set.of("branch-hcm-01");
    }

    private Page<TaskSummary> list(String assignment, Integer limit, String cursor, boolean breached) {
        return engine.queries.tasks(new TaskQuery(assignment == null ? Set.of() : Set.of(assignment.split(",")),
                Set.of(), null, null, null, breached, new PageRequest(limit, cursor)));
    }

    @Test
    void PRE_030_task_query_filters_bounded_by_index() {
        // When minh lists his own tasks
        Page<TaskSummary> mine = list("mine", null, null, false);

        // Then the 12 tasks he claimed, by due time, and no other page
        assertThat(mine.items()).hasSize(12).allSatisfy(t -> {
            assertThat(t.claimedBy()).isEqualTo("minh");
            assertThat(t.status()).isEqualTo("CLAIMED");
            assertThat(t.id()).startsWith("KMC");
        });
        assertThat(mine.items()).isSortedAccordingTo(ORDER);
        assertThat(mine.hasNext()).isFalse();

        // When he lists his candidate tasks, 50 to a page
        Page<TaskSummary> candidate = list("candidate", 50, null, false);

        // Then 50 open tasks across both roles and the group, by (dueAt, id), with a next page
        assertThat(candidate.items()).hasSize(50).isSortedAccordingTo(ORDER)
                .allSatisfy(t -> assertThat(t.status()).isEqualTo("OPEN"));
        assertThat(candidate.items()).extracting(TaskSummary::assignee)
                .contains("role:credit-officer", "role:ops", "group:branch-hcm-01");
        assertThat(candidate.nextCursor()).isNotNull();
        Page<TaskSummary> next = list("candidate", 50, candidate.nextCursor(), false);
        assertThat(next.items()).hasSize(50);
        assertThat(ORDER.compare(next.items().get(0), candidate.items().get(49))).isPositive();

        // When he lists missed deadlines
        Page<TaskSummary> breached = list("candidate", null, null, true);

        // Then the 3 overdue credit-officer tasks, most overdue first
        assertThat(breached.items()).extracting(TaskSummary::id).containsExactly(
                "KCO" + "%023d".formatted(3), "KCO" + "%023d".formatted(2), "KCO" + "%023d".formatted(1));
        assertThat(breached.items()).allSatisfy(t -> assertThat(t.dueAt()).isBefore(NOW));

        // When he asks for 500 at once, then the limit is refused naming the maximum
        assertThatThrownBy(() -> list(null, 500, null, false))
                .isInstanceOfSatisfying(QueryInvalidException.class, e -> {
                    assertThat(e.code()).isEqualTo("QUERY_INVALID");
                    assertThat(e.getMessage()).contains("1 to 200");
                });

        // And every range read is a range scan of its partial index, in index order, under a Limit
        Timestamp end = Timestamp.from(Instant.parse("9999-12-31T00:00:00Z"));
        Timestamp epoch = Timestamp.from(Instant.EPOCH);
        String[] both = {"CLAIMED", "OPEN"};
        assertRangeScan(TaskRange.ROLE, true, "idx_pre_task_role_due", List.of("bank-a", "ops", both, epoch, "", end, 51));
        // Nearly every open task is a credit-officer task, so the planner may read the tenant's due-time index and
        // filter by role: still a partial-index range scan in due order under the Limit, never a sort
        assertRangeScan(TaskRange.ROLE, true, "idx_pre_task_role_due|idx_pre_task_open_due",
                List.of("bank-a", "credit-officer", both, epoch, "", end, 51));
        assertRangeScan(TaskRange.ROLE, true, "idx_pre_task_role_due|idx_pre_task_open_due",
                List.of("bank-a", "credit-officer", both, epoch, "", Timestamp.from(NOW), 51));
        assertRangeScan(TaskRange.CLAIMED, true, "idx_pre_task_claimed", List.of("bank-a", "minh", both, epoch, "", end, 51));
        assertRangeScan(TaskRange.MINE, true, "idx_pre_task_mine", List.of("bank-a", "minh", both, epoch, "", end, 51));
        assertRangeScan(TaskRange.GROUP, false, "idx_pre_task_group", List.of("bank-a", "branch-hcm-01", both, "", 51));
        assertRangeScan(TaskRange.ANY, true, "idx_pre_task_open_due", List.of("bank-a", both, epoch, "", end, 51));
    }

    private static void assertRangeScan(TaskRange range, boolean dated, String index, List<Object> params) {
        String json = TestEngine.jdbc().sql("EXPLAIN (FORMAT JSON) " + ProcessListQueries.tasks(range, dated).sql())
                .params(params).query(String.class).single();
        List<JsonNode> nodes = new ArrayList<>();
        collect(JSON.readTree(json).get(0).get("Plan"), nodes);
        assertThat(nodes.get(0).path("Node Type").asString("")).as(range + " plan").isEqualTo("Limit");
        assertThat(nodes).as(range + " plan").noneSatisfy(n -> assertThat(n.path("Node Type").asString(""))
                .isIn("Seq Scan", "Sort", "Bitmap Heap Scan"));
        assertThat(nodes).as(range + " plan").anySatisfy(n -> {
            assertThat(n.path("Index Name").asString("")).isIn((Object[]) index.split("\\|"));
            assertThat(n.path("Index Cond").asString("")).contains("tenant_id");
        });
    }

    private static void collect(JsonNode node, List<JsonNode> into) {
        into.add(node);
        for (JsonNode child : node.path("Plans")) {
            collect(child, into);
        }
    }

    @Test
    void PRE_030_tasks_without_a_due_time_follow_by_id_across_pages() {
        // When minh pages through the branch queue seven at a time
        List<TaskSummary> all = new ArrayList<>();
        String cursor = null;
        do {
            Page<TaskSummary> page = engine.queries.tasks(new TaskQuery(Set.of("candidate"), Set.of(), null,
                    "branch-hcm-01", null, false, new PageRequest(7, cursor)));
            assertThat(page.items().size()).isLessThanOrEqualTo(7);
            all.addAll(page.items());
            cursor = page.nextCursor();
        } while (cursor != null);

        // Then 30 dated tasks by due time, then the 4 without one by id, each once
        assertThat(all).hasSize(34).isSortedAccordingTo(ORDER);
        assertThat(all).extracting(TaskSummary::id).doesNotHaveDuplicates();
        assertThat(all.subList(30, 34)).allSatisfy(t -> assertThat(t.dueAt()).isNull());
    }

    @Test
    void PRE_030_role_narrows_the_candidate_list_and_mine_alone_refuses_it() {
        Page<TaskSummary> ops = engine.queries.tasks(new TaskQuery(Set.of("candidate"), Set.of(), "ops", null, null,
                false, new PageRequest(100, null)));
        assertThat(ops.items()).hasSize(40).allSatisfy(t -> assertThat(t.assignee()).isEqualTo("role:ops"));

        // A role the caller does not hold lists nothing
        assertThat(engine.queries.tasks(new TaskQuery(Set.of("candidate"), Set.of(), "senior-credit-officer", null,
                null, false, null)).items()).isEmpty();
        assertThatThrownBy(() -> engine.queries.tasks(new TaskQuery(Set.of("mine"), Set.of(), "ops", null, null, false,
                null))).isInstanceOf(QueryInvalidException.class);
        assertThatThrownBy(() -> engine.queries.tasks(new TaskQuery(Set.of("everything"), Set.of(), null, null, null,
                false, null))).isInstanceOf(QueryInvalidException.class);
        assertThatThrownBy(() -> engine.queries.tasks(new TaskQuery(Set.of(), Set.of("COMPLETED"), null, null, null,
                false, null))).isInstanceOf(QueryInvalidException.class);
    }

    @Test
    void PRE_030_another_tenants_tasks_and_cursors_are_refused_and_nothing_is_written() {
        // Given bank-a's first candidate page, and the row counts
        Page<TaskSummary> first = list("candidate", 50, null, false);
        int history = count("SELECT count(*) FROM pre_history");
        int tasks = count("SELECT count(*) FROM pre_task");

        // When minh signs in to bank-b and lists, then replays bank-a's cursor there
        asMinh("bank-b");
        Page<TaskSummary> mineB = list("mine", null, null, false);

        // Then only bank-b's tasks are listed, and bank-a's cursor is refused
        assertThat(mineB.items()).hasSize(12).allSatisfy(t -> assertThat(t.id()).startsWith("KXM"));
        assertThat(list("candidate", 200, null, true).items()).allSatisfy(t -> assertThat(t.id()).startsWith("KXC"));
        assertThatThrownBy(() -> list("candidate", 50, first.nextCursor(), false))
                .isInstanceOf(QueryInvalidException.class);
        // A cursor issued for other filters is refused too
        asMinh("bank-a");
        assertThatThrownBy(() -> list("mine", 50, first.nextCursor(), false)).isInstanceOf(QueryInvalidException.class);
        // And the list wrote nothing
        assertThat(count("SELECT count(*) FROM pre_history")).isEqualTo(history);
        assertThat(count("SELECT count(*) FROM pre_task")).isEqualTo(tasks);
    }

    @Test
    void PRE_030_assignment_any_needs_task_manage_and_stays_in_the_tenant() {
        // Without processes:task:manage the whole queue is refused
        assertThatThrownBy(() -> list("any", 50, null, false)).isInstanceOf(PermissionDeniedException.class);
        // Without processes:task:work any list is refused
        engine.identity.as("bank-a", "minh", "processes:read");
        assertThatThrownBy(() -> list("mine", 50, null, false)).isInstanceOf(PermissionDeniedException.class);

        // A team lead with processes:task:manage sees bank-a's whole queue by due time, overdue first
        engine.identity.as("bank-a", "lan", "processes:task:work", "processes:task:manage");
        Page<TaskSummary> any = list("any", 200, null, false);
        assertThat(any.items()).hasSize(200).isSortedAccordingTo(ORDER)
                .allSatisfy(t -> assertThat(t.id()).doesNotStartWith("KX"));
        assertThat(any.items().get(0).id()).isEqualTo("KCO" + "%023d".formatted(3));
        // And her overdue view is the 3 overdue tasks of bank-a only
        assertThat(list("any", 200, null, true).items()).hasSize(3);
        assertThat(Duration.between(any.items().get(0).dueAt(), NOW)).isEqualTo(Duration.ofHours(3));
    }

    @Test
    void PRE_216_a_task_list_over_more_role_queues_than_the_cap_is_400_ROLE_FAN_OUT_EXCEEDED() {
        // Given minh holds 99 roles and the group branch-hcm-01, which is 100 queues: the cap, so the list is served
        engine.identity.roles = rolesNamed(99);
        assertThat(list("candidate", 10, null, false).items()).hasSize(10);

        // When he gains one more role, 101 queues over query.max-roles of 100
        engine.identity.roles = rolesNamed(100);

        // Then the list is refused naming the cap and the number of queues, and reads no task
        assertThatThrownBy(() -> list("candidate", 10, null, false))
                .isInstanceOfSatisfying(RoleFanOutExceededException.class, e -> {
                    assertThat(e.code()).isEqualTo("ROLE_FAN_OUT_EXCEEDED");
                    assertThat(e.provided()).isEqualTo(101);
                    assertThat(e.cap()).isEqualTo(100);
                    assertThat(e.getMessage()).contains("101").contains("query.max-roles is 100");
                });

        // And one named role narrows the list to one queue, so it is served; his own tasks never fan out
        assertThat(engine.queries.tasks(new TaskQuery(Set.of("candidate"), Set.of(), "ops", null, null, false,
                new PageRequest(5, null))).items()).hasSize(5);
        assertThat(list("mine", null, null, false).items()).hasSize(12);
    }

    /** Role names {@code ops} and {@code role-001} onward, {@code count} of them in all. */
    private static Set<String> rolesNamed(int count) {
        Set<String> roles = new java.util.LinkedHashSet<>(Set.of("ops"));
        for (int i = 1; roles.size() < count; i++) {
            roles.add("role-%03d".formatted(i));
        }
        return roles;
    }

    private static int count(String sql) {
        return TestEngine.jdbc().sql(sql).query(Integer.class).single();
    }
}
