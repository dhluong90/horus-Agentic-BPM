package io.github.dhluong90.bpm.rules.test;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * The row coverage of one test run (RLE-064, RLE-065, PD-120). It records which rows each test matched, and when the
 * run ends writes {@code rules-coverage.json} and {@code rules-coverage.md}, prints one summary line per rule set and
 * applies {@code horus.rules.coverage.fail-on}. It lives in the root store of the engine's extension context, so there
 * is one per run and it closes when the run does; nothing is static, and two runs in one JVM do not mix.
 *
 * <p>A row is covered only when a test evaluation returned a match with that row. A failed evaluation covers nothing.
 * The report names rule sets by name and canonical hash, rows in document order and tests in name order, with no time
 * or machine detail, so the same tests give the same bytes. A rule set no test loaded is absent: the report is not a
 * list of the files on the class path. It never contains an input value.
 */
// JUnit 5.12 (Spring Boot 3.5) closes only CloseableResource values in a store; JUnit 6 deprecates it for
// AutoCloseable. Implementing both writes the report on either line; JUnit closes the value once.
@SuppressWarnings("deprecation")
final class CoverageRun implements AutoCloseable, ExtensionContext.Store.CloseableResource {

    /** The setting that decides what fails the build: {@code none} (default), {@code unique} or {@code any}. */
    static final String FAIL_ON = "horus.rules.coverage.fail-on";

    /** The folder the report is written to, relative to the working directory of the build. */
    static final String REPORT_DIR = "horus.rules.coverage.report-dir";

    /** The default report folder, the module's build output. */
    static final String DEFAULT_REPORT_DIR = "target/horus-rules-coverage";

    /** What fails the build. */
    enum FailOn {
        /** Nothing: the report only. */
        NONE,
        /** An uncovered row of a {@code unique} table. */
        UNIQUE,
        /** Any uncovered row. */
        ANY;

        static FailOn parse(String text) {
            if (text == null || text.isBlank() || text.equals("none")) {
                return NONE;
            }
            return switch (text) {
                case "unique" -> UNIQUE;
                case "any" -> ANY;
                default -> throw new IllegalStateException(FAIL_ON + " must be none, unique or any, was '" + text + "'");
            };
        }
    }

    private final Path reportDir;
    private final String failOnText;
    private final PrintStream out;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    CoverageRun(Path reportDir, String failOnText, PrintStream out) {
        this.reportDir = Objects.requireNonNull(reportDir, "reportDir must not be null");
        this.failOnText = failOnText;
        this.out = Objects.requireNonNull(out, "out must not be null");
    }

    // getOrComputeIfAbsent exists in JUnit 5.12 (Spring Boot 3.5) and 6; JUnit 6 deprecates it for
    // computeIfAbsent(K, Function, Class), which 5.12 lacks, so the deprecation is accepted.
    @SuppressWarnings("deprecation")
    static CoverageRun of(ExtensionContext context) {
        return context.getRoot().getStore(ExtensionContext.Namespace.create(CoverageRun.class))
                .getOrComputeIfAbsent(CoverageRun.class, key -> new CoverageRun(
                        Path.of(context.getConfigurationParameter(REPORT_DIR).orElse(DEFAULT_REPORT_DIR)),
                        context.getConfigurationParameter(FAIL_ON).orElse(null), System.out), CoverageRun.class);
    }

    /** The rows a rule set has and the tests that matched each. */
    private static final class Entry {
        final String name;
        final String hash;
        final String hitPolicy;
        final List<String> rows;
        final Map<String, Set<String>> testsByRow = new ConcurrentHashMap<>();

        Entry(LoadedRuleSet loaded) {
            this.name = loaded.rules().name();
            this.hash = loaded.hash();
            this.hitPolicy = loaded.hitPolicy();
            this.rows = loaded.ruleIds();
        }
    }

    /** Registers a rule set as loaded by a test class, so it is in the report even when no test matches a row. */
    void loaded(LoadedRuleSet loaded) {
        entries.computeIfAbsent(loaded.rules().name() + "\u0000" + loaded.hash(), key -> new Entry(loaded));
    }

    void matched(LoadedRuleSet loaded, String testId, Collection<String> ruleIds) {
        loaded(loaded);
        Entry entry = entries.get(loaded.rules().name() + "\u0000" + loaded.hash());
        for (String id : ruleIds) {
            entry.testsByRow.computeIfAbsent(id, key -> ConcurrentHashMap.newKeySet()).add(testId);
        }
    }

    /** One rule set of the report. */
    record RuleSetCoverage(String name, String hash, String hitPolicy, List<String> rows,
            Map<String, List<String>> testsByRow) {

        List<String> uncovered() {
            return rows.stream().filter(row -> testsByRow.getOrDefault(row, List.of()).isEmpty()).toList();
        }

        long covered() {
            return rows.size() - uncovered().size();
        }

        String summary() {
            List<String> uncovered = uncovered();
            return name + ": " + covered() + " of " + rows.size() + " rows covered"
                    + (uncovered.isEmpty() ? "" : "; " + String.join(", ", uncovered) + " not covered");
        }
    }

    /** @return the rule sets some test loaded, by name and hash */
    List<RuleSetCoverage> snapshot() {
        List<RuleSetCoverage> sets = new ArrayList<>();
        for (Entry entry : entries.values()) {
            Map<String, List<String>> tests = new TreeMap<>();
            entry.testsByRow.forEach((row, ids) -> tests.put(row, new TreeSet<>(ids).stream().toList()));
            sets.add(new RuleSetCoverage(entry.name, entry.hash, entry.hitPolicy, entry.rows, tests));
        }
        sets.sort(Comparator.comparing(RuleSetCoverage::name).thenComparing(RuleSetCoverage::hash));
        return List.copyOf(sets);
    }

    @Override
    public void close() throws IOException {
        List<RuleSetCoverage> sets = snapshot();
        if (sets.isEmpty()) {
            return;
        }
        Files.createDirectories(reportDir);
        Files.writeString(reportDir.resolve("rules-coverage.json"), json(sets), StandardCharsets.UTF_8);
        Files.writeString(reportDir.resolve("rules-coverage.md"), markdown(sets), StandardCharsets.UTF_8);
        sets.forEach(set -> out.println("Rule coverage " + set.summary()));
        // The report is written in every case; the setting only decides whether the run fails.
        FailOn failOn = FailOn.parse(failOnText);
        String failure = failure(sets, failOn);
        if (failure != null) {
            throw new IllegalStateException(failure);
        }
    }

    static String failure(List<RuleSetCoverage> sets, FailOn failOn) {
        if (failOn == FailOn.NONE) {
            return null;
        }
        List<String> uncovered = new ArrayList<>();
        for (RuleSetCoverage set : sets) {
            if (failOn == FailOn.ANY || set.hitPolicy().equals("unique")) {
                if (!set.uncovered().isEmpty()) {
                    uncovered.add(set.name() + " " + String.join(", ", set.uncovered()));
                }
            }
        }
        if (uncovered.isEmpty()) {
            return null;
        }
        return "Rule coverage is below what " + FAIL_ON + "=" + failOn.name().toLowerCase(java.util.Locale.ROOT)
                + " asks for; rows no test matched: " + String.join("; ", uncovered) + ". See rules-coverage.md";
    }

    static String json(List<RuleSetCoverage> sets) {
        StringBuilder text = new StringBuilder("{\n  \"ruleSets\": [");
        for (int i = 0; i < sets.size(); i++) {
            RuleSetCoverage set = sets.get(i);
            text.append(i == 0 ? "\n" : ",\n").append("    {\n")
                    .append("      \"name\": ").append(quote(set.name())).append(",\n")
                    .append("      \"hash\": ").append(quote(set.hash())).append(",\n")
                    .append("      \"hitPolicy\": ").append(quote(set.hitPolicy())).append(",\n")
                    .append("      \"rowsCovered\": ").append(set.covered()).append(",\n")
                    .append("      \"rowsTotal\": ").append(set.rows().size()).append(",\n")
                    .append("      \"rows\": [");
            for (int j = 0; j < set.rows().size(); j++) {
                String row = set.rows().get(j);
                List<String> tests = set.testsByRow().getOrDefault(row, List.of());
                text.append(j == 0 ? "\n" : ",\n").append("        { \"id\": ").append(quote(row))
                        .append(", \"covered\": ").append(!tests.isEmpty()).append(", \"tests\": [");
                for (int k = 0; k < tests.size(); k++) {
                    text.append(k == 0 ? "" : ", ").append(quote(tests.get(k)));
                }
                text.append("] }");
            }
            text.append("\n      ]\n    }");
        }
        return text.append("\n  ]\n}\n").toString();
    }

    static String markdown(List<RuleSetCoverage> sets) {
        StringBuilder text = new StringBuilder("# Rule coverage\n");
        for (RuleSetCoverage set : sets) {
            text.append("\n## ").append(set.name()).append("\n\n")
                    .append(set.summary()).append(".\n\n")
                    .append("Hit policy ").append(set.hitPolicy()).append(", canonical hash `").append(set.hash())
                    .append("`.\n\n| Row | Covered | Tests |\n|---|---|---|\n");
            for (String row : set.rows()) {
                List<String> tests = set.testsByRow().getOrDefault(row, List.of());
                text.append("| ").append(row).append(" | ").append(tests.isEmpty() ? "not covered" : "covered")
                        .append(" | ").append(String.join("<br>", tests.stream().map(t -> "`" + t + "`").toList()))
                        .append(" |\n");
            }
        }
        return text.toString();
    }

    private static String quote(String value) {
        StringBuilder text = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> text.append("\\\"");
                case '\\' -> text.append("\\\\");
                case '\n' -> text.append("\\n");
                case '\r' -> text.append("\\r");
                case '\t' -> text.append("\\t");
                default -> {
                    if (c < 0x20) {
                        text.append(String.format("\\u%04x", (int) c));
                    } else {
                        text.append(c);
                    }
                }
            }
        }
        return text.append('"').toString();
    }
}
