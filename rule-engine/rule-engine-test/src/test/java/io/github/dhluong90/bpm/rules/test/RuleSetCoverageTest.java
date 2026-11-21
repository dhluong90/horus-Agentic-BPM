package io.github.dhluong90.bpm.rules.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.test.fixture.BrokenRuleSetFixture;
import io.github.dhluong90.bpm.rules.test.fixture.CardFeeActiveFixture;
import io.github.dhluong90.bpm.rules.test.fixture.CardFeeGoldFixture;
import io.github.dhluong90.bpm.rules.test.fixture.KycRouteFixture;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

/**
 * RLE-064 and RLE-065 (PD-120): the fixtures run through the JUnit launcher as a build runs them, and the report and the
 * threshold are read back. Two classes over card-fee match R1 and R2 and never R3; a third over kyc-route (unique)
 * matches K1 and K2 together, which is UNIQUE_HIT_VIOLATION.
 */
class RuleSetCoverageTest {

    @TempDir
    Path dir;

    /** What one launcher run left behind. */
    record Run(TestExecutionSummary summary, Path reportDir, String console) {

        String json() throws IOException {
            return Files.readString(reportDir.resolve("rules-coverage.json"), StandardCharsets.UTF_8);
        }

        String markdown() throws IOException {
            return Files.readString(reportDir.resolve("rules-coverage.md"), StandardCharsets.UTF_8);
        }

        /** Every message of every failure, its causes and its suppressed failures. */
        String failures() {
            StringBuilder text = new StringBuilder();
            summary.getFailures().forEach(failure -> collect(failure.getException(), text));
            return text.toString();
        }

        private static void collect(Throwable error, StringBuilder text) {
            if (error == null) {
                return;
            }
            text.append('\n').append(error.getMessage());
            collect(error.getCause(), text);
            for (Throwable suppressed : error.getSuppressed()) {
                collect(suppressed, text);
            }
        }
    }

    Run run(String name, String failOn, Class<?>... fixtures) {
        Path reportDir = dir.resolve(name);
        LauncherDiscoveryRequestBuilder request = LauncherDiscoveryRequestBuilder.request()
                .configurationParameter(CoverageRun.REPORT_DIR, reportDir.toString());
        if (failOn != null) {
            request.configurationParameter(CoverageRun.FAIL_ON, failOn);
        }
        for (Class<?> fixture : fixtures) {
            request.selectors(selectClass(fixture));
        }
        LauncherDiscoveryRequest built = request.build();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        PrintStream original = System.out;
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        System.setOut(new PrintStream(console, true, StandardCharsets.UTF_8));
        try {
            LauncherFactory.create().execute(built, listener);
        } finally {
            System.setOut(original);
        }
        return new Run(listener.getSummary(), reportDir, console.toString(StandardCharsets.UTF_8));
    }

    Run standardRun(String name, String failOn) {
        return run(name, failOn, CardFeeGoldFixture.class, CardFeeActiveFixture.class, KycRouteFixture.class);
    }

    @Test
    void RLE_064_coverage_report_names_each_row_no_test_matched() throws IOException {
        Run run = standardRun("report", null);

        assertThat(run.summary().getTestsFailedCount()).isZero();
        String markdown = run.markdown();
        assertThat(markdown).contains("card-fee: 2 of 3 rows covered; R3 not covered")
                .contains("kyc-route: 0 of 2 rows covered");
        // rows in document order, each covered or not, with the tests that matched it
        assertThat(markdown).contains("| R1 | covered | `" + CardFeeGoldFixture.class.getName() + "#gold_customers_pay_nothing` |")
                .contains("| R2 | covered | `" + CardFeeActiveFixture.class.getName()
                        + "#active_silver_cards_pay_the_reduced_fee` |")
                .contains("| R3 | not covered |  |")
                .contains("| K1 | not covered |  |").contains("| K2 | not covered |  |");
        assertThat(markdown.indexOf("| R1 |")).isLessThan(markdown.indexOf("| R2 |"));
        assertThat(markdown.indexOf("| R2 |")).isLessThan(markdown.indexOf("| R3 |"));
        String json = run.json();
        assertThat(json).contains("\"name\": \"card-fee\"").contains("\"rowsCovered\": 2").contains("\"rowsTotal\": 3")
                .contains("{ \"id\": \"R3\", \"covered\": false, \"tests\": [] }")
                .contains("\"name\": \"kyc-route\"").contains("\"hitPolicy\": \"unique\"")
                .contains("{ \"id\": \"K1\", \"covered\": false, \"tests\": [] }");
        assertThat(json).containsPattern("\"hash\": \"[0-9a-f]{64}\"");
        // the failed evaluation of kyc-route (K1 and K2 together) covered nothing, and the report holds no input value
        assertThat(json + markdown).doesNotContain("SILVER").doesNotContain("\"VN\"");
        assertThat(run.console()).contains("Rule coverage card-fee: 2 of 3 rows covered; R3 not covered")
                .contains("Rule coverage kyc-route: 0 of 2 rows covered");
    }

    @Test
    void RLE_064_the_same_tests_give_a_byte_identical_report_and_an_unloaded_rule_set_is_absent() throws IOException {
        Run first = standardRun("first", null);
        Run second = run("second", null, CardFeeActiveFixture.class, KycRouteFixture.class, CardFeeGoldFixture.class);

        assertThat(second.json()).isEqualTo(first.json());
        assertThat(second.markdown()).isEqualTo(first.markdown());
        // same-day-cutoff.yaml is on the class path, but no test loaded it
        assertThat(first.json()).doesNotContain("same-day-cutoff");
    }

    @Test
    void RLE_064_a_file_that_fails_import_fails_the_class_with_the_violations_before_any_test_runs() {
        Run run = run("broken", null, BrokenRuleSetFixture.class);

        assertThat(run.summary().getTestsStartedCount()).isZero();
        assertThat(run.summary().getContainersFailedCount()).isEqualTo(1);
        Throwable failure = run.summary().getFailures().getFirst().getException();
        assertThat(failure.getMessage()).contains("rules/broken.yaml fails import").contains("rule R2")
                .contains("column tier").contains("UNKNOWN_INPUT").contains("creditScoreX");
        assertThat(failure.getCause()).isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(
                e.violations()).singleElement().satisfies(v -> {
                    assertThat(v.ruleId()).isEqualTo("R2");
                    assertThat(v.column()).isEqualTo("tier");
                }));
    }

    @Test
    void RLE_065_uncovered_rows_fail_the_build_only_at_the_configured_threshold() throws IOException {
        // none, and the default: report only, one summary line per rule set
        for (String none : List.of("none", "")) {
            Run run = standardRun("none-" + none.length(), none.isEmpty() ? null : none);
            assertThat(run.summary().getContainersFailedCount()).as("fail-on " + none).isZero();
            assertThat(run.console().lines().filter(line -> line.startsWith("Rule coverage "))).hasSize(2);
            assertThat(run.json()).contains("card-fee");
        }

        // unique: only the unique table is held to full coverage
        Run unique = standardRun("unique", "unique");
        assertThat(unique.summary().getContainersFailedCount()).isEqualTo(1);
        assertThat(unique.failures()).contains("horus.rules.coverage.fail-on=unique").contains("kyc-route K1, K2")
                .doesNotContain("card-fee");
        assertThat(unique.json()).as("the report is written when the build fails").contains("card-fee");

        // any: every uncovered row of every table
        Run any = standardRun("any", "any");
        assertThat(any.summary().getContainersFailedCount()).isEqualTo(1);
        assertThat(any.failures()).contains("card-fee R3").contains("kyc-route K1, K2");
        assertThat(any.markdown()).contains("card-fee: 2 of 3 rows covered");

        // an unknown value fails the run naming the setting, and the report is still written
        Run unknown = standardRun("unknown", "most");
        assertThat(unknown.summary().getContainersFailedCount()).isEqualTo(1);
        assertThat(unknown.failures()).contains("horus.rules.coverage.fail-on must be none, unique or any, was 'most'");
        assertThat(unknown.json()).contains("card-fee");
    }

    @Test
    void RLE_065_a_run_that_covers_every_row_passes_whatever_the_threshold() throws IOException {
        Run run = run("covered", "any", CardFeeGoldFixture.class, CardFeeActiveFixture.class);

        // R3 is still not covered, so any fails; a project with a fixture for R3 would pass
        assertThat(run.failures()).contains("card-fee R3");
        assertThat(CoverageRun.failure(List.of(new CoverageRun.RuleSetCoverage("card-fee", "h", "first",
                List.of("R1", "R2", "R3"), java.util.Map.of("R1", List.of("a"), "R2", List.of("b"), "R3", List.of("c")))),
                CoverageRun.FailOn.ANY)).isNull();
    }

    @Test
    void RLE_064_the_module_is_test_scope_only_with_no_database_no_spring_context_and_no_auto_configuration() {
        assertThat(getClass().getClassLoader()
                .getResource("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"))
                .as("the starter never auto-configures this module").isNull();
        assertThat(isPresent("org.springframework.context.ApplicationContext")).isFalse();
        assertThat(isPresent("org.postgresql.Driver")).isFalse();
        assertThat(isPresent("io.github.dhluong90.bpm.rules.postgres.PostgresRuleEngines")).isFalse();
    }

    private static boolean isPresent(String name) {
        try {
            Class.forName(name, false, RuleSetCoverageTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
