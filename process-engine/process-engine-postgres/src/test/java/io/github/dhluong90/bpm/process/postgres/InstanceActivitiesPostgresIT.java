package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.InstanceActivities;
import io.github.dhluong90.bpm.process.api.InstanceNotFoundException;
import io.github.dhluong90.bpm.process.api.PermissionDeniedException;
import io.github.dhluong90.bpm.process.api.QueryInvalidException;
import io.github.dhluong90.bpm.process.internal.service.InstanceActivityService;
import io.github.dhluong90.bpm.process.spi.CallerResolver;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The activities of one instance on PostgreSQL (PRE-027): per step entered, completed, duration, attempts, actor and
 * incident, read only from the monthly history partitions between the instance's start and end, with no input value
 * in the answer, and never across tenants.
 */
@Testcontainers
class InstanceActivitiesPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    static final String I7 = "I7000000000000000000000007";
    static final String PAYOUT = "I9000000000000000000000009";
    static final String RUNNING = "IA00000000000000000000000A";
    static final String OTHER = "I8000000000000000000000008";
    static final String DEFINITION = "DEFA0000000000000000000002";
    static final Instant STARTED = Instant.parse("2027-03-10T08:00:00Z");
    static final Instant ENDED = Instant.parse("2027-04-02T10:00:00Z");
    static final String NATIONAL_ID = "079123456789";

    static PGSimpleDataSource dataSource;
    static JdbcClient jdbc;

    final AtomicReference<String> tenant = new AtomicReference<>("bank-a");
    final AtomicReference<Set<String>> permissions = new AtomicReference<>(Set.of("processes:read"));
    InstanceActivityService service;

    @BeforeAll
    static void schemaAndSeed() {
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        new ProcessEngineSchemaMigrator(dataSource).migrate();
        jdbc = JdbcClient.create(dataSource);

        // Monthly partitions of pre_history from 2026-01 to 2027-06: 18 partitions.
        for (LocalDate month = LocalDate.of(2026, 1, 1); month.isBefore(LocalDate.of(2027, 7, 1)); month = month.plusMonths(1)) {
            jdbc.sql("CREATE TABLE pre_history_" + month.getYear() + "_" + "%02d".formatted(month.getMonthValue())
                    + " PARTITION OF pre_history FOR VALUES FROM ('" + month + "') TO ('" + month.plusMonths(1) + "')")
                    .update();
        }
        jdbc.sql("INSERT INTO pre_definition (tenant_id, id, key_name, version_num, status, name, definition_json, "
                        + "definition_hash, source_format, source_document, source_hash, node_count, source, effective_from, "
                        + "created_by, created_at, approved_by, approved_at) VALUES ('bank-a', ?, 'kyc-onboarding', 2, "
                        + "'ACTIVE', 'KYC onboarding', '{}'::jsonb, repeat('a', 64), 'BPMN_XML', '<definitions/>', "
                        + "repeat('b', 64), 4, 'IMPORT', ?, 'lan', ?, 'minh', ?)")
                .params(DEFINITION, ts(STARTED.minus(Duration.ofDays(400))), ts(STARTED.minus(Duration.ofDays(400))),
                        ts(STARTED.minus(Duration.ofDays(400))))
                .update();
        instance(I7, "{\"nationalId\":\"hmac:k1:9f2c\",\"applicant\":\"" + NATIONAL_ID + "\"}", STARTED, ENDED);
        instance(PAYOUT, "{}", STARTED, ENDED);
        history(PAYOUT, STARTED, "NODE_ENTERED", "credit-customer", null, null);
        history(PAYOUT, STARTED.plusSeconds(2), "ERROR_THROWN", "credit-customer", null, null);
        history(PAYOUT, STARTED.plusSeconds(2), "TOKEN_CANCELLED", "credit-customer", null, null);
        history(PAYOUT, STARTED.plusSeconds(3), "NODE_ENTERED", "tell-failure", null, null);
        history(PAYOUT, STARTED.plusSeconds(4), "NODE_COMPLETED", "tell-failure", null, null);
        history(PAYOUT, ENDED, "INSTANCE_ENDED", null, null, null);
        instance(RUNNING, "{}", STARTED, null);
        history(RUNNING, STARTED, "NODE_ENTERED", "approve", null, null);
        instance(OTHER, "{}", STARTED.minus(Duration.ofDays(300)), STARTED.minus(Duration.ofDays(299)));

        Instant t = STARTED;
        history(I7, t, "INSTANCE_STARTED", null, null, "svc-kyc");
        history(I7, t, "NODE_ENTERED", "verify-docs", null, null);
        history(I7, t.plusSeconds(60), "ASYNC_ATTEMPT_FAILED", "verify-docs", "AT100000000000000000000001", null);
        history(I7, t.plusSeconds(120), "ASYNC_STEP_SCHEDULED", "verify-docs", "AT200000000000000000000002", null);
        history(I7, t.plusSeconds(190), "NODE_COMPLETED", "verify-docs", "AT200000000000000000000002", "svc-kyc");
        history(I7, t.plusSeconds(190), "NODE_ENTERED", "assess-documents", null, null);
        history(I7, t.plusSeconds(400), "INCIDENT_RAISED", "assess-documents", null, null);
        history(I7, Instant.parse("2027-04-01T09:00:00Z"), "NODE_ENTERED", "approve", null, null);
        history(I7, ENDED, "INSTANCE_ENDED", null, null, "lan");
        // The other instance's history lives in other partitions and must never be read for I7.
        history(OTHER, STARTED.minus(Duration.ofDays(300)), "NODE_ENTERED", "verify-docs", null, null);
        jdbc.sql("INSERT INTO pre_incident (tenant_id, id, instance_id, definition_id, node_id, error_code, cause_summary, "
                        + "status, created_at) VALUES ('bank-a', 'INC0000000000000000000001', ?, ?, 'assess-documents', "
                        + "'AGENT_TIMEOUT', 'The agent did not answer in time', 'OPEN', ?)")
                .params(I7, DEFINITION, ts(t.plusSeconds(400))).update();
        jdbc.sql("ANALYZE").update();
    }

    @BeforeEach
    void service() {
        CallerResolver callers = new CallerResolver() {
            @Override
            public String currentCallerId() {
                return "operator-" + tenant.get();
            }

            @Override
            public Set<String> currentPermissions() {
                return permissions.get();
            }
        };
        JdbcProcessStore store = new JdbcProcessStore(dataSource);
        service = new InstanceActivityService(tenant::get, callers,
                new ProcessEngineTransactions(new DataSourceTransactionManager(dataSource)), store, store, 200);
    }

    @Test
    void PRE_027_step_interrupted_by_an_error_boundary_has_a_final_state_when_the_instance_ends() {
        // Given a loan payout whose credit step was cancelled by its error boundary, and an instance that then ended
        InstanceActivities activities = service.activities(PAYOUT, 200);

        // Then no step is left ACTIVE on the ended instance
        assertThat(activities.nodes()).extracting(InstanceActivities.Node::nodeId, InstanceActivities.Node::state)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("credit-customer", InstanceActivities.State.COMPLETED),
                        org.assertj.core.groups.Tuple.tuple("tell-failure", InstanceActivities.State.COMPLETED));
    }

    @Test
    void PRE_027_step_entered_and_not_left_is_active_while_the_instance_runs() {
        assertThat(service.activities(RUNNING, 200).nodes()).extracting(InstanceActivities.Node::state)
                .containsExactly(InstanceActivities.State.ACTIVE);
    }

    @Test
    void PRE_027_activities_bounded_by_instance_lifetime() {
        InstanceActivities activities = service.activities(I7, 200);

        assertThat(activities.nodes()).extracting(InstanceActivities.Node::nodeId)
                .containsExactly("verify-docs", "assess-documents", "approve");
        InstanceActivities.Node verify = activities.nodes().get(0);
        assertThat(verify.state()).isEqualTo(InstanceActivities.State.COMPLETED);
        assertThat(verify.attempts()).as("two attempts of the remote step").isEqualTo(2);
        assertThat(verify.duration()).isEqualTo(Duration.ofSeconds(190));
        assertThat(verify.actor()).isEqualTo("svc-kyc");
        assertThat(verify.enteredAt()).isEqualTo(STARTED);
        InstanceActivities.Node assess = activities.nodes().get(1);
        assertThat(assess.state()).isEqualTo(InstanceActivities.State.INCIDENT);
        assertThat(assess.incidentCode()).isEqualTo("AGENT_TIMEOUT");
        assertThat(assess.duration()).as("not completed").isNull();
        InstanceActivities.Node approve = activities.nodes().get(2);
        assertThat(approve.state()).as("entered, never closed, and the instance ended: closed at the end")
                .isEqualTo(InstanceActivities.State.COMPLETED);
        assertThat(approve.completedAt()).isEqualTo(ENDED);
        assertThat(approve.attempts()).isEqualTo(1);
        assertThat(activities.entries()).hasSize(9).extracting(InstanceActivities.Entry::occurredAt).isSorted();
        assertThat(activities.hasMore()).isFalse();
        assertThat(activities.toString()).as("no input value, masked or not, is in the answer")
                .doesNotContain(NATIONAL_ID).doesNotContain("hmac:");

        assertThat(scannedPartitions(ProcessListQueries.NODE_ACTIVITIES.sql(), List.of("bank-a", I7, STARTED, ENDED)))
                .as("2 partitions scanned of 18").containsExactly("pre_history_2027_03", "pre_history_2027_04");
        assertThat(scannedPartitions(ProcessListQueries.HISTORY_ENTRIES.sql(), List.of("bank-a", I7, STARTED, ENDED, 201)))
                .containsExactly("pre_history_2027_03", "pre_history_2027_04");

        assertThat(service.activities(I7, 200)).as("the same answer, so the same ETag and a 304 on repeat")
                .isEqualTo(activities);
    }

    @Test
    void PRE_027_the_entries_are_bounded_by_the_limit() {
        InstanceActivities activities = service.activities(I7, 4);

        assertThat(activities.entries()).hasSize(4);
        assertThat(activities.hasMore()).isTrue();
        assertThat(activities.nodes()).as("per step activity is complete whatever the limit").hasSize(3);
        assertThatThrownBy(() -> service.activities(I7, 0)).isInstanceOf(QueryInvalidException.class);
        assertThatThrownBy(() -> service.activities(I7, 201)).isInstanceOf(QueryInvalidException.class);
    }

    @Test
    void PRE_027_another_tenants_instance_is_not_found_and_reading_needs_the_permission() {
        tenant.set("bank-b");
        assertThatThrownBy(() -> service.activities(I7, 200)).isInstanceOf(InstanceNotFoundException.class)
                .extracting("code").isEqualTo("INSTANCE_NOT_FOUND");

        tenant.set("bank-a");
        permissions.set(Set.of("processes:start"));
        assertThatThrownBy(() -> service.activities(I7, 200)).isInstanceOf(PermissionDeniedException.class);
    }

    /** The pre_history partitions a plan of the statement reads, with the parameters bound. */
    private static Set<String> scannedPartitions(String sql, List<Object> params) {
        List<Object> bound = new ArrayList<>();
        for (Object param : params) {
            bound.add(param instanceof Instant instant ? ts(instant) : param);
        }
        String plan = String.join("\n", jdbc.sql("EXPLAIN " + sql).params(bound).query(String.class).list());
        Matcher partitions = Pattern.compile("pre_history_(\\d{4}_\\d{2}|default)").matcher(plan);
        Set<String> found = new TreeSet<>();
        while (partitions.find()) {
            found.add(partitions.group());
        }
        return found;
    }

    private static void instance(String id, String variables, Instant started, Instant ended) {
        jdbc.sql("INSERT INTO pre_instance (tenant_id, id, definition_id, definition_key, definition_ver, status, "
                        + "variables, started_by, started_at, ended_at) VALUES ('bank-a', ?, ?, 'kyc-onboarding', 2, "
                        + "'COMPLETED', ?::jsonb, 'svc-kyc', ?, ?)")
                .params(id, DEFINITION, variables, ts(started), ts(ended)).update();
    }

    private static int seq;

    private static void history(String instance, Instant at, String type, String node, String attempt, String actor) {
        seq++;
        jdbc.sql("INSERT INTO pre_history (tenant_id, occurred_at, id, instance_id, definition_id, event_type, node_id, "
                        + "attempt_id, actor_id, details) VALUES ('bank-a', ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)")
                .params(ts(at), "H" + "%025d".formatted(seq), instance, DEFINITION, type, node, attempt, actor,
                        "{\"nationalId\":\"hmac:k1:9f2c\"}")
                .update();
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
