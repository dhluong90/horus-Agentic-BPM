package io.github.dhluong90.bpm.rules.postgres;

import java.sql.Timestamp;
import java.time.Duration;
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
 * <p>{@link #ensureAhead} is the maintenance step (RLE-017): the starter runs it at start-up and daily, and it
 * creates every month from the current one up to {@code now + decision-log.partitions-ahead}, and always the next
 * month. {@link #defaultRows()} feeds the {@code horus_rle_partition_default_rows} gauge. Retention by dropping whole
 * partitions arrives with its own slice; nothing here drops a partition or deletes a row by date (TR-OBS-01).
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
        return ensureAhead(now, Duration.ZERO);
    }

    /**
     * Creates the missing partitions of every month from the month of {@code now} to the month of
     * {@code now + ahead}, and of the next month in any case. Rows of a created month that sit in the default
     * partition move into it before {@code ATTACH}. Safe to run on several replicas at once: one runs at a time
     * under a transaction-scoped advisory lock, and the others find the months present (TR-OPS-05).
     *
     * @param now the current instant from the host's clock
     * @param ahead how far ahead to create months, {@code decision-log.partitions-ahead}; zero or positive
     * @return the names of the partitions this call created, in month order; empty when all existed
     * @throws org.springframework.dao.DataAccessException when the lock or a DDL statement waits longer than
     *     {@code lock_timeout} (5 s); nothing is created and the next run retries
     */
    public List<String> ensureAhead(Instant now, Duration ahead) {
        Objects.requireNonNull(now, "now must not be null");
        if (ahead == null || ahead.isNegative()) {
            throw new IllegalArgumentException("ahead must be zero or positive, was " + ahead);
        }
        List<YearMonth> months = months(now, ahead);
        return transactions.inNewTransaction(status -> {
            jdbc.sql("SET LOCAL lock_timeout = '5s'").update();
            jdbc.sql("SELECT pg_advisory_xact_lock(:key)").param("key", ADVISORY_LOCK_KEY)
                    .query((rs, rowNum) -> rowNum).single();
            List<String> created = new ArrayList<>();
            for (YearMonth month : months) {
                if (!exists(month)) {
                    create(month);
                    created.add(partitionName(month));
                }
            }
            return List.copyOf(created);
        });
    }

    /**
     * Returns the months {@link #ensureAhead} keeps present: the month of {@code now}, the next month, and every
     * month up to the month of {@code now + ahead}.
     *
     * @param now the current instant
     * @param ahead how far ahead
     * @return the months in order
     */
    public static List<YearMonth> months(Instant now, Duration ahead) {
        YearMonth current = monthOf(now);
        YearMonth last = monthOf(now.plus(ahead));
        if (last.isBefore(current.plusMonths(1))) {
            last = current.plusMonths(1);
        }
        List<YearMonth> months = new ArrayList<>();
        for (YearMonth month = current; !month.isAfter(last); month = month.plusMonths(1)) {
            months.add(month);
        }
        return List.copyOf(months);
    }

    /**
     * Counts the rows in the default partition {@code rle_decision_default}, which should stay empty: a row there
     * fell outside every monthly partition. Read-only, in a transaction of its own, bounded by a 2 s statement
     * timeout.
     *
     * @return the number of rows
     */
    public long defaultRows() {
        return transactions.inNewTransaction(status -> {
            jdbc.sql("SET LOCAL statement_timeout = '2s'").update();
            return jdbc.sql("SELECT count(*) FROM rle_decision_default").query(Long.class).single();
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
