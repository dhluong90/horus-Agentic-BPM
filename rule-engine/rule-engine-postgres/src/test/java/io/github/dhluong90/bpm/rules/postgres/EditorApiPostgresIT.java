package io.github.dhluong90.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.LifecycleAction;
import io.github.dhluong90.bpm.rules.api.Page;
import io.github.dhluong90.bpm.rules.api.PageRequest;
import io.github.dhluong90.bpm.rules.api.RuleSetNotFoundException;
import io.github.dhluong90.bpm.rules.api.RuleSetSummary;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.api.VersionHistoryEntry;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.api.VersionSummary;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.service.EngineSettings;
import io.github.dhluong90.bpm.rules.internal.service.RuleEngines;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The authoring API of #147 on PostgreSQL: list SQL (keyset, effective-version selection, latest change),
 * history rows of every transition, the rejection in one transaction, and tenant scope. One fresh database
 * per test; the clock moves from 1 to 10 December 2026.
 */
@Testcontainers
class EditorApiPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static final Instant DEC_01 = Instant.parse("2026-12-01T00:00:00Z");
    static final Instant DEC_10 = Instant.parse("2026-12-10T03:00:00Z");
    private static final AtomicInteger DATABASES = new AtomicInteger();

    final RuleEnginePostgresIT.Caller caller = new RuleEnginePostgresIT.Caller();
    final Movable clock = new Movable(DEC_01);
    JdbcClient jdbc;
    VersionService versions;

    @BeforeEach
    void setUp() {
        DataSource db = freshDatabase();
        new RuleEngineSchemaMigrator(db).migrate();
        RuleEngineTransactions transactions = new RuleEngineTransactions(new DataSourceTransactionManager(db));
        jdbc = JdbcClient.create(db);
        RuleEngines engines = PostgresRuleEngines.create(db, transactions, caller, caller, clock, EngineSettings.defaults());
        engines.cursorKeys().load();   // as the starter does at start-up, after the migrations
        versions = engines.versionService();
    }

    @Test
    void RLE_039_rule_sets_are_listed_per_tenant_by_name_with_a_cursor() {
        // Given bank-a: card-fee v1 published 1 Dec, v2 requested 10 Dec; fx-margin and kyc-risk drafts;
        // a future-dated v3 of card-fee approved but not yet effective; bank-b: aml-score
        publish("card-fee", null);
        imported("kyc-risk");
        imported("fx-margin");
        caller.as("bank-a", "lan");
        versions.importVersion("card-fee", ImportRequest.yaml(renamed("card-fee", "2027-01-01T00:00:00Z")));
        caller.as("bank-a", "lan");
        versions.requestPublish("card-fee", 2, WriteOptions.none());
        caller.as("bank-a", "minh");
        versions.approve("card-fee", 2, WriteOptions.none());
        caller.as("bank-a", "lan");
        versions.importVersion("card-fee", ImportRequest.yaml(renamed("card-fee", null)));
        clock.set(DEC_10);
        versions.requestPublish("card-fee", 3, WriteOptions.none());
        caller.as("bank-b", "lan");
        versions.importRuleSet(ImportRequest.yaml(renamed("aml-score", null)));

        // When bank-a reads two at a time
        caller.as("bank-a", "dan");
        Page<RuleSetSummary> first = versions.listRuleSets(new PageRequest(2, null));
        Page<RuleSetSummary> second = versions.listRuleSets(new PageRequest(2, first.nextCursor()));

        // Then v2 is published but effective only in January, so v1 is the published version now
        assertThat(first.items()).containsExactly(
                new RuleSetSummary("card-fee", 3, VersionStatus.PENDING_APPROVAL, 1, DEC_10),
                new RuleSetSummary("fx-margin", 1, VersionStatus.DRAFT, null, DEC_01));
        assertThat(second.items()).extracting(RuleSetSummary::ruleSet).containsExactly("kyc-risk");
        assertThat(second.nextCursor()).isNull();
        assertThat(versions.findRuleSet("card-fee")).contains(first.items().getFirst());

        // and bank-b sees only aml-score, and not bank-a's card-fee
        caller.as("bank-b", "dan");
        assertThat(versions.listRuleSets(PageRequest.first()).items()).extracting(RuleSetSummary::ruleSet)
                .containsExactly("aml-score");
        assertThat(versions.findRuleSet("card-fee")).isEmpty();
    }

    @Test
    void RLE_040_versions_are_listed_newest_first_with_their_actors() {
        publish("card-fee", null);
        caller.as("bank-a", "lan");
        versions.importVersion("card-fee", ImportRequest.yaml(renamed("card-fee", null)));
        versions.requestPublish("card-fee", 2, WriteOptions.none());
        versions.importVersion("card-fee", ImportRequest.yaml(renamed("card-fee", null)));

        caller.as("bank-a", "minh");
        Page<VersionSummary> first = versions.listVersions("card-fee", new PageRequest(2, null));
        Page<VersionSummary> second = versions.listVersions("card-fee", new PageRequest(2, first.nextCursor()));

        assertThat(first.items()).extracting(VersionSummary::version, VersionSummary::status,
                VersionSummary::createdBy, VersionSummary::requestedBy)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(3, VersionStatus.DRAFT, "lan", null),
                        org.assertj.core.groups.Tuple.tuple(2, VersionStatus.PENDING_APPROVAL, "lan", "lan"));
        assertThat(second.items()).extracting(VersionSummary::version, VersionSummary::approvedBy, VersionSummary::status)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, "minh", VersionStatus.PUBLISHED));
        assertThat(second.nextCursor()).isNull();

        caller.as("bank-b", "minh");
        assertThatThrownBy(() -> versions.listVersions("card-fee", PageRequest.first()))
                .isInstanceOf(RuleSetNotFoundException.class);
    }

    @Test
    void RLE_025_reject_returns_pending_version_to_draft_with_reason() {
        // Given card-fee v2 pending, requested by lan
        publish("card-fee", null);
        caller.as("bank-a", "lan");
        versions.importVersion("card-fee", ImportRequest.yaml(renamed("card-fee", null)));
        long lock = versions.requestPublish("card-fee", 2, WriteOptions.none()).lockVersion();
        long events = count("rle_decision_outbox");

        // When minh rejects with k-201 twice
        caller.as("bank-a", "minh");
        WriteOptions reject = new WriteOptions("k-201", lock, "R2 waiver needs pricing committee minutes");
        versions.reject("card-fee", 2, reject);
        versions.reject("card-fee", 2, reject);

        // Then the row is a draft with the request cleared, one history row and one event
        assertThat(jdbc.sql("""
                SELECT v.status || ' ' || v.lock_version || ' ' || coalesce(v.requested_by, '-') || ' '
                  || coalesce(v.requested_at::text, '-')
                FROM rle_version v JOIN rle_rule_set s ON s.tenant_id = v.tenant_id AND s.id = v.rule_set_id
                WHERE v.tenant_id = 'bank-a' AND s.name = 'card-fee' AND v.version_num = 2
                """).query(String.class).single()).isEqualTo("DRAFT " + (lock + 1) + " - -");
        assertThat(versions.history("card-fee", 2)).extracting(VersionHistoryEntry::action, VersionHistoryEntry::actor)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(LifecycleAction.CREATED, "lan"),
                        org.assertj.core.groups.Tuple.tuple(LifecycleAction.REQUESTED, "lan"),
                        org.assertj.core.groups.Tuple.tuple(LifecycleAction.REJECTED, "minh"));
        assertThat(jdbc.sql("SELECT reason || ' ' || from_status || ' ' || to_status FROM rle_version_history "
                        + "WHERE tenant_id = 'bank-a' AND action = 'REJECTED'").query(String.class).single())
                .isEqualTo("R2 waiver needs pricing committee minutes PENDING_APPROVAL DRAFT");
        assertThat(count("rle_decision_outbox")).isEqualTo(events + 1);
        assertThat(jdbc.sql("SELECT payload->>'rejectedBy' || ' ' || (payload->>'reason') FROM rle_decision_outbox "
                        + "WHERE tenant_id = 'bank-a' AND payload->>'eventType' = 'rules.version.rejected.v1'")
                .query(String.class).single()).isEqualTo("minh R2 waiver needs pricing committee minutes");
        assertThat(jdbc.sql("SELECT count(*) FROM rle_idempotency WHERE tenant_id = 'bank-a' AND operation = 'reject'")
                .query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void RLE_046_requester_withdraws_own_pending_request_to_draft() {
        // Given card-fee v1 published, v2 pending, requested by alice
        publish("card-fee", null);
        caller.as("bank-a", "alice");
        versions.importVersion("card-fee", ImportRequest.yaml(renamed("card-fee", null)));
        long lock = versions.requestPublish("card-fee", 2, WriteOptions.none()).lockVersion();
        long events = count("rle_decision_outbox");

        // When alice withdraws with k-301 twice
        WriteOptions withdraw = new WriteOptions("k-301", lock, "Wrong fee for GOLD");
        versions.withdraw("card-fee", 2, withdraw);
        versions.withdraw("card-fee", 2, withdraw);

        // Then the row is a draft with the request cleared, one WITHDRAWN row, one event and one idempotency record
        assertThat(jdbc.sql("""
                SELECT v.status || ' ' || v.lock_version || ' ' || coalesce(v.requested_by, '-') || ' '
                  || coalesce(v.requested_at::text, '-')
                FROM rle_version v JOIN rle_rule_set s ON s.tenant_id = v.tenant_id AND s.id = v.rule_set_id
                WHERE v.tenant_id = 'bank-a' AND s.name = 'card-fee' AND v.version_num = 2
                """).query(String.class).single()).isEqualTo("DRAFT " + (lock + 1) + " - -");
        assertThat(jdbc.sql("SELECT actor || ' ' || reason || ' ' || from_status || ' ' || to_status || ' ' || lock_version "
                        + "FROM rle_version_history WHERE tenant_id = 'bank-a' AND action = 'WITHDRAWN'")
                .query(String.class).single()).isEqualTo("alice Wrong fee for GOLD PENDING_APPROVAL DRAFT " + (lock + 1));
        assertThat(versions.history("card-fee", 2)).extracting(VersionHistoryEntry::action, VersionHistoryEntry::actor)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(LifecycleAction.CREATED, "alice"),
                        org.assertj.core.groups.Tuple.tuple(LifecycleAction.REQUESTED, "alice"),
                        org.assertj.core.groups.Tuple.tuple(LifecycleAction.WITHDRAWN, "alice"));
        assertThat(count("rle_decision_outbox")).isEqualTo(events + 1);
        assertThat(jdbc.sql("SELECT payload->>'withdrawnBy' || ' ' || (payload->>'reason') || ' ' || (payload->>'version') "
                        + "FROM rle_decision_outbox WHERE tenant_id = 'bank-a' "
                        + "AND payload->>'eventType' = 'rules.version.withdrawn.v1'")
                .query(String.class).single()).isEqualTo("alice Wrong fee for GOLD 2");
        assertThat(jdbc.sql("SELECT count(*) FROM rle_decision_outbox WHERE tenant_id = 'bank-a' "
                + "AND payload->>'eventType' = 'rules.version.rejected.v1'").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM rle_idempotency WHERE tenant_id = 'bank-a' AND operation = 'withdraw'")
                .query(Long.class).single()).isEqualTo(1L);
        // and v1 is still the version in effect
        assertThat(versions.findRuleSet("card-fee").orElseThrow().publishedVersion()).isEqualTo(1);

        // and bank-b cannot reach it
        caller.as("bank-b", "alice");
        assertThatThrownBy(() -> versions.withdraw("card-fee", 2, new WriteOptions("k-302", lock + 1, null)))
                .isInstanceOf(RuleSetNotFoundException.class);
    }

    /**
     * A requester's withdrawal and an approver's approval of the same pending version race: exactly one commits, and
     * the history and the outbox hold that one transition only. Each thread has its own identity.
     */
    @Test
    void RLE_046_a_withdrawal_racing_an_approval_lets_exactly_one_win() throws Exception {
        PerThreadCaller who = new PerThreadCaller();
        DataSource db = freshDatabase();
        new RuleEngineSchemaMigrator(db).migrate();
        RuleEngines engines = PostgresRuleEngines.create(db, new RuleEngineTransactions(new DataSourceTransactionManager(db)),
                who, who, clock, EngineSettings.defaults());
        engines.cursorKeys().load();
        VersionService service = engines.versionService();
        JdbcClient sql = JdbcClient.create(db);
        for (int round = 0; round < 10; round++) {
            String name = "race-" + round;
            who.set("bank-a", "alice");
            service.importRuleSet(ImportRequest.yaml(renamed(name, null)));
            long lock = service.requestPublish(name, 1, WriteOptions.none()).lockVersion();
            java.util.concurrent.CyclicBarrier together = new java.util.concurrent.CyclicBarrier(2);

            String withdrawn;
            String approved;
            try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var a = pool.submit(() -> outcome(who, "alice", together,
                        () -> service.withdraw(name, 1, new WriteOptions("w-" + name, lock, null))));
                var b = pool.submit(() -> outcome(who, "bob", together,
                        () -> service.approve(name, 1, new WriteOptions("a-" + name, lock, null))));
                withdrawn = a.get();
                approved = b.get();
            }

            assertThat(java.util.List.of(withdrawn, approved)).as(name).filteredOn("OK"::equals).hasSize(1);
            assertThat(java.util.List.of(withdrawn, approved)).as(name).filteredOn(o -> !o.equals("OK"))
                    .allSatisfy(o -> assertThat(o).isIn("VERSION_CONFLICT", "INVALID_STATE"));
            String status = sql.sql("""
                    SELECT v.status FROM rle_version v JOIN rle_rule_set s ON s.tenant_id = v.tenant_id AND s.id = v.rule_set_id
                    WHERE v.tenant_id = 'bank-a' AND s.name = :name""").param("name", name).query(String.class).single();
            assertThat(status).as(name).isEqualTo("OK".equals(withdrawn) ? "DRAFT" : "PUBLISHED");
            assertThat(sql.sql("""
                    SELECT count(*) FROM rle_version_history h JOIN rle_version v ON v.tenant_id = h.tenant_id AND v.id = h.version_id
                    JOIN rle_rule_set s ON s.tenant_id = v.tenant_id AND s.id = v.rule_set_id
                    WHERE h.tenant_id = 'bank-a' AND s.name = :name AND h.action IN ('WITHDRAWN', 'APPROVED')""")
                    .param("name", name).query(Long.class).single()).as(name).isEqualTo(1L);
            assertThat(sql.sql("""
                    SELECT count(*) FROM rle_decision_outbox WHERE tenant_id = 'bank-a' AND payload->>'ruleSet' = :name
                      AND payload->>'eventType' IN ('rules.version.withdrawn.v1', 'rules.version.published.v1')""")
                    .param("name", name).query(Long.class).single()).as(name).isEqualTo(1L);
        }
    }

    private static String outcome(PerThreadCaller who, String user, java.util.concurrent.CyclicBarrier together,
            Runnable command) throws Exception {
        who.set("bank-a", user);
        together.await();
        try {
            command.run();
            return "OK";
        } catch (io.github.dhluong90.bpm.rules.api.HorusBpmException e) {
            return e.code();
        }
    }

    /** A caller per thread, so two identities can act at the same moment. */
    static final class PerThreadCaller implements io.github.dhluong90.bpm.rules.spi.TenantResolver,
            io.github.dhluong90.bpm.rules.spi.CallerResolver {
        private final ThreadLocal<String[]> current = ThreadLocal.withInitial(() -> new String[] {"bank-a", "alice"});

        void set(String tenant, String user) {
            current.set(new String[] {tenant, user});
        }

        @Override
        public String currentTenantId() {
            return current.get()[0];
        }

        @Override
        public String currentCallerId() {
            return current.get()[1];
        }

        @Override
        public java.util.Set<String> currentPermissions() {
            return "bob".equals(current.get()[1])
                    ? java.util.Set.of("rules:read", "rules:approve")
                    : java.util.Set.of("rules:read", "rules:author");
        }
    }

    /**
     * JSONB reorders keys. A command answered from a row read back (approve, reject) must carry the same canonical
     * document as its idempotent replay, which is rebuilt from the recorded response.
     */
    @Test
    void RLE_014_a_replay_of_a_command_on_a_stored_version_returns_the_same_canonical_document() {
        caller.as("bank-a", "lan");
        io.github.dhluong90.bpm.rules.api.RuleSetVersion imported = versions.importRuleSet(ImportRequest.yaml(renamed("card-fee", null)));
        versions.requestPublish("card-fee", 1, WriteOptions.none());
        caller.as("bank-a", "minh");

        io.github.dhluong90.bpm.rules.api.RuleSetVersion approved = versions.approve("card-fee", 1, WriteOptions.idempotencyKey("k-301"));
        io.github.dhluong90.bpm.rules.api.RuleSetVersion replay = versions.approve("card-fee", 1, WriteOptions.idempotencyKey("k-301"));

        assertThat(approved.canonicalDocument()).isEqualTo(imported.canonicalDocument());
        assertThat(replay).isEqualTo(approved);
        assertThat(versions.find("card-fee", 1, false).orElseThrow().canonicalDocument())
                .isEqualTo(imported.canonicalDocument());
        assertThat(io.github.dhluong90.bpm.rules.internal.json.Hashes.sha256(approved.canonicalDocument()))
                .isEqualTo(approved.canonicalHash());
    }

    /** V4: the history is append-only in the database, not only by convention (security review, M2). */
    @Test
    void RLE_025_history_rows_cannot_be_updated_deleted_or_truncated() {
        // Given card-fee v2 rejected by minh: a REJECTED row exists
        publish("card-fee", null);
        caller.as("bank-a", "lan");
        versions.importVersion("card-fee", ImportRequest.yaml(renamed("card-fee", null)));
        versions.requestPublish("card-fee", 2, WriteOptions.none());
        caller.as("bank-a", "minh");
        versions.reject("card-fee", 2, new WriteOptions(null, null, "R2 waiver needs pricing committee minutes"));
        long rows = count("rle_version_history");

        // Then the application role can neither rewrite, remove nor truncate it
        for (String sql : new String[] {
                "UPDATE rle_version_history SET reason = 'approved after all', actor = 'lan' WHERE action = 'REJECTED'",
                "DELETE FROM rle_version_history WHERE action = 'REJECTED'",
                "TRUNCATE rle_version_history"}) {
            assertThatThrownBy(() -> jdbc.sql(sql).update()).as(sql)
                    .hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class)
                    .rootCause().hasMessageContaining("rle_version_history is append-only")
                    .satisfies(e -> assertThat(((org.postgresql.util.PSQLException) e).getSQLState()).isEqualTo("55000"));
        }
        assertThat(count("rle_version_history")).isEqualTo(rows);
        assertThat(jdbc.sql("SELECT actor || ' ' || reason FROM rle_version_history WHERE action = 'REJECTED'")
                .query(String.class).single()).isEqualTo("minh R2 waiver needs pricing committee minutes");
        // and inserting still works: the next transition appends
        caller.as("bank-a", "lan");
        versions.requestPublish("card-fee", 2, WriteOptions.none());
        assertThat(count("rle_version_history")).isEqualTo(rows + 1);
    }

    @Test
    void RLE_039_a_cursor_replayed_in_another_tenant_is_query_invalid() {
        publish("card-fee", null);
        imported("fx-margin");
        caller.as("bank-b", "lan");
        versions.importRuleSet(ImportRequest.yaml(renamed("aml-score", null)));
        versions.importRuleSet(ImportRequest.yaml(renamed("zz-limits", null)));
        caller.as("bank-a", "dan");
        String cursor = versions.listRuleSets(new PageRequest(1, null)).nextCursor();

        // The key is stored once in rle_engine_key, so the cursor works for its own tenant on any replica
        assertThat(versions.listRuleSets(new PageRequest(10, cursor)).items()).extracting(RuleSetSummary::ruleSet)
                .containsExactly("fx-margin");
        assertThat(jdbc.sql("SELECT count(*) FROM rle_engine_key WHERE name = 'cursor-hmac' AND octet_length(key_bytes) = 32")
                .query(Long.class).single()).isEqualTo(1L);
        caller.as("bank-b", "dan");
        assertThatThrownBy(() -> versions.listRuleSets(new PageRequest(10, cursor)))
                .isInstanceOf(io.github.dhluong90.bpm.rules.api.QueryInvalidException.class);
    }

    /** Replicas that start together race safely on the insert and all hold the same key (architecture review). */
    @Test
    void RLE_039_two_replicas_starting_together_create_the_cursor_key_once() throws Exception {
        DataSource db = freshDatabase();
        new RuleEngineSchemaMigrator(db).migrate();
        DataSourceTransactionManager manager = new DataSourceTransactionManager(db);
        RuleEngines replicaA = engines(db, manager);
        RuleEngines replicaB = engines(db, manager);
        java.util.concurrent.CyclicBarrier together = new java.util.concurrent.CyclicBarrier(2);

        // When both start at the same instant
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var a = pool.submit(() -> { together.await(); replicaA.cursorKeys().load(); return null; });
            var b = pool.submit(() -> { together.await(); replicaB.cursorKeys().load(); return null; });
            a.get();
            b.get();
        }

        // Then one row, and each replica accepts the other's cursors
        assertThat(JdbcClient.create(db).sql("SELECT count(*) FROM rle_engine_key").query(Long.class).single()).isEqualTo(1L);
        caller.as("bank-a", "lan");
        replicaA.versionService().importRuleSet(ImportRequest.yaml(renamed("card-fee", null)));
        replicaA.versionService().importRuleSet(ImportRequest.yaml(renamed("fx-margin", null)));
        String fromA = replicaA.versionService().listRuleSets(new PageRequest(1, null)).nextCursor();
        String fromB = replicaB.versionService().listRuleSets(new PageRequest(1, null)).nextCursor();
        assertThat(fromA).isEqualTo(fromB);
        assertThat(replicaB.versionService().listRuleSets(new PageRequest(1, fromA)).items())
                .extracting(RuleSetSummary::ruleSet).containsExactly("fx-margin");
    }

    /** A forged cursor costs one HMAC in memory: no connection, no statement, no write (architecture review). */
    @Test
    void RLE_039_a_forged_cursor_makes_no_database_call() {
        DataSource db = freshDatabase();
        new RuleEngineSchemaMigrator(db).migrate();
        java.util.concurrent.atomic.AtomicInteger connections = new java.util.concurrent.atomic.AtomicInteger();
        DataSource counting = new org.springframework.jdbc.datasource.DelegatingDataSource(db) {
            @Override
            public java.sql.Connection getConnection() throws java.sql.SQLException {
                connections.incrementAndGet();
                return super.getConnection();
            }
        };
        DataSourceTransactionManager manager = new DataSourceTransactionManager(counting);
        RuleEngines replica = engines(counting, manager);
        replica.cursorKeys().load();
        caller.as("bank-a", "lan");
        replica.versionService().importRuleSet(ImportRequest.yaml(renamed("card-fee", null)));
        replica.versionService().importRuleSet(ImportRequest.yaml(renamed("fx-margin", null)));
        String issued = replica.versionService().listRuleSets(new PageRequest(1, null)).nextCursor();
        // Keep the real signature, change the position it signs
        String forged = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("rule-sets:zz-forged".getBytes(StandardCharsets.UTF_8)) + issued.substring(issued.indexOf('.'));
        caller.as("bank-a", "dan");
        connections.set(0);

        for (String cursor : new String[] {forged, "not-a-cursor", issued + "x"}) {
            assertThatThrownBy(() -> replica.versionService().listRuleSets(new PageRequest(10, cursor))).as(cursor)
                    .isInstanceOf(io.github.dhluong90.bpm.rules.api.QueryInvalidException.class);
            assertThatThrownBy(() -> replica.versionService().listVersions("card-fee", new PageRequest(10, cursor)))
                    .isInstanceOf(io.github.dhluong90.bpm.rules.api.QueryInvalidException.class);
        }

        assertThat(connections.get()).isZero();
    }

    @Test
    void RLE_039_start_up_fails_naming_the_table_when_the_user_cannot_insert_the_key() {
        DataSource db = freshDatabase();
        new RuleEngineSchemaMigrator(db).migrate();
        JdbcClient admin = JdbcClient.create(db);
        admin.sql("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rle_reader') THEN "
                + "CREATE ROLE rle_reader LOGIN PASSWORD 'reader'; END IF; END $$").update();
        admin.sql("GRANT SELECT ON ALL TABLES IN SCHEMA public TO rle_reader").update();
        admin.sql("GRANT USAGE ON SCHEMA public TO rle_reader").update();
        org.postgresql.ds.PGSimpleDataSource reader = new org.postgresql.ds.PGSimpleDataSource();
        reader.setUrl(((org.postgresql.ds.PGSimpleDataSource) db).getUrl());
        reader.setUser("rle_reader");
        reader.setPassword("reader");
        RuleEngines replica = engines(reader, new DataSourceTransactionManager(reader));

        assertThatThrownBy(() -> replica.cursorKeys().load())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rle_engine_key")
                .hasMessageContaining("INSERT");
        assertThat(admin.sql("SELECT count(*) FROM rle_engine_key").query(Long.class).single()).isZero();
    }

    @Test
    void RLE_039_a_refresh_swaps_in_a_changed_key_and_keeps_the_previous_one() {
        DataSource db = freshDatabase();
        new RuleEngineSchemaMigrator(db).migrate();
        DataSourceTransactionManager manager = new DataSourceTransactionManager(db);
        RuleEngines replicaA = engines(db, manager);
        replicaA.cursorKeys().load();
        caller.as("bank-a", "lan");
        replicaA.versionService().importRuleSet(ImportRequest.yaml(renamed("card-fee", null)));
        replicaA.versionService().importRuleSet(ImportRequest.yaml(renamed("fx-margin", null)));
        String oldCursor = replicaA.versionService().listRuleSets(new PageRequest(1, null)).nextCursor();

        // When the key row changes and replica B starts with the new key
        JdbcClient admin = JdbcClient.create(db);
        admin.sql("UPDATE rle_engine_key SET key_bytes = decode(repeat('ab', 32), 'hex') WHERE name = 'cursor-hmac'").update();
        RuleEngines replicaB = engines(db, manager);
        replicaB.cursorKeys().load();
        String newCursor = replicaB.versionService().listRuleSets(new PageRequest(1, null)).nextCursor();
        assertThatThrownBy(() -> replicaA.versionService().listRuleSets(new PageRequest(1, newCursor)))
                .as("before the refresh, A does not know the new key")
                .isInstanceOf(io.github.dhluong90.bpm.rules.api.QueryInvalidException.class);

        // Then after A's scheduled refresh: new cursors verify, and the previous key still verifies old ones
        replicaA.cursorKeys().refresh();
        assertThat(replicaA.versionService().listRuleSets(new PageRequest(1, newCursor)).items())
                .extracting(RuleSetSummary::ruleSet).containsExactly("fx-margin");
        assertThat(replicaA.versionService().listRuleSets(new PageRequest(1, oldCursor)).items())
                .extracting(RuleSetSummary::ruleSet).containsExactly("fx-margin");
    }

    @Test
    void RLE_039_the_first_list_read_works_inside_a_read_only_host_transaction() {
        DataSource db = freshDatabase();
        new RuleEngineSchemaMigrator(db).migrate();
        DataSourceTransactionManager manager = new DataSourceTransactionManager(db);
        VersionService service = replica(db, manager);
        caller.as("bank-a", "lan");
        service.importRuleSet(ImportRequest.yaml(renamed("card-fee", null)));
        service.importRuleSet(ImportRequest.yaml(renamed("fx-margin", null)));
        org.springframework.transaction.support.TransactionTemplate readOnly =
                new org.springframework.transaction.support.TransactionTemplate(manager);
        readOnly.setReadOnly(true);

        Page<RuleSetSummary> first = readOnly.execute(status -> service.listRuleSets(new PageRequest(1, null)));
        Page<RuleSetSummary> second = readOnly.execute(status -> service.listRuleSets(new PageRequest(1, first.nextCursor())));

        assertThat(first.items()).extracting(RuleSetSummary::ruleSet).containsExactly("card-fee");
        assertThat(second.items()).extracting(RuleSetSummary::ruleSet).containsExactly("fx-margin");
        assertThat(JdbcClient.create(db).sql("SELECT count(*) FROM rle_engine_key").query(Long.class).single()).isEqualTo(1L);
    }

    VersionService replica(DataSource db, DataSourceTransactionManager manager) {
        RuleEngines engines = engines(db, manager);
        engines.cursorKeys().load();
        return engines.versionService();
    }

    RuleEngines engines(DataSource db, DataSourceTransactionManager manager) {
        return PostgresRuleEngines.create(db, new RuleEngineTransactions(manager), caller, caller, clock,
                EngineSettings.defaults());
    }

    /** V3 backfills one CREATED row per version that existed before it, so their history starts at creation. */
    @Test
    void RLE_025_versions_created_before_the_history_table_get_their_created_row() {
        // Given a database at V2 with card-fee v1 by lan (published) and v2 written before authors were recorded
        DataSource db = freshDatabase();
        org.flywaydb.core.Flyway.configure().dataSource(db).table("rle_schema_history")
                .locations(RuleEngineSchemaMigrator.MIGRATION_LOCATION).baselineOnMigrate(true).baselineVersion("0")
                .target("2").load().migrate();
        JdbcClient old = JdbcClient.create(db);
        old.sql("INSERT INTO rle_rule_set (tenant_id, id, name, created_at) VALUES "
                + "('bank-a', '01KD00000000000000000RS001', 'card-fee', '2026-11-01T00:00:00Z')").update();
        String insert = """
                INSERT INTO rle_version (tenant_id, id, rule_set_id, version_num, status, hit_policy, canonical_json,
                  canonical_hash, source_format, source_document, source_hash, lock_version, requested_by, approved_by,
                  created_by, created_at)
                VALUES ('bank-a', :id, '01KD00000000000000000RS001', :num, :status, 'FIRST', '{}', :hash, 'YAML', '',
                  :hash, :lock, :requestedBy, :approvedBy, :createdBy, CAST(:at AS timestamptz))
                """;
        String hash = "0".repeat(64);
        old.sql(insert).param("id", "01KD00000000000000000VS001").param("num", 1).param("status", "PUBLISHED")
                .param("hash", hash).param("lock", 2).param("requestedBy", "lan").param("approvedBy", "minh")
                .param("createdBy", "lan").param("at", "2026-11-01T00:00:00Z").update();
        old.sql(insert).param("id", "01KD00000000000000000VS002").param("num", 2).param("status", "DRAFT")
                .param("hash", hash).param("lock", 0).param("requestedBy", null).param("approvedBy", null)
                .param("createdBy", null).param("at", "2026-11-05T00:00:00Z").update();

        // When V3 runs
        new RuleEngineSchemaMigrator(db).migrate();

        // Then each version has exactly one CREATED row: actor the author, or system when none was recorded
        assertThat(old.sql("""
                SELECT string_agg(version_id || ' ' || action || ' ' || to_status || ' ' || actor || ' ' || lock_version
                  || ' ' || to_char(occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD'), ', ' ORDER BY version_id)
                FROM rle_version_history WHERE tenant_id = 'bank-a'
                """).query(String.class).single()).isEqualTo(
                "01KD00000000000000000VS001 CREATED DRAFT lan 0 2026-11-01, "
                        + "01KD00000000000000000VS002 CREATED DRAFT system 0 2026-11-05");
        assertThat(old.sql("SELECT count(*) FROM rle_version_history WHERE from_status IS NOT NULL")
                .query(Long.class).single()).isZero();
    }

    @Test
    void RLE_042_money_output_without_currency_is_refused_at_import() {
        caller.as("bank-a", "lan");
        byte[] bad = new String(RuleEnginePostgresIT.cardFee(), StandardCharsets.UTF_8)
                .replace("then: { fee: \"10000 VND\" }", "then: { fee: \"30000\" }").getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> versions.importRuleSet(ImportRequest.yaml(bad)))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.violations())
                        .singleElement().satisfies(v -> assertThat(v.ruleId() + "/" + v.column()).isEqualTo("R2/fee")));
        assertThat(count("rle_rule_set") + count("rle_version") + count("rle_version_history")).isZero();
    }

    void publish(String name, String effectiveFrom) {
        caller.as("bank-a", "lan");
        versions.importRuleSet(ImportRequest.yaml(renamed(name, effectiveFrom)));
        versions.requestPublish(name, 1, WriteOptions.none());
        caller.as("bank-a", "minh");
        versions.approve(name, 1, WriteOptions.none());
    }

    void imported(String name) {
        caller.as("bank-a", "lan");
        versions.importRuleSet(ImportRequest.yaml(renamed(name, null)));
    }

    static byte[] renamed(String name, String effectiveFrom) {
        String yaml = new String(RuleEnginePostgresIT.cardFee(), StandardCharsets.UTF_8)
                .replace("ruleSet: card-fee", "ruleSet: " + name);
        if (effectiveFrom != null) {
            yaml = yaml.replace("hitPolicy: first", "hitPolicy: first\neffectiveFrom: \"" + effectiveFrom + "\"");
        }
        return yaml.getBytes(StandardCharsets.UTF_8);
    }

    long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    static DataSource freshDatabase() {
        String name = "editor_" + DATABASES.incrementAndGet();
        JdbcClient.create(dataSource(POSTGRES.getDatabaseName())).sql("CREATE DATABASE " + name).update();
        return dataSource(name);
    }

    static DataSource dataSource(String database) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database));
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }

    /** A clock the test moves between steps. */
    static final class Movable extends Clock {
        private volatile Instant now;

        Movable(Instant start) {
            this.now = start;
        }

        void set(Instant instant) {
            this.now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
