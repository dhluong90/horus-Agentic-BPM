package io.github.dhluong90.bpm.process.postgres;

import io.github.dhluong90.bpm.process.api.LayoutRevision;
import io.github.dhluong90.bpm.process.internal.store.LayoutStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link LayoutStore} on PostgreSQL (#102). The engine only inserts; the table's triggers refuse any
 * other write. Every statement names the tenant first.
 */
public final class JdbcLayoutStore implements LayoutStore {

    private static final String SUMMARY = "revision, layout_hash, definition_hash, source, created_by, created_at";

    private final JdbcClient jdbc;

    /** @param dataSource the host's data source */
    public JdbcLayoutStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource must not be null"));
    }

    @Override
    public boolean insert(String tenant, String definitionId, LayoutRevision r) {
        // A save that lost a race hits the primary key; DO NOTHING keeps the transaction usable to report it.
        return jdbc.sql("INSERT INTO pre_definition_layout (tenant_id, definition_id, revision, layout_json, "
                        + "layout_hash, definition_hash, source, created_by, created_at) "
                        + "VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")
                .params(tenant, definitionId, r.revision(), r.layoutJson(), r.layoutHash(), r.definitionHash(),
                        r.source(), r.createdBy(), Timestamp.from(r.createdAt()))
                .update() == 1;
    }

    @Override
    public int latestRevision(String tenant, String definitionId) {
        return jdbc.sql("SELECT coalesce(max(revision), 0) FROM pre_definition_layout WHERE tenant_id = ? "
                + "AND definition_id = ?").params(tenant, definitionId).query(Integer.class).single();
    }

    @Override
    public Optional<LayoutRevision> find(String tenant, String definitionId, int revision) {
        return jdbc.sql("SELECT " + SUMMARY + ", layout_json::text AS layout_json FROM pre_definition_layout "
                        + "WHERE tenant_id = ? AND definition_id = ? AND revision = ?")
                .params(tenant, definitionId, revision).query((rs, n) -> row(rs, true)).optional();
    }

    @Override
    public List<LayoutRevision> list(String tenant, String definitionId, int afterRevision, int limit) {
        return ProcessListQueries.LAYOUT_REVISIONS.list(jdbc, List.of(tenant, definitionId, afterRevision, limit),
                (rs, n) -> row(rs, false));
    }

    private static LayoutRevision row(ResultSet rs, boolean withDocument) throws SQLException {
        return new LayoutRevision(rs.getInt("revision"), withDocument ? rs.getString("layout_json") : null,
                rs.getString("layout_hash"), rs.getString("definition_hash"), rs.getString("source"),
                rs.getString("created_by"), rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
