package io.github.dhluong90.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.OptionalLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The event outbox lag meter (TR-OBS-02): the rows the host's relay has not marked SENT, and the age of the oldest,
 * measured from the clock the caller passes.
 */
@Testcontainers
class EventOutboxLagIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    @Test
    void TR_OBS_02_an_empty_outbox_has_nothing_pending_and_no_age() {
        // Given a migrated database with no outbox row
        DataSource db = migratedDatabase("rle_lag_empty");
        EventOutboxLag lag = lag(db);

        // Then
        assertThat(lag.pending()).isZero();
        assertThat(lag.oldestAgeSeconds(Instant.parse("2027-01-26T12:00:00Z"))).isEqualTo(OptionalLong.empty());
    }

    @Test
    void TR_OBS_02_pending_and_claimed_rows_count_and_sent_rows_do_not_across_tenants() {
        // Given one sent row, one claimed row from 90 s ago, one pending row from 30 s ago, in two tenants
        DataSource db = migratedDatabase("rle_lag_mixed");
        JdbcClient jdbc = JdbcClient.create(db);
        insert(jdbc, "bank-a", "01KDQ0000000000000000L0001", "SENT", "2027-01-26T11:00:00Z");
        insert(jdbc, "bank-a", "01KDQ0000000000000000L0002", "CLAIMED", "2027-01-26T11:58:30Z");
        insert(jdbc, "bank-b", "01KDQ0000000000000000L0003", "PENDING", "2027-01-26T11:59:30Z");
        EventOutboxLag lag = lag(db);

        // When the clock reads 12:00:00
        long pending = lag.pending();
        OptionalLong age = lag.oldestAgeSeconds(Instant.parse("2027-01-26T12:00:00Z"));

        // Then the sent row is ignored and the oldest unsent row sets the age
        assertThat(pending).isEqualTo(2);
        assertThat(age).hasValue(90);
    }

    @Test
    void TR_OBS_02_a_row_dated_after_the_clock_reads_as_age_zero() {
        DataSource db = migratedDatabase("rle_lag_future");
        insert(JdbcClient.create(db), "bank-a", "01KDQ0000000000000000L0004", "PENDING", "2027-01-26T12:00:05Z");

        assertThat(lag(db).oldestAgeSeconds(Instant.parse("2027-01-26T12:00:00Z"))).hasValue(0);
    }

    private static void insert(JdbcClient jdbc, String tenant, String id, String status, String createdAt) {
        jdbc.sql("INSERT INTO rle_decision_outbox (tenant_id, id, payload, status, created_at) "
                        + "VALUES (:t, :id, '{}'::jsonb, :s, :at)")
                .param("t", tenant).param("id", id).param("s", status)
                .param("at", Timestamp.from(Instant.parse(createdAt))).update();
    }

    private static EventOutboxLag lag(DataSource db) {
        return new EventOutboxLag(db, new RuleEngineTransactions(new DataSourceTransactionManager(db)));
    }

    private static DataSource migratedDatabase(String name) {
        JdbcClient.create(dataSourceFor(POSTGRES.getDatabaseName())).sql("CREATE DATABASE " + name).update();
        DataSource db = dataSourceFor(name);
        new RuleEngineSchemaMigrator(db).migrate();
        return db;
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
}
