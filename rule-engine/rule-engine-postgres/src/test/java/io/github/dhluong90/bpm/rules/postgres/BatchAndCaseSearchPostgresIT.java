package io.github.dhluong90.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.rules.api.BatchRequest;
import io.github.dhluong90.bpm.rules.api.BatchResult;
import io.github.dhluong90.bpm.rules.api.CorrelationSearch;
import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionLogEntry;
import io.github.dhluong90.bpm.rules.api.DecisionRequest;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.InputSearch;
import io.github.dhluong90.bpm.rules.api.MaskingKey;
import io.github.dhluong90.bpm.rules.api.Page;
import io.github.dhluong90.bpm.rules.api.PageRequest;
import io.github.dhluong90.bpm.rules.api.QueryInvalidException;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.json.Ulid;
import io.github.dhluong90.bpm.rules.internal.service.EngineSettings;
import io.github.dhluong90.bpm.rules.internal.service.RuleEngines;
import io.github.dhluong90.bpm.rules.internal.store.DecisionRecord;
import io.github.dhluong90.bpm.rules.spi.CallerResolver;
import io.github.dhluong90.bpm.rules.spi.MaskingPolicy;
import io.github.dhluong90.bpm.rules.spi.TenantResolver;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Batch evaluation and the case searches on PostgreSQL (RLE-067 to RLE-071): rows commit and roll back with the host
 * transaction, the migration V20 index serves the correlation search inside at most two monthly partitions, and a
 * number or a money value is found by exact value through JSONB.
 */
@Testcontainers
class BatchAndCaseSearchPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    static final Instant NOW = Instant.parse("2027-01-15T02:00:00Z");
    static final Instant JAN = Instant.parse("2027-01-01T00:00:00Z");
    static final Instant FEB = Instant.parse("2027-02-01T00:00:00Z");
    static final String APPLICATION = "LA-2027-000123";
    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static final Pattern RELATION = Pattern.compile("\"Relation Name\":\\s*\"(rle_decision[a-z0-9_]*)\"");
    private static final Pattern INDEX = Pattern.compile("\"Index Name\":\\s*\"([a-z0-9_]+)\"");

    static final String CARD_FEE = """
            schemaVersion: 1
            ruleSet: card-fee
            hitPolicy: first
            inputs:
              - { name: tier,    type: string }
              - { name: txCount, type: integer }
            outputs:
              - { name: fee, type: money }
            rules:
              - { id: R1, when: { tier: "== 'GOLD'" }, then: { fee: "0 VND" } }
              - { id: R2, when: { txCount: ">= 3" }, then: { fee: "10000 VND" } }
              - { id: R3, when: {}, then: { fee: "50000 VND" } }
            """;

    static final String LOAN_PRICING = """
            schemaVersion: 1
            ruleSet: loan-pricing
            hitPolicy: first
            inputs:
              - { name: productCode, type: string, mask: false }
              - { name: amount, type: money }
              - { name: marginBps, type: decimal }
              - { name: nationalId, type: string, mask: true }
            outputs:
              - { name: rate, type: decimal }
            rules:
              - { id: R1, when: {}, then: { rate: 0.085 } }
            """;

    final Caller caller = new Caller();
    DataSource plain;
    DataSource db;
    TransactionTemplate host;
    RuleEngines engines;

    @BeforeEach
    void setUp() {
        plain = freshDatabase();
        db = plain;
        new RuleEngineSchemaMigrator(db).migrate();
        DataSourceTransactionManager manager = new DataSourceTransactionManager(db);
        RuleEngineTransactions transactions = new RuleEngineTransactions(manager);
        new DecisionLogPartitions(db, transactions).ensureAhead(Instant.parse("2027-01-26T00:00:00Z"),
                Duration.ofDays(7));
        host = new TransactionTemplate(manager);
        engines = PostgresRuleEngines.create(db, transactions, caller, caller, Clock.fixed(NOW, ZoneOffset.UTC),
                EngineSettings.defaults(), MaskingPolicy.withKey(MaskingKey.of(new byte[32])));
        engines.cursorKeys().load();
    }

    void publish(String tenant, String yaml, String name) {
        caller.as(tenant, "alice", Set.of("rules:author", "rules:approve"));
        engines.versionService().importRuleSet(ImportRequest.yaml(yaml.getBytes(StandardCharsets.UTF_8)));
        long lock = engines.versionService().requestPublish(name, 1, WriteOptions.none()).lockVersion();
        caller.as(tenant, "bob", Set.of("rules:author", "rules:approve"));
        try {
            engines.versionService().approve(name, 1, new WriteOptions(null, lock, null));
        } catch (io.github.dhluong90.bpm.rules.api.WarningsNotAcknowledgedException shown) {
            engines.versionService().approve(name, 1, new WriteOptions(null, lock, null).acknowledging(shown.warnings()
                    .stream().map(w -> new io.github.dhluong90.bpm.rules.api.AcknowledgedWarning(w.code(), w.input()))
                    .toList()));
        }
    }

    long rows(String tenant) {
        return JdbcClient.create(plain).sql("SELECT count(*) FROM rle_decision WHERE tenant_id = :tenant")
                .param("tenant", tenant).query(Long.class).single();
    }

    static List<BatchRequest.Item> cardFeeItems() {
        return List.of(
                new BatchRequest.Item(DecisionInput.of(Map.of("tier", "GOLD", "txCount", 5)), "eod-2027-01-31-0000"),
                new BatchRequest.Item(DecisionInput.of(Map.of("tier", "SILVER", "txCount", 4)), "eod-2027-01-31-0001"),
                new BatchRequest.Item(DecisionInput.of(Map.of("tier", "SILVER", "txCount", "four")),
                        "eod-2027-01-31-0002"),
                new BatchRequest.Item(DecisionInput.of(Map.of("tier", "STANDARD", "txCount", 0)),
                        "eod-2027-01-31-0003"));
    }

    @Test
    void RLE_069_batch_rows_commit_together_roll_back_with_the_host_and_replay_writes_nothing() {
        // Given card-fee v1 published in bank-a and svc-eod holding rules:evaluate
        publish("bank-a", CARD_FEE, "card-fee");
        caller.as("bank-a", "svc-eod", Set.of("rules:evaluate"));
        BatchRequest request = BatchRequest.of("card-fee", cardFeeItems()).withIdempotencyKey("k-701");

        // When a Java host calls evaluateBatch inside @Transactional and rolls back
        assertThatThrownBy(() -> host.executeWithoutResult(status -> {
            engines.ruleEngine().evaluateBatch(request);
            throw new IllegalStateException("host business rule failed");
        })).isInstanceOf(IllegalStateException.class);

        // Then 0 rows and no idempotency record remain
        assertThat(rows("bank-a")).isZero();
        assertThat(JdbcClient.create(plain).sql("SELECT count(*) FROM rle_idempotency WHERE operation = 'evaluate-batch'")
                .query(Long.class).single()).isZero();

        // When the host transaction commits
        BatchResult committed = host.execute(status -> engines.ruleEngine().evaluateBatch(request));

        // Then the three decided items' rows are there, with their own ids and correlation ids; the invalid one has none
        assertThat(rows("bank-a")).isEqualTo(3);
        assertThat(JdbcClient.create(plain).sql("SELECT correlation_id FROM rle_decision WHERE tenant_id = 'bank-a'"
                + " ORDER BY correlation_id").query(String.class).list())
                .containsExactly("eod-2027-01-31-0000", "eod-2027-01-31-0001", "eod-2027-01-31-0003");
        assertThat(committed.items().get(2).code()).isEqualTo("INPUT_INVALID");
        assertThat(committed.succeeded()).isEqualTo(3);

        // And a repeat with the same key returns the same body, decision ids included, and writes no row
        BatchResult repeat = engines.ruleEngine().evaluateBatch(request);
        assertThat(repeat).isEqualTo(committed);
        assertThat(rows("bank-a")).isEqualTo(3);

        // And a caller of bank-b is served from bank-b, which has no card-fee
        caller.as("bank-b", "svc-eod", Set.of("rules:evaluate"));
        assertThatThrownBy(() -> engines.ruleEngine().evaluateBatch(request))
                .isInstanceOf(io.github.dhluong90.bpm.rules.api.RuleSetNotFoundException.class);
        assertThat(rows("bank-b")).isZero();
    }

    @Test
    void RLE_070_the_case_search_uses_the_tenant_correlation_index_inside_the_partitions_of_the_period() {
        // Given decisions of bank-a in January and February, one of bank-b with the same reference, and others
        JdbcDecisionLog log = new JdbcDecisionLog(plain);
        List<String> january = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Instant at = Instant.parse("2027-01-10T09:00:00Z").plusSeconds(60L * i);
            String id = Ulid.next(at);
            january.add(id);
            log.append(row("bank-a", id, at, i % 2 == 0 ? "card-fee" : "kyc-risk", APPLICATION));
        }
        Instant february = Instant.parse("2027-02-05T09:00:00Z");
        log.append(row("bank-a", Ulid.next(february), february, "card-fee", APPLICATION));
        Instant at = Instant.parse("2027-01-10T09:30:00Z");
        log.append(row("bank-b", Ulid.next(at), at, "card-fee", APPLICATION));
        for (int i = 0; i < 50; i++) {
            Instant other = Instant.parse("2027-01-20T00:00:00Z").plusSeconds(i);
            log.append(row("bank-a", Ulid.next(other), other, "card-fee", "LA-2027-" + (300000 + i)));
        }
        long before = rows("bank-a");

        // When dan searches January
        caller.as("bank-a", "dan", Set.of("rules:log:read"));
        Page<DecisionLogEntry> page = engines.decisionLogQuery()
                .searchByCorrelationId(CorrelationSearch.of(APPLICATION, JAN, FEB), null);

        // Then the 4 January decisions of bank-a, newest first; not February's, not bank-b's
        assertThat(page.items()).extracting(DecisionLogEntry::decisionId).containsExactlyElementsOf(january.reversed());
        assertThat(engines.decisionLogQuery().searchByCorrelationId(
                new CorrelationSearch(APPLICATION, "card-fee", JAN, FEB), null).items()).hasSize(2);
        caller.as("bank-b", "dan-b", Set.of("rules:log:read"));
        assertThat(engines.decisionLogQuery().searchByCorrelationId(CorrelationSearch.of(APPLICATION, JAN, FEB), null)
                .items()).hasSize(1);
        assertThat(rows("bank-a")).isEqualTo(before);

        // And the plan reads the partition of the period only, through idx_rle_decision_correlation, never the default
        String plan = explain(JdbcDecisionLog.correlationSql(false, false), Map.of("tenant", "bank-a",
                "correlation", APPLICATION, "from", Timestamp.from(JAN), "to", Timestamp.from(FEB), "limit", 201));
        assertThat(relations(plan)).containsExactly("rle_decision_2027_01");
        assertThat(indexes(plan)).containsExactly("rle_decision_2027_01_tenant_id_correlation_id_created_at_id_idx");
        assertThat(plan).doesNotContain("Seq Scan");
        Instant midJanuary = Instant.parse("2027-01-15T00:00:00Z");
        String twoMonths = explain(JdbcDecisionLog.correlationSql(false, true), Map.of("tenant", "bank-a",
                "correlation", APPLICATION, "from", Timestamp.from(midJanuary),
                "to", Timestamp.from(midJanuary.plus(Duration.ofDays(31))), "limit", 201,
                "afterAt", Timestamp.from(february), "afterId", Ulid.next(february)));
        assertThat(relations(twoMonths)).containsExactly("rle_decision_2027_01", "rle_decision_2027_02");
        assertThat(indexes(twoMonths)).hasSize(2).allMatch(name -> name.contains("_correlation_id_created_at_id_idx"));
        assertThat(twoMonths).doesNotContain("Seq Scan");
    }

    @Test
    void RLE_070_the_migration_index_leads_with_the_tenant_and_exists_on_the_parent_and_every_partition() {
        assertThat(JdbcClient.create(plain).sql("""
                SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_rle_decision_correlation'
                """).query(String.class).single())
                .contains("(tenant_id, correlation_id, created_at DESC, id DESC)")
                .contains("WHERE (correlation_id IS NOT NULL)");
        assertThat(JdbcClient.create(plain).sql("""
                SELECT count(*) FROM pg_indexes WHERE tablename LIKE 'rle_decision_%'
                  AND indexdef LIKE '%(tenant_id, correlation_id, created_at DESC, id DESC)%'
                """).query(Long.class).single()).isEqualTo(3);
    }

    @Test
    void RLE_071_a_number_or_money_is_found_by_exact_value_in_the_tenant_and_the_plan_stays_bounded() {
        // Given loan-pricing in bank-a with 30 January decisions, 2 of them for 2,500,000,000 VND, and bank-b's own
        publish("bank-a", LOAN_PRICING, "loan-pricing");
        publish("bank-b", LOAN_PRICING, "loan-pricing");
        for (int i = 0; i < 30; i++) {
            caller.as("bank-a", "svc-los", Set.of("rules:evaluate"));
            engines.ruleEngine().evaluate(DecisionRequest.of("loan-pricing", DecisionInput.of(Map.of(
                    "productCode", "HOME-30Y", "amount", Money.ofMinor("VND", i < 2 ? 2_500_000_000L : 800_000_000L),
                    "marginBps", new BigDecimal("30000"), "nationalId", "07909000" + (1000 + i)))));
        }
        caller.as("bank-b", "svc-los", Set.of("rules:evaluate"));
        engines.ruleEngine().evaluate("loan-pricing", DecisionInput.of(Map.of("productCode", "HOME-30Y",
                "amount", Money.ofMinor("VND", 2_500_000_000L))));
        long before = rows("bank-a");
        caller.as("bank-a", "dan", Set.of("rules:log:read"));

        // When dan searches by productCode, by amount, and by marginBps written at another scale
        List<DecisionLogEntry> byProduct = engines.decisionLogQuery()
                .searchByInput(new InputSearch("loan-pricing", "productCode", "HOME-30Y", JAN, FEB), null).items();
        List<DecisionLogEntry> byAmount = engines.decisionLogQuery().searchByInput(new InputSearch("loan-pricing",
                "amount", Money.ofMinor("VND", 2_500_000_000L), JAN, FEB), null).items();
        List<DecisionLogEntry> byMargin = engines.decisionLogQuery().searchByInput(new InputSearch("loan-pricing",
                "marginBps", new BigDecimal("30000.00"), JAN, FEB), null).items();

        // Then 30, 2 and 30 decisions, all bank-a's, and the search wrote nothing
        assertThat(byProduct).hasSize(30);
        assertThat(byAmount).hasSize(2);
        assertThat(byMargin).hasSize(30);
        assertThat(rows("bank-a")).isEqualTo(before);
        assertThat(engines.decisionLogQuery().searchByInput(new InputSearch("loan-pricing", "productCode", "home-30y",
                JAN, FEB), null).items()).isEmpty();

        // And a masked input is refused, not answered with no rows
        assertThatThrownBy(() -> engines.decisionLogQuery().searchByInput(new InputSearch("loan-pricing", "nationalId",
                "079081000", JAN, FEB), null)).isInstanceOf(QueryInvalidException.class)
                .hasMessageContaining("find-by-masked-input");

        // And a bank-b caller finds bank-b's one decision only
        caller.as("bank-b", "dan-b", Set.of("rules:log:read"));
        assertThat(engines.decisionLogQuery().searchByInput(new InputSearch("loan-pricing", "amount",
                Money.ofMinor("VND", 2_500_000_000L), JAN, FEB), null).items()).hasSize(1);

        // And the plan reads one partition through an index led by tenant, never a sequential scan
        String plan = explain(JdbcDecisionLog.inputSql(false), Map.of("tenant", "bank-a", "ruleSet", "loan-pricing",
                "input", "productCode", "value", "\"HOME-30Y\"", "from", Timestamp.from(JAN),
                "to", Timestamp.from(FEB), "limit", 201));
        assertThat(relations(plan)).containsExactly("rle_decision_2027_01");
        assertThat(plan).doesNotContain("Seq Scan");
    }

    private static DecisionRecord row(String tenant, String id, Instant at, String ruleSet, String correlation) {
        return new DecisionRecord(tenant, id, at, ruleSet, 1, "FIRST", "OK", null, "{\"tier\":\"SILVER\"}",
                "{\"fee\":{\"currency\":\"VND\",\"amount\":10000}}", "[\"R2\"]", "svc-los", correlation, at, 31);
    }

    /** EXPLAIN of one statement with sequential scans off, so the plan shows which index serves it. */
    private String explain(String sql, Map<String, Object> params) {
        try (Connection connection = plain.getConnection()) {
            connection.createStatement().execute("SET enable_seqscan = off");
            JdbcClient jdbc = JdbcClient.create(new SingleConnectionDataSource(connection, true));
            var statement = jdbc.sql("EXPLAIN (FORMAT JSON) " + sql);
            for (Map.Entry<String, Object> param : params.entrySet()) {
                statement = statement.param(param.getKey(), param.getValue());
            }
            return statement.query(String.class).single();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Set<String> relations(String plan) {
        return matches(RELATION, plan);
    }

    private static Set<String> indexes(String plan) {
        return matches(INDEX, plan);
    }

    private static Set<String> matches(Pattern pattern, String plan) {
        Set<String> found = new TreeSet<>();
        Matcher m = pattern.matcher(plan);
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }

    private static DataSource freshDatabase() {
        String name = "rle_case_it_" + DATABASES.incrementAndGet();
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

    /** A caller whose tenant, name and permissions the test switches. */
    static final class Caller implements TenantResolver, CallerResolver {
        private volatile String tenant = "bank-a";
        private volatile String user = "alice";
        private volatile Set<String> permissions = Set.of();

        void as(String tenant, String user, Set<String> permissions) {
            this.tenant = tenant;
            this.user = user;
            this.permissions = permissions;
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
            return permissions;
        }
    }
}
