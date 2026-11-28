package io.github.dhluong90.bpm.process.postgres;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Finds out whether the role the host serves traffic with could switch off the guards on the pre_* tables (#700).
 *
 * <p>A table owner can run {@code ALTER TABLE ... DISABLE TRIGGER}, and a superuser can do anything, so a runtime role
 * that is either one makes the append-only and seal triggers advice instead of a control. The check also reports a
 * role that holds the {@code TRIGGER} privilege on a pre_* table, which is enough to add or change triggers.
 * It reads the catalogue only and changes nothing.
 */
public final class DatabaseRoleCheck {

    private final JdbcClient jdbc;

    /**
     * Creates the check on the data source the host serves traffic with.
     *
     * @param dataSource the runtime data source
     */
    public DatabaseRoleCheck(DataSource dataSource) {
        this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource must not be null"));
    }

    /**
     * Lists what is wrong with the runtime role.
     *
     * @return one sentence per problem, empty when the role owns no pre_* table, is no superuser and holds no
     *     {@code TRIGGER} privilege on them
     */
    public List<String> problems() {
        String role = jdbc.sql("SELECT current_user").query(String.class).single();
        List<String> found = new ArrayList<>();
        boolean superuser = jdbc.sql("SELECT rolsuper FROM pg_roles WHERE rolname = current_user")
                .query(Boolean.class).optional().orElse(false);
        if (superuser) {
            found.add("the database role " + role + " is a superuser");
        }
        List<String> owned = jdbc.sql("""
                SELECT tablename FROM pg_tables
                WHERE schemaname = current_schema() AND tablename LIKE 'pre\\_%'
                  AND pg_has_role(current_user, tableowner, 'MEMBER') ORDER BY tablename
                """).query(String.class).list();
        if (!owned.isEmpty()) {
            found.add("the database role " + role + " owns " + owned.size() + " pre_* table(s), for example "
                    + owned.get(0) + ", and so can disable their triggers");
        }
        List<String> trigger = jdbc.sql("""
                SELECT tablename FROM pg_tables
                WHERE schemaname = current_schema() AND tablename LIKE 'pre\\_%'
                  AND has_table_privilege(current_user, quote_ident(schemaname) || '.' || quote_ident(tablename), 'TRIGGER')
                  AND NOT pg_has_role(current_user, tableowner, 'MEMBER') ORDER BY tablename
                """).query(String.class).list();
        if (!trigger.isEmpty()) {
            found.add("the database role " + role + " holds TRIGGER on " + trigger.size() + " pre_* table(s), for example "
                    + trigger.get(0));
        }
        return List.copyOf(found);
    }
}
