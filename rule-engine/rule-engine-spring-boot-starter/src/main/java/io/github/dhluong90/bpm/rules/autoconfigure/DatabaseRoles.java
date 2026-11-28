package io.github.dhluong90.bpm.rules.autoconfigure;

import io.github.dhluong90.bpm.rules.postgres.DatabaseRoleCheck;
import java.util.List;
import javax.sql.DataSource;
import org.apache.commons.logging.Log;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The two-role database setup (#700): the schema owner's data source and the start-up check of the runtime role.
 */
final class DatabaseRoles {

    private DatabaseRoles() {
    }

    /**
     * Builds the schema owner's data source. It opens a connection per use and keeps no pool, so the owner's session
     * lives only for a migration run.
     *
     * @param database the settings
     * @return the owner's data source, or {@code null} when the two-role setup is off
     * @throws IllegalStateException when an owner is named without a URL
     */
    static DataSource ownerDataSource(RuleEngineProperties.Database database) {
        if (!database.split()) {
            return null;
        }
        if (database.ownerUrl() == null || database.ownerUrl().isBlank()) {
            throw new IllegalStateException("horus.bpm.rules.database.owner-username is set but horus.bpm.rules.database.owner-url is not; "
                    + "name the JDBC URL the schema owner connects to");
        }
        return new DriverManagerDataSource(database.ownerUrl(), database.ownerUsername(), database.ownerPassword());
    }

    /**
     * Checks the runtime role. {@code warn} logs one WARN, {@code enforce} refuses to start, {@code off} does
     * nothing; unset is {@code enforce} outside the {@code dev} profile and {@code off} inside it. The refusal carries the
     * code {@code HORUS-DB-ROLE-001}. Choosing {@code warn} or {@code off} outside {@code dev} is the documented
     * opt-out and logs {@code HORUS-DB-ROLE-002} at ERROR on every start. A run with {@code horus.bpm.migrate-only}
     * skips the check: that step runs as the schema owner by design.
     *
     * @param check the check on the runtime data source
     * @param database the settings
     * @param environment the host's environment, for the profile
     * @param log where a warning goes
     * @throws IllegalStateException in {@code enforce} mode when the role can disable the triggers
     */
    static void verify(DatabaseRoleCheck check, RuleEngineProperties.Database database, Environment environment, Log log) {
        String mode = database.roleCheck() == null ? "" : database.roleCheck().strip().toLowerCase(java.util.Locale.ROOT);
        if (mode.isEmpty()) {
            mode = environment.matchesProfiles("dev") ? "off" : "enforce";
        }
        if (environment.getProperty("horus.bpm.migrate-only", Boolean.class, false)) {
            return;
        }
        if (!List.of("off", "warn", "enforce").contains(mode)) {
            throw new IllegalStateException("horus.bpm.rules.database.role-check must be warn, enforce or off, was " + mode);
        }
        boolean dev = environment.matchesProfiles("dev");
        if (!dev && !mode.equals("enforce")) {
            log.error("HORUS-DB-ROLE-002: horus.bpm.%s.database.role-check=%s outside the dev profile. The check that the serving database role cannot switch off the append-only and seal triggers is relaxed. This is not for production; set role-check=enforce."
                    .formatted("rules", mode));
        }
        if (mode.equals("off")) {
            return;
        }
        List<String> problems;
        try {
            problems = check.problems();
        } catch (org.springframework.dao.DataAccessException e) {
            if (mode.equals("enforce")) {
                throw new IllegalStateException("The database role check could not run: " + e.getMostSpecificCause().getMessage(), e);
            }
            log.warn("The database role check could not run: " + e.getMostSpecificCause().getMessage());
            return;
        }
        if (problems.isEmpty()) {
            return;
        }
        String message = "HORUS-DB-ROLE-001: " + String.join("; ", problems) + ". It can switch off the append-only and seal triggers. Run the "
                + "application as a role that owns nothing and migrate as a separate owner "
                + "(horus.bpm.rules.database.owner-*, docs/DEPLOYING.md, section Two database roles).";
        if (mode.equals("enforce")) {
            throw new IllegalStateException(message);
        }
        log.warn(message);
    }
}
