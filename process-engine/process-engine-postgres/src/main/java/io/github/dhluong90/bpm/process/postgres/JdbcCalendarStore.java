package io.github.dhluong90.bpm.process.postgres;

import io.github.dhluong90.bpm.process.api.WorkingCalendar;
import io.github.dhluong90.bpm.process.internal.json.CalendarJson;
import io.github.dhluong90.bpm.process.internal.json.Ulid;
import io.github.dhluong90.bpm.process.internal.json.Values;
import io.github.dhluong90.bpm.process.internal.store.CalendarStore;
import io.github.dhluong90.bpm.process.internal.store.ProcessStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link CalendarStore} on PostgreSQL (#72, migration V20): {@code pre_calendar} with its working hours as JSON,
 * {@code pre_holiday} one row per date, and {@code pre_calendar_change}, the record of every proposal and decision.
 * Every statement filters by tenant first; reads join the caller's transaction.
 */
public final class JdbcCalendarStore implements CalendarStore {

    private static final String CALENDAR_COLUMNS = "id, calendar_key, timezone, weekly_hours::text AS weekly_hours, "
            + "lock_version, updated_by, approved_by, updated_at";
    private static final String CHANGE_COLUMNS = ProcessListQueries.CALENDAR_CHANGE_COLUMNS;

    private final JdbcClient jdbc;

    /** @param dataSource the host's data source */
    public JdbcCalendarStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource must not be null"));
    }

    @Override
    public Optional<StoredCalendar> calendar(String tenant, String key) {
        return read(tenant, key, "");
    }

    @Override
    public Optional<StoredCalendar> lockCalendar(String tenant, String key) {
        return read(tenant, key, " FOR UPDATE");
    }

    private Optional<StoredCalendar> read(String tenant, String key, String lock) {
        Optional<Object[]> row = jdbc.sql("SELECT " + CALENDAR_COLUMNS + " FROM pre_calendar WHERE tenant_id = ? "
                        + "AND calendar_key = ?" + lock)
                .params(tenant, key)
                .query((rs, n) -> new Object[] {rs.getString("id"), rs.getString("calendar_key"),
                        rs.getString("timezone"), rs.getString("weekly_hours"), rs.getLong("lock_version"),
                        rs.getString("updated_by"), rs.getString("approved_by"), instant(rs, "updated_at")})
                .optional();
        return row.map(r -> {
            TreeMap<LocalDate, String> holidays = new TreeMap<>();
            ProcessListQueries.CALENDAR_HOLIDAYS.list(jdbc, List.of(tenant, (String) r[0]),
                    (rs, n) -> holidays.put(rs.getObject("holiday_date", LocalDate.class), rs.getString("description")));
            WorkingCalendar calendar = new WorkingCalendar(ZoneId.of((String) r[2]), CalendarJson.week(Values.readMap((String) r[3])),
                    holidays);
            return new StoredCalendar((String) r[0], (String) r[1], (Long) r[4], calendar, (String) r[5],
                    (String) r[6], (Instant) r[7]);
        });
    }

    @Override
    public boolean insertCalendar(String tenant, String id, String key, WorkingCalendar calendar, String updatedBy,
            String approvedBy, Instant at) {
        int inserted = jdbc.sql("INSERT INTO pre_calendar (tenant_id, id, calendar_key, timezone, weekly_hours, "
                        + "lock_version, updated_by, approved_by, updated_at) VALUES (?, ?, ?, ?, ?::jsonb, 1, ?, ?, ?) "
                        + "ON CONFLICT (tenant_id, calendar_key) DO NOTHING")
                .params(tenant, id, key, calendar.zone().getId(), Values.write(CalendarJson.week(calendar)), updatedBy,
                        approvedBy, ts(at))
                .update();
        if (inserted == 1) {
            insertHolidays(tenant, id, calendar, at);
        }
        return inserted == 1;
    }

    @Override
    public void replaceCalendar(String tenant, String id, long version, WorkingCalendar calendar, String updatedBy,
            String approvedBy, Instant at) {
        jdbc.sql("UPDATE pre_calendar SET timezone = ?, weekly_hours = ?::jsonb, lock_version = ?, updated_by = ?, "
                        + "approved_by = ?, updated_at = ? WHERE tenant_id = ? AND id = ?")
                .params(calendar.zone().getId(), Values.write(CalendarJson.week(calendar)), version, updatedBy, approvedBy,
                        ts(at), tenant, id)
                .update();
        jdbc.sql("DELETE FROM pre_holiday WHERE tenant_id = ? AND calendar_id = ?").params(tenant, id).update();
        insertHolidays(tenant, id, calendar, at);
    }

    private void insertHolidays(String tenant, String calendarId, WorkingCalendar calendar, Instant at) {
        for (Map.Entry<LocalDate, String> h : calendar.holidays().entrySet()) {
            jdbc.sql("INSERT INTO pre_holiday (tenant_id, id, calendar_id, holiday_date, description) "
                            + "VALUES (?, ?, ?, ?, ?)")
                    .params(tenant, Ulid.next(at), calendarId, h.getKey(), h.getValue())
                    .update();
        }
    }

    @Override
    public void insertChange(ChangeRow c) {
        jdbc.sql("INSERT INTO pre_calendar_change (tenant_id, id, calendar_key, status, approval, base_version, "
                        + "previous_value, proposed_value, reason, proposed_by, proposed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)")
                .params(c.tenantId(), c.id(), c.calendarKey(), c.status(), c.approval(), c.baseVersion(),
                        c.previous() == null ? null : Values.write(CalendarJson.toMap(c.previous())), Values.write(CalendarJson.toMap(c.proposed())),
                        c.reason(), c.proposedBy(), ts(c.proposedAt()))
                .update();
    }

    @Override
    public Optional<ChangeRow> lockChange(String tenant, String id) {
        return jdbc.sql("SELECT " + CHANGE_COLUMNS + " FROM pre_calendar_change WHERE tenant_id = ? AND id = ? "
                        + "FOR UPDATE")
                .params(tenant, id).query(JdbcCalendarStore::change).optional();
    }

    @Override
    public Optional<ChangeRow> change(String tenant, String id) {
        return jdbc.sql("SELECT " + CHANGE_COLUMNS + " FROM pre_calendar_change WHERE tenant_id = ? AND id = ?")
                .params(tenant, id).query(JdbcCalendarStore::change).optional();
    }

    @Override
    public void decideChange(String tenant, String id, String status, String decidedBy, Instant decidedAt,
            String comment) {
        jdbc.sql("UPDATE pre_calendar_change SET status = ?, decided_by = ?, decided_at = ?, decision_comment = ? "
                        + "WHERE tenant_id = ? AND id = ? AND status = 'PENDING'")
                .params(status, decidedBy, ts(decidedAt), comment, tenant, id)
                .update();
    }

    private static ChangeRow change(ResultSet rs, int row) throws SQLException {
        String previous = rs.getString("previous_value");
        return new ChangeRow(rs.getString("tenant_id"), rs.getString("id"), rs.getString("calendar_key"),
                rs.getString("status"), rs.getString("approval"), rs.getLong("base_version"),
                previous == null ? null : CalendarJson.fromMap(Values.readMap(previous)),
                CalendarJson.fromMap(Values.readMap(rs.getString("proposed_value"))), rs.getString("reason"),
                rs.getString("proposed_by"), instant(rs, "proposed_at"), rs.getString("decided_by"),
                instant(rs, "decided_at"), rs.getString("decision_comment"));
    }

    @Override
    public List<SummaryRow> calendars(String tenant, String afterKey, int limit) {
        List<Object> params = new ArrayList<>(List.of(tenant));
        if (afterKey != null) {
            params.add(afterKey);
        }
        params.add(limit);
        return (afterKey == null ? ProcessListQueries.CALENDARS : ProcessListQueries.CALENDARS_AFTER)
                .list(jdbc, params, (rs, n) -> new SummaryRow(rs.getString("calendar_key"), rs.getLong("lock_version"),
                        rs.getString("timezone"), rs.getString("updated_by"), rs.getString("approved_by"),
                        instant(rs, "updated_at")));
    }

    @Override
    public List<ChangeRow> changes(String tenant, String status, Instant afterAt, String afterId, int limit) {
        List<Object> params = new ArrayList<>(List.of(tenant, status));
        if (afterAt != null) {
            params.add(ts(afterAt));
            params.add(afterId);
        }
        params.add(limit);
        return (afterAt == null ? ProcessListQueries.CALENDAR_CHANGES : ProcessListQueries.CALENDAR_CHANGES_AFTER)
                .list(jdbc, params, JdbcCalendarStore::change);
    }

    @Override
    public Optional<ProcessStore.IdempotencyRecord> idempotency(String tenant, String operation, String key,
            Instant now) {
        return jdbc.sql("SELECT tenant_id, operation, idempotency_key, request_hash, response_body::text AS body, "
                        + "expires_at FROM pre_idempotency WHERE tenant_id = ? AND operation = ? "
                        + "AND idempotency_key = ? AND expires_at > ?")
                .params(tenant, operation, key, ts(now))
                .query((rs, row) -> new ProcessStore.IdempotencyRecord(rs.getString("tenant_id"),
                        rs.getString("operation"), rs.getString("idempotency_key"), rs.getString("request_hash"),
                        rs.getString("body"), instant(rs, "expires_at")))
                .optional();
    }

    @Override
    public boolean claimIdempotency(String tenant, String operation, String key, String requestHash,
            Instant expiresAt, Instant now) {
        return jdbc.sql("INSERT INTO pre_idempotency (tenant_id, operation, idempotency_key, request_hash, status, "
                        + "response_body, expires_at) VALUES (?, ?, ?, ?, 'PENDING', NULL, ?) "
                        + "ON CONFLICT (tenant_id, operation, idempotency_key) DO UPDATE SET "
                        + "request_hash = EXCLUDED.request_hash, status = 'PENDING', response_body = NULL, "
                        + "expires_at = EXCLUDED.expires_at WHERE pre_idempotency.expires_at <= ?")
                .params(tenant, operation, key, requestHash, ts(expiresAt), ts(now))
                .update() == 1;
    }

    @Override
    public void completeIdempotency(String tenant, String operation, String key, String responseJson) {
        int updated = jdbc.sql("UPDATE pre_idempotency SET status = 'DONE', response_body = ?::jsonb "
                        + "WHERE tenant_id = ? AND operation = ? AND idempotency_key = ? AND status = 'PENDING'")
                .params(responseJson, tenant, operation, key).update();
        if (updated != 1) {
            throw new IllegalStateException("Idempotency claim lost for " + operation);
        }
    }

    @Override
    public byte[] engineKey(String name, byte[] candidate, Instant now) {
        jdbc.sql("INSERT INTO pre_engine_key (name, key_bytes, created_at) VALUES (?, ?, ?) ON CONFLICT (name) DO NOTHING")
                .params(name, candidate, ts(now)).update();
        return jdbc.sql("SELECT key_bytes FROM pre_engine_key WHERE name = ?").params(name).query(byte[].class).single();
    }

    private static Timestamp ts(Instant at) {
        return Timestamp.from(at);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.withOffsetSameInstant(ZoneOffset.UTC).toInstant();
    }
}
