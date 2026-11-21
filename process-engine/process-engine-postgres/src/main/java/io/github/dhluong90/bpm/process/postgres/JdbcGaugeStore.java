package io.github.dhluong90.bpm.process.postgres;

import io.github.dhluong90.bpm.process.internal.store.GaugeStore;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@link GaugeStore} on PostgreSQL: the three registered gauge queries of {@link ProcessListQueries}. */
public final class JdbcGaugeStore implements GaugeStore {

    /** The job kinds of {@code pre_timer} (V6): a fixed set, so the kind tag has a bounded number of values. */
    public static final List<String> JOB_KINDS = List.of("TIMER", "SLA_ESCALATION", "ASYNC_AGENT", "ASYNC_SERVICE",
            "COMPENSATION", "DEFINITION_EFFECTIVE");

    private final JdbcClient jdbc;

    /** @param dataSource the host's data source */
    public JdbcGaugeStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource must not be null"));
    }

    @Override
    public Snapshot read() {
        long active = jdbc.sql(ProcessListQueries.GAUGE_ACTIVE_INSTANCES.sql()).query(Long.class).single();
        long incidents = jdbc.sql(ProcessListQueries.GAUGE_OPEN_INCIDENTS.sql()).query(Long.class).single();
        Map<String, Instant> oldest = new LinkedHashMap<>();
        for (String kind : JOB_KINDS) {
            jdbc.sql(ProcessListQueries.GAUGE_OLDEST_DUE_JOB.sql()).param(kind)
                    .query((rs, row) -> rs.getTimestamp("next_fire_at").toInstant()).optional()
                    .ifPresent(at -> oldest.put(kind, at));
        }
        return new Snapshot(active, incidents, oldest);
    }
}
