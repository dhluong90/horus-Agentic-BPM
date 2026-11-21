package io.github.dhluong90.bpm.process.postgres;

import io.github.dhluong90.bpm.process.api.WorkingCalendar;
import io.github.dhluong90.bpm.process.internal.json.Ulid;
import io.github.dhluong90.bpm.process.internal.json.Values;
import io.github.dhluong90.bpm.process.internal.store.CalendarStore;
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
    private static final String CHANGE_COLUMNS = "tenant_id, id, calendar_key, status, approval, base_version, "
            + "previous_value::text AS previous_value, proposed_value::text AS proposed_value, reason, proposed_by, "
            + "proposed_at, decided_by, decided_at, decision_comment";

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
            WorkingCalendar calendar = new WorkingCalendar(ZoneId.of((String) r[2]), week(Values.readMap((String) r[3])),
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
                .params(tenant, id, key, calendar.zone().getId(), Values.write(weekJson(calendar)), updatedBy,
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
                .params(calendar.zone().getId(), Values.write(weekJson(calendar)), version, updatedBy, approvedBy,
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
                        c.previous() == null ? null : Values.write(json(c.previous())), Values.write(json(c.proposed())),
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
                previous == null ? null : calendar(Values.readMap(previous)),
                calendar(Values.readMap(rs.getString("proposed_value"))), rs.getString("reason"),
                rs.getString("proposed_by"), instant(rs, "proposed_at"), rs.getString("decided_by"),
                instant(rs, "decided_at"), rs.getString("decision_comment"));
    }

    /** The whole calendar as the change record keeps it: zone, week and holidays. */
    static Map<String, Object> json(WorkingCalendar c) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("zone", c.zone().getId());
        out.put("week", weekJson(c));
        Map<String, Object> holidays = new LinkedHashMap<>();
        c.holidays().forEach((date, description) -> holidays.put(date.toString(), description));
        out.put("holidays", holidays);
        return out;
    }

    @SuppressWarnings("unchecked")
    static WorkingCalendar calendar(Map<String, Object> json) {
        TreeMap<LocalDate, String> holidays = new TreeMap<>();
        ((Map<String, Object>) json.get("holidays")).forEach((date, d) -> holidays.put(LocalDate.parse(date),
                (String) d));
        return new WorkingCalendar(ZoneId.of((String) json.get("zone")), week((Map<String, Object>) json.get("week")),
                holidays);
    }

    private static Map<String, Object> weekJson(WorkingCalendar c) {
        Map<String, Object> week = new LinkedHashMap<>();
        c.week().forEach((day, hours) -> week.put(day.name(), hours.stream().map(Object::toString).toList()));
        return week;
    }

    @SuppressWarnings("unchecked")
    private static Map<DayOfWeek, List<WorkingCalendar.Hours>> week(Map<String, Object> json) {
        EnumMap<DayOfWeek, List<WorkingCalendar.Hours>> week = new EnumMap<>(DayOfWeek.class);
        json.forEach((day, ranges) -> {
            List<WorkingCalendar.Hours> hours = new ArrayList<>();
            for (Object range : (List<Object>) ranges) {
                hours.add(WorkingCalendar.Hours.parse((String) range));
            }
            week.put(DayOfWeek.valueOf(day), hours);
        });
        return week;
    }

    private static Timestamp ts(Instant at) {
        return Timestamp.from(at);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.withOffsetSameInstant(ZoneOffset.UTC).toInstant();
    }
}
