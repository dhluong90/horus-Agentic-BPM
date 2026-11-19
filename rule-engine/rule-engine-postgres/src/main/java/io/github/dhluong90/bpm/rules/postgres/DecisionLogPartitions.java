package io.github.dhluong90.bpm.rules.postgres;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Creates the monthly partitions of the decision log {@code rle_decision}.
 *
 * <p>Months are calendar months in UTC, half-open {@code [first day, first day of next month)}, named
 * {@code rle_decision_YYYY_MM} (rule-engine/docs/DESIGN.md section 5). The month comes from the
 * instant the caller passes, normally the host's {@code Clock}, never from the database clock, so the
 * result is deterministic under a fixed clock.
 *
 * <p>This is the start-up step only: it creates the current and the next month. Scheduled creation
 * up to {@code decision-log.partitions-ahead}, retention by dropping whole partitions and the
 * {@code horus_rle_partition_default_rows} gauge are RLE-017 (milestone M1).
 *
 * <p>Each partition is created standalone with a CHECK constraint and then attached, so
 * {@code ATTACH} skips the validation scan and takes {@code SHARE UPDATE EXCLUSIVE} on the parent.
 * Rows of that month already in {@code rle_decision_default} are moved into the new partition in the
 * same transaction before {@code ATTACH}, so no row is lost. One replica at a time runs the step,
 * serialised by a transaction-scoped advisory lock.
 */
public final class DecisionLogPartitions {

    /** Advisory lock key of partition maintenance: the bytes of "rle_part". */
    static final long ADVISORY_LOCK_KEY = 0x726C655F70617274L;

    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyy_MM");

    private final JdbcClient jdbc;
    private final RuleEngineTransactions transactions;

    /**
     * Creates the partition step on the host's data source and transactions.
     *
     * @param dataSource the host's data source
     * @param transactions the engine's access to the host's transaction manager
     */
    public DecisionLogPartitions(DataSource dataSource, RuleEngineTransactions transactions) {
        this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource must not be null"));
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
    }

    /**
     * Returns the partition name of a month.
     *
     * @param month the calendar month in UTC
     * @return for example {@code rle_decision_2027_01}
     */
    public static String partitionName(YearMonth month) {
        return "rle_decision_" + month.format(SUFFIX);
    }

    /**
     * Returns the UTC calendar month an instant falls in.
     *
     * @param instant the instant
     * @return its month in UTC
     */
    public static YearMonth monthOf(Instant instant) {
        return YearMonth.from(instant.atZone(ZoneOffset.UTC));
    }

    /**
     * Creates the partitions of the month of {@code now} and of the next month when they are missing.
     * Safe to run on every start and on several replicas at once.
     *
     * @param now the current instant from the host's clock
     * @return the names of the partitions this call created, in month order; empty when both existed
     */
    public List<String> ensureCurrentAndNextMonth(Instant now) {
        YearMonth current = monthOf(Objects.requireNonNull(now, "now must not be null"));
        return transactions.inNewTransaction(status -> {
            jdbc.sql("SET LOCAL lock_timeout = '5s'").update();
            jdbc.sql("SELECT pg_advisory_xact_lock(:key)").param("key", ADVISORY_LOCK_KEY)
                    .query((rs, rowNum) -> rowNum).list();
            List<String> created = new ArrayList<>();
            for (YearMonth month : List.of(current, current.plusMonths(1))) {
                if (!exists(month)) {
                    create(month);
                    created.add(partitionName(month));
                }
            }
            return List.copyOf(created);
        });
    }

    /**
     * Tells whether the partition of a month is attached to {@code rle_decision}.
     *
     * @param month the calendar month in UTC
     * @return true when {@code rle_decision_YYYY_MM} is a partition of {@code rle_decision}
     */
    public boolean exists(YearMonth month) {
        return jdbc.sql("""
                SELECT EXISTS (
                  SELECT 1 FROM pg_inherits i
                  JOIN pg_class child ON child.oid = i.inhrelid
                  WHERE i.inhparent = 'rle_decision'::regclass AND child.relname = :name)
                """).param("name", partitionName(month)).query(Boolean.class).single();
    }

    private void create(YearMonth month) {
        // DDL takes no bind parameters. The name is built from digits only and the bounds are
        // Instant values formatted by the JDK, so no caller text reaches these statements.
        String name = partitionName(month);
        Instant lower = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant upper = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        String range = "'" + lower + "'::timestamptz AND created_at < '" + upper + "'::timestamptz";

        jdbc.sql("CREATE TABLE " + name + " (LIKE rle_decision INCLUDING DEFAULTS INCLUDING CONSTRAINTS)").update();
        jdbc.sql("ALTER TABLE " + name + " ADD CONSTRAINT " + name + "_range CHECK (created_at >= " + range + ")")
                .update();
        jdbc.sql("WITH moved AS (DELETE FROM rle_decision_default WHERE created_at >= :lower AND created_at < :upper "
                        + "RETURNING *) INSERT INTO " + name + " SELECT * FROM moved")
                .param("lower", Timestamp.from(lower))
                .param("upper", Timestamp.from(upper))
                .update();
        jdbc.sql("ALTER TABLE rle_decision ATTACH PARTITION " + name
                        + " FOR VALUES FROM ('" + lower + "') TO ('" + upper + "')")
                .update();
    }
}
