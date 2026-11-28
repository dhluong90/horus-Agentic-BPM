package io.github.dhluong90.bpm.examples.ruleengineonly;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * The migration scripts the rule engine ships, read from its classpath location, so a test that checks the history
 * the engine wrote does not hard-code how many migrations there are.
 */
final class EngineMigrations {

    private static final String PATTERN = "classpath*:io/github/dhluong90/bpm/rules/postgres/migration/V*__*.sql";

    private EngineMigrations() {
    }

    /** The script file names in version order, such as {@code V1__baseline.sql}. */
    static List<String> scripts() {
        try {
            return java.util.Arrays.stream(new PathMatchingResourcePatternResolver().getResources(PATTERN))
                    .map(Resource::getFilename)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparingInt(EngineMigrations::version))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The history rows a host that already had tables gets: the baseline at 0, then one row per script. */
    static List<String> historyAfterBaselineAt0() {
        List<String> rows = new java.util.ArrayList<>();
        rows.add("0 Rule Engine baseline");
        scripts().forEach(script -> rows.add(version(script) + " " + script));
        return rows;
    }

    /** The highest shipped version, which the health check reports as the schema version. */
    static String latestVersion() {
        List<String> all = scripts();
        return String.valueOf(version(all.get(all.size() - 1)));
    }

    private static int version(String script) {
        return Integer.parseInt(script.substring(1, script.indexOf("__")));
    }
}
