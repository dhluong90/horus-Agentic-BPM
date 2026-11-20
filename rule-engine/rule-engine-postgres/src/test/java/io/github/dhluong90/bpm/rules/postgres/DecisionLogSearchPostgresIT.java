package io.github.dhluong90.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.DecisionLogEntry;
import io.github.dhluong90.bpm.rules.api.DecisionSearch;
import io.github.dhluong90.bpm.rules.api.Money;
import io.github.dhluong90.bpm.rules.api.Page;
import io.github.dhluong90.bpm.rules.api.PageRequest;
import io.github.dhluong90.bpm.rules.api.QueryInvalidException;
import io.github.dhluong90.bpm.rules.internal.json.Ulid;
import io.github.dhluong90.bpm.rules.internal.service.EngineSettings;
import io.github.dhluong90.bpm.rules.internal.service.RuleEngines;
import io.github.dhluong90.bpm.rules.internal.store.DecisionRecord;
import io.github.dhluong90.bpm.rules.spi.CallerResolver;
import io.github.dhluong90.bpm.rules.spi.TenantResolver;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * RLE-029 on PostgreSQL: one rule set over at most 31 days, newest first, keyset pages of 200, tenant first, masked
 * inputs as stored, at most two monthly partitions in the plan, and nothing written.
 */
@Testcontainers
class DecisionLogSearchPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(TestImages.postgres());

    static final Instant JAN = Instant.parse("2027-01-01T00:00:00Z");
    static final Instant FEB = Instant.parse("2027-02-01T00:00:00Z");
    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static final Pattern RELATION = Pattern.compile("\"Relation Name\":\\s*\"(rle_decision[a-z0-9_]*)\"");

    final List<String> statements = new CopyOnWriteArrayList<>();
    final Auditor auditor = new Auditor();
    DataSource plain;
    DataSource db;
    TransactionTemplate host;
    RuleEngines engines;
    JdbcDecisionLog log;

    @BeforeEach
    void setUp() {
        plain = freshDatabase();
        db = new RuleEnginePostgresIT.SqlCapturingDataSource(plain, statements);
        new RuleEngineSchemaMigrator(db).migrate();
        DataSourceTransactionManager manager = new DataSourceTransactionManager(db);
        RuleEngineTransactions transactions = new RuleEngineTransactions(manager);
        new DecisionLogPartitions(db, transactions).ensureAhead(Instant.parse("2027-01-26T00:00:00Z"),
                Duration.ofDays(7));
        host = new TransactionTemplate(manager);
        engines = PostgresRuleEngines.create(db, transactions, auditor, auditor,
                Clock.fixed(Instant.parse("2027-02-10T00:00:00Z"), ZoneOffset.UTC), EngineSettings.defaults());
        engines.cursorKeys().load();
        log = new JdbcDecisionLog(plain);
    }

    @Test
    void RLE_029_decision_search_by_rule_set_and_period_is_bounded() {
        // Given 250 card-fee and 40 kyc-risk decisions of bank-a in January 2027, and 10 card-fee decisions of bank-b
        List<String> cardFee = logDecisions("bank-a", "card-fee", 250);
        logDecisions("bank-a", "kyc-risk", 40);
        logDecisions("bank-b", "card-fee", 10);
        auditor.as("bank-a", "dan");
        long rowsBefore = count("SELECT count(*) FROM rle_decision");
        statements.clear();

        // When dan reads the first page and follows the cursor
        DecisionSearch january = DecisionSearch.of("card-fee", JAN, FEB);
        Page<DecisionLogEntry> first = engines.decisionLogQuery().search(january, PageRequest.first());
        Page<DecisionLogEntry> second = engines.decisionLogQuery().search(january,
                new PageRequest(null, first.nextCursor()));

        // Then 200 newest first with a cursor, then 50 without; no kyc-risk or bank-b row; inputs stay hashed
        assertThat(first.items()).hasSize(200);
        assertThat(first.nextCursor()).isNotNull();
        assertThat(second.items()).hasSize(50);
        assertThat(second.nextCursor()).isNull();
        List<String> seen = new ArrayList<>();
        first.items().forEach(e -> seen.add(e.decisionId()));
        second.items().forEach(e -> seen.add(e.decisionId()));
        assertThat(seen).isEqualTo(cardFee.reversed());
        assertThat(first.items()).allSatisfy(e -> {
            assertThat(e.ruleSet()).isEqualTo("card-fee");
            assertThat(e.inputs().get("nationalId")).asString().matches("hmac:[0-9a-f]{64}");
            assertThat(e.inputs()).containsEntry("tier", "SILVER").containsEntry("txCount", 5L);
            assertThat(e.outputs()).containsEntry("fee", Money.ofMinor("VND", 10000));
            assertThat(e.outcome()).isEqualTo(DecisionLogEntry.Outcome.MATCHED);
        });

        // And the search wrote nothing
        assertThat(statements).noneMatch(sql -> sql.strip().toUpperCase().matches("(?s)^(INSERT|UPDATE|DELETE).*"));
        assertThat(count("SELECT count(*) FROM rle_decision")).isEqualTo(rowsBefore);

        // And the query plan reads at most two monthly partitions and never the default
        assertThat(partitionsRead(JAN, FEB, null)).containsExactly("rle_decision_2027_01");
        Instant midJanuary = Instant.parse("2027-01-15T00:00:00Z");
        assertThat(partitionsRead(midJanuary, midJanuary.plus(Duration.ofDays(31)), null))
                .containsExactly("rle_decision_2027_01", "rle_decision_2027_02");
        DecisionLogEntry last = first.items().getLast();
        assertThat(partitionsRead(midJanuary, midJanuary.plus(Duration.ofDays(31)), last))
                .hasSizeLessThanOrEqualTo(2).doesNotContain("rle_decision_default");
    }

    @Test
    void RLE_029_eval_clock_and_pinned_are_read_back_from_the_row() {
        // Given a card-fee decision of 20 January 2027 back-dated to 31 December 2026, and one pinned to version 1
        Instant at = Instant.parse("2027-01-20T10:00:00Z");
        Instant december = Instant.parse("2026-12-31T10:00:00Z");
        String backDated = Ulid.next(at);
        String pinned = Ulid.next(at.plusSeconds(1));
        log.append(row("bank-a", backDated, at, december, false));
        log.append(row("bank-a", pinned, at.plusSeconds(1), at.plusSeconds(1), true));
        auditor.as("bank-a", "dan");

        // When
        List<DecisionLogEntry> entries = engines.decisionLogQuery()
                .search(DecisionSearch.of("card-fee", JAN, FEB), PageRequest.first()).items();

        // Then eval_clock, created_at and pinned come back from the row
        assertThat(entries).extracting(DecisionLogEntry::decisionId).containsExactly(pinned, backDated);
        assertThat(entries.get(1).evalClock()).isEqualTo(december);
        assertThat(entries.get(1).evaluatedAt()).isEqualTo(at);
        assertThat(entries.get(1).clockChosen()).isTrue();
        assertThat(entries.get(1).pinned()).isFalse();
        assertThat(entries.get(0).clockChosen()).isFalse();
        assertThat(entries.get(0).pinned()).isTrue();
    }

    private static DecisionRecord row(String tenant, String id, Instant at, Instant evalClock, boolean pinned) {
        return new DecisionRecord(tenant, id, at, "card-fee", 1, "FIRST", "OK", null,
                "{\"tier\":\"SILVER\",\"txCount\":5}", "{\"fee\":{\"currency\":\"VND\",\"amount\":10000}}",
                "[\"R2\"]", "teller-01", null, evalClock, 31, pinned);
    }

    @Test
    void RLE_029_failure_paths_range_rule_set_and_tenant() {
        // Given
        logDecisions("bank-a", "card-fee", 25);
        List<String> bankB = logDecisions("bank-b", "card-fee", 10);
        auditor.as("bank-a", "dan");

        // Then a range of 32 days and a missing rule set are QUERY_INVALID naming the parameter
        assertThatThrownBy(() -> engines.decisionLogQuery().search(
                DecisionSearch.of("card-fee", JAN, Instant.parse("2027-02-02T00:00:00Z")), null))
                .isInstanceOf(QueryInvalidException.class).hasMessageContaining("from and to");
        assertThatThrownBy(() -> engines.decisionLogQuery().search(DecisionSearch.of(null, JAN, FEB), null))
                .isInstanceOf(QueryInvalidException.class).hasMessageContaining("ruleSet");

        // And a bank-b caller gets its own 10 decisions only
        auditor.as("bank-b", "dan-b");
        assertThat(engines.decisionLogQuery().search(DecisionSearch.of("card-fee", JAN, FEB), null).items())
                .extracting(DecisionLogEntry::decisionId).isEqualTo(bankB.reversed());
    }

    @Test
    void RLE_029_the_statement_timeout_applies_to_the_search_only_and_the_host_keeps_its_own() {
        // Given a host transaction with its own statement timeout
        logDecisions("bank-a", "card-fee", 3);
        auditor.as("bank-a", "dan");

        // When the search joins it
        String after = host.execute(status -> {
            JdbcClient.create(db).sql("SET LOCAL statement_timeout = '7s'").update();
            engines.decisionLogQuery().search(DecisionSearch.of("card-fee", JAN, FEB), null);
            return JdbcClient.create(db).sql("SHOW statement_timeout").query(String.class).single();
        });

        // Then
        assertThat(after).isEqualTo("7s");
        assertThat(statements).anyMatch(sql -> sql.contains("set_config('statement_timeout'"));
    }

    /** The relations the plan of one search page reads, from EXPLAIN with the same statement and values. */
    private Set<String> partitionsRead(Instant from, Instant to, DecisionLogEntry after) {
        var explain = JdbcClient.create(plain).sql("EXPLAIN (FORMAT JSON) " + JdbcDecisionLog.searchSql(after != null))
                .param("tenant", "bank-a").param("ruleSet", "card-fee")
                .param("from", Timestamp.from(from)).param("to", Timestamp.from(to)).param("limit", 201);
        if (after != null) {
            explain = explain.param("afterAt", Timestamp.from(after.loggedAt())).param("afterId", after.decisionId());
        }
        String plan = explain.query(String.class).single();
        Set<String> relations = new TreeSet<>();
        Matcher m = RELATION.matcher(plan);
        while (m.find()) {
            relations.add(m.group(1));
        }
        return relations;
    }

    /** Logs {@code count} decisions spread over January 2027, oldest first; returns their ids in that order. */
    private List<String> logDecisions(String tenant, String ruleSet, int count) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Instant at = JAN.plus(Duration.ofMinutes(10L * i + ruleSet.length() + tenant.length()));
            String id = Ulid.next(at);
            ids.add(id);
            log.append(new DecisionRecord(tenant, id, at, ruleSet, 1, "FIRST", "OK", null,
                    "{\"tier\":\"SILVER\",\"txCount\":5,\"nationalId\":\"hmac:" + "9f2c41d07a5be3c8".repeat(4) + "\"}",
                    "{\"fee\":{\"currency\":\"VND\",\"amount\":10000}}", "[\"R2\"]", "teller-01", null, at, 31));
        }
        return ids;
    }

    private long count(String sql) {
        return JdbcClient.create(plain).sql(sql).query(Long.class).single();
    }

    private static DataSource freshDatabase() {
        String name = "rle_search_" + DATABASES.incrementAndGet();
        JdbcClient.create(dataSourceFor(POSTGRES.getDatabaseName())).sql("CREATE DATABASE " + name).update();
        return dataSourceFor(name);
    }

    private static DataSource dataSourceFor(String database) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setServerNames(new String[] {POSTGRES.getHost()});
        ds.setPortNumbers(new int[] {POSTGRES.getFirstMappedPort()});
        ds.setDatabaseName(database);
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }

    /** An auditor holding rules:log:read in the tenant it is switched to. */
    static final class Auditor implements TenantResolver, CallerResolver {
        private volatile String tenant = "bank-a";
        private volatile String user = "dan";

        void as(String tenant, String user) {
            this.tenant = tenant;
            this.user = user;
        }

        @Override
        public String currentTenantId() {
            return tenant;
        }

        @Override
        public String currentCallerId() {
            return user;
        }

        @Override
        public Set<String> currentPermissions() {
            return Set.of("rules:log:read");
        }
    }
}
