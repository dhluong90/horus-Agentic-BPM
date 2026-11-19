package io.github.dhluong90.bpm.rules.internal.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.SourceFormat;
import io.github.dhluong90.bpm.rules.internal.read.RuleSetReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.stream.LongStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Property: for generated {@code unique} and {@code collect} tables, the same inputs always give the same
 * outcome, outputs and matched rules, also when the table is compiled again from its canonical form.
 * 1,000 tries from fixed seeds, so a failure names the seed that reproduces it.
 */
class HitPolicyDeterminismTest {

    static LongStream seeds() {
        return LongStream.range(0, 1000);
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void TR_DET_01_unique_and_collect_tables_are_deterministic(long seed) {
        // Given a generated table and generated inputs
        Random random = new Random(seed);
        String policy = random.nextBoolean() ? "unique" : "collect";
        String yaml = table(random, policy);
        var parsed = RuleSetReader.read(SourceFormat.YAML, yaml.getBytes(StandardCharsets.UTF_8));
        CompiledRuleSet first = CompiledRuleSet.compile(parsed.model());
        CompiledRuleSet recompiled = CompiledRuleSet.compile(RuleSetReader.readCanonical(parsed.canonicalJson()));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("score", random.nextInt(1000));
        values.put("segment", random.nextInt(5) == 0 ? null : "S" + random.nextInt(4));
        DecisionInput input = DecisionInput.of(values);

        // When evaluated three times
        DecisionResult.Outcome a = first.evaluate(input);
        DecisionResult.Outcome b = first.evaluate(input);
        DecisionResult.Outcome c = recompiled.evaluate(input);

        // Then
        assertThat(b).isEqualTo(a);
        assertThat(c).isEqualTo(a);
        if (policy.equals("unique") && a instanceof DecisionResult.Failed failed) {
            assertThat(failed.code()).isEqualTo("UNIQUE_HIT_VIOLATION");
            assertThat(failed.matchedRuleIds().size()).isNotEqualTo(1);
        }
        if (a instanceof DecisionResult.Matched matched && policy.equals("unique")) {
            assertThat(matched.ruleIds()).hasSize(1);
        }
    }

    private static String table(Random random, String policy) {
        StringBuilder yaml = new StringBuilder("""
                schemaVersion: 1
                ruleSet: generated
                hitPolicy: %s
                inputs:
                  - { name: score, type: integer }
                  - { name: segment, type: string }
                outputs:
                """.formatted(policy));
        yaml.append(policy.equals("collect")
                ? "  - { name: codes, type: list<string> }\n  - { name: points, type: integer, collect: sum }\n"
                    + "  - { name: best, type: integer, collect: max }\n  - { name: hits, type: integer, collect: count }\n"
                : "  - { name: codes, type: string }\n  - { name: points, type: integer }\n");
        yaml.append("rules:\n");
        int rows = 1 + random.nextInt(8);
        for (int i = 0; i < rows; i++) {
            String when = switch (random.nextInt(4)) {
                case 0 -> "{ score: \">= " + random.nextInt(1000) + "\" }";
                case 1 -> "{ segment: \"== 'S" + random.nextInt(4) + "'\" }";
                case 2 -> "{ score: \"< " + random.nextInt(1000) + "\", segment: \"!= 'S" + random.nextInt(4) + "'\" }";
                default -> "{}";
            };
            String then = policy.equals("collect")
                    ? "{ codes: \"'C" + i + "'\", points: " + random.nextInt(50) + ", best: " + random.nextInt(9)
                        + ", hits: 1 }"
                    : "{ codes: \"'C" + i + "'\", points: " + random.nextInt(50) + " }";
            yaml.append("  - { id: R").append(i).append(", when: ").append(when).append(", then: ").append(then).append(" }\n");
        }
        return yaml.toString();
    }
}
