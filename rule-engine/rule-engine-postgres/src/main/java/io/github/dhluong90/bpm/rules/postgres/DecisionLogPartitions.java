package io.github.dhluong90.bpm.rules.postgres;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * month. {@link #defaultRows()} feeds the {@code horus_rle_partition_default_rows} gauge. {@link #dropExpired} is
 * retention: it drops whole monthly partitions older than the host's {@code decision-log.retention} and records each
 * drop in the append-only {@code rle_decision_log_drop}. It is called only when the host set a retention, and nothing
 * here deletes a row by date (TR-OBS-01). The default partition is never dropped.
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
    private static final Pattern MONTH_PARTITION = Pattern.compile("^rle_decision_(\\d{4})_(\\d{2})$");

    /** The {@code tenant_id} of the record row holding a dropped partition's total over every tenant. */
    public static final String ALL_TENANTS = "*";

    /**
     * One partition dropped by retention, as recorded in {@code rle_decision_log_drop}.
     *
     * @param partition the partition name, for example {@code rle_decision_2027_01}
     * @param from the first instant of the month, inclusive
     * @param to the first instant of the next month, exclusive
     * @param decisionsByTenant how many decisions each tenant had in it, by tenant id in order
     * @param total the decisions of every tenant
     */
    public record Dropped(String partition, Instant from, Instant to, Map<String, Long> decisionsByTenant, long total) {

        public Dropped {
            decisionsByTenant = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(decisionsByTenant));
        }
    }

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
     * Returns the cutoff of a retention: the instant {@code retention} before {@code now}, in UTC calendar months, so
     * {@code P12M} at {@code 2028-02-03T00:00:00Z} is {@code 2027-02-03T00:00:00Z}.
     *
     * @param now the current instant from the host's clock
     * @param retention the retention, whole months only
     * @return the cutoff; a partition whose upper bound is at or before it holds only older rows
     */
    public static Instant cutoff(Instant now, Period retention) {
        return now.atZone(ZoneOffset.UTC).minusMonths(wholeMonths(retention)).toInstant();
    }

    /**
     * Returns the months whose partitions retention drops: those present whose upper bound is at or before the cutoff.
     *
     * @param present the months whose partitions exist
     * @param cutoff the cutoff, see {@link #cutoff}
     * @return the expired months, in month order
     */
    public static List<YearMonth> expired(List<YearMonth> present, Instant cutoff) {
        return present.stream()
                .filter(month -> !month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().isAfter(cutoff))
                .sorted()
                .toList();
    }

    /**
     * Drops every monthly partition of {@code rle_decision} that lies wholly before {@code now - retention}, and
     * records each drop (RLE-017, TR-OPS-04, PD-050). Never deletes a row and never touches the default partition.
     *
     * <p>One transaction, with {@code lock_timeout = 2s}, under the advisory lock of {@link #ensureAhead}. For each
     * expired partition: {@code LOCK} it in {@code SHARE} mode, so no row can arrive in it while it is counted; count
     * its rows per tenant; insert the record rows into {@code rle_decision_log_drop} (one per tenant and one for
     * {@value #ALL_TENANTS}); then {@code DROP TABLE}. The drop holds the parent's lock from that statement to the
     * commit only, so inserts wait at most the lock timeout. Either every expired month is dropped and recorded, or
     * none is and the next run retries.
     *
     * @param now the current instant from the host's clock
     * @param retention the host's {@code decision-log.retention}, whole months only
     * @return what was dropped, in month order; empty when nothing had expired
     * @throws IllegalArgumentException when the retention is not a positive number of whole months
     * @throws org.springframework.dao.DataAccessException when a lock waits longer than {@code lock_timeout}; nothing
     *     is dropped and the next run retries
     */
    public List<Dropped> dropExpired(Instant now, Period retention) {
        Objects.requireNonNull(now, "now must not be null");
        long months = wholeMonths(retention);
        Instant cutoff = cutoff(now, retention);
        String recorded = "P" + months + "M";
        return transactions.inNewTransaction(status -> {
            jdbc.sql("SET LOCAL lock_timeout = '2s'").update();
            jdbc.sql("SELECT pg_advisory_xact_lock(:key)").param("key", ADVISORY_LOCK_KEY)
                    .query((rs, rowNum) -> rowNum).single();
            List<Dropped> dropped = new ArrayList<>();
            for (YearMonth month : expired(presentMonths(), cutoff)) {
                dropped.add(drop(month, recorded, cutoff, now));
            }
            return List.copyOf(dropped);
        });
    }

    /**
     * Returns the months whose partitions are attached to {@code rle_decision}; the default partition is not a month.
     *
     * @return the months in order
     */
    public List<YearMonth> presentMonths() {
        List<String> names = RuleListQueries.DECISION_PARTITIONS.list(jdbc, java.util.Map.of(),
                (rs, row) -> rs.getString("relname"));
        List<YearMonth> months = new ArrayList<>();
        for (String name : names) {
            Matcher matcher = MONTH_PARTITION.matcher(name);
            if (matcher.matches()) {
                months.add(YearMonth.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))));
            }
        }
        months.sort(null);
        return List.copyOf(months);
    }

    private Dropped drop(YearMonth month, String retention, Instant cutoff, Instant now) {
        // DDL takes no bind parameters. The name is built from a YearMonth (digits only), so no caller text reaches
        // these statements.
        String name = partitionName(month);
        Instant lower = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant upper = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        jdbc.sql("LOCK TABLE " + name + " IN SHARE MODE").update();
        Map<String, Long> byTenant = new LinkedHashMap<>();
        jdbc.sql("SELECT tenant_id, count(*) AS n FROM " + name + " GROUP BY tenant_id ORDER BY tenant_id")
                .query(rs -> {
                    byTenant.put(rs.getString("tenant_id"), rs.getLong("n"));
                });
        long total = byTenant.values().stream().mapToLong(Long::longValue).sum();
        Map<String, Long> rows = new LinkedHashMap<>(byTenant);
        rows.put(ALL_TENANTS, total);
        for (Map.Entry<String, Long> row : rows.entrySet()) {
            jdbc.sql("""
                    INSERT INTO rle_decision_log_drop
                      (tenant_id, partition_name, range_from, range_to, decision_count, retention, cutoff, dropped_at)
                    VALUES (:tenant, :name, :lower, :upper, :count, :retention, :cutoff, :now)
                    """)
                    .param("tenant", row.getKey())
                    .param("name", name)
                    .param("lower", Timestamp.from(lower))
                    .param("upper", Timestamp.from(upper))
                    .param("count", row.getValue())
                    .param("retention", retention)
                    .param("cutoff", Timestamp.from(cutoff))
                    .param("now", Timestamp.from(now))
                    .update();
        }
        jdbc.sql("DROP TABLE " + name).update();
        return new Dropped(name, lower, upper, byTenant, total);
    }

    private static long wholeMonths(Period retention) {
        Objects.requireNonNull(retention, "retention must not be null");
        if (retention.getDays() != 0 || retention.toTotalMonths() <= 0) {
            throw new IllegalArgumentException("retention must be a positive number of whole months, was " + retention);
        }
        return retention.toTotalMonths();
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
        // AUD-03: the row trigger of the parent was cloned onto the partition by ATTACH; a statement trigger is not.
        jdbc.sql("CREATE TRIGGER " + name + "_no_truncate BEFORE TRUNCATE ON " + name
                + " FOR EACH STATEMENT EXECUTE FUNCTION rle_decision_append_only()").update();
    }
}
