package io.github.dhluong90.bpm.examples.benchmarks;

import java.nio.charset.StandardCharsets;

/** The fee tables of the published performance conditions (TR-PERF-01, TR-PERF-02, TR-PERF-13). */
public final class FeeTables {

    private FeeTables() {
    }

    /**
     * A {@code first} table of {@code rows} rows over a card tier and a monthly transaction count: row {@code i} charges
     * {@code i * 100} VND to a Silver or Gold card with at least {@code rows - i} transactions, and the last row is the
     * default of 50,000 VND. A Classic card with no transaction reads every row and gets the default.
     *
     * @param ruleSet the rule set name
     * @param rows the number of rows, at least 2
     * @return the YAML document
     */
    public static byte[] firstHit(String ruleSet, int rows) {
        StringBuilder yaml = new StringBuilder("""
                schemaVersion: 1
                ruleSet: %s
                description: Monthly card fee by tier and transaction count
                hitPolicy: first
                inputs:
                  - { name: tier, type: string }
                  - { name: txCount, type: integer }
                outputs:
                  - { name: fee, type: money }
                rules:
                """.formatted(ruleSet));
        for (int i = 1; i < rows; i++) {
            yaml.append("  - id: R").append(i).append('\n')
                    .append("    when: { tier: \"in ['SILVER', 'GOLD']\", txCount: \">= ").append(rows - i)
                    .append("\" }\n")
                    .append("    then: { fee: \"").append(i * 100).append(" VND\" }\n");
        }
        yaml.append("  - id: R").append(rows).append("\n    when: {}\n    then: { fee: \"50000 VND\" }\n");
        return yaml.toString().getBytes(StandardCharsets.UTF_8);
    }
}
