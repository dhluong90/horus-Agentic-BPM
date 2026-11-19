package io.github.dhluong90.bpm.rules.internal.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.SourceFormat;
import io.github.dhluong90.bpm.rules.internal.read.RuleSetReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.function.BiPredicate;
import java.util.stream.LongStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** QA review of #18: generated collect and unique tables checked against an independent oracle. */
class HitPolicyOracleQaTest {

    record Row(String id, BiPredicate<Integer, String> when, String code, int points, int best) {
    }

    static LongStream seeds() {
        return LongStream.range(0, 1000);
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void TR_DET_01_collect_and_unique_agree_with_an_oracle(long seed) {
        // Given
        Random random = new Random(seed);
        boolean collect = random.nextBoolean();
        List<Row> rows = new ArrayList<>();
        StringBuilder yaml = new StringBuilder("schemaVersion: 1\nruleSet: g\nhitPolicy: " + (collect ? "collect" : "unique")
                + "\ninputs:\n  - { name: score, type: integer }\n  - { name: segment, type: string }\noutputs:\n");
        yaml.append(collect
                ? "  - { name: codes, type: list<string> }\n  - { name: points, type: integer, collect: sum }\n"
                    + "  - { name: best, type: integer, collect: max }\n  - { name: low, type: integer, collect: min }\n"
                    + "  - { name: hits, type: integer, collect: count }\n"
                : "  - { name: codes, type: string }\n  - { name: points, type: integer }\n");
        yaml.append("rules:\n");
        int n = 1 + random.nextInt(10);
        for (int i = 0; i < n; i++) {
            int k = random.nextInt(1000);
            int s = random.nextInt(4);
            String when;
            BiPredicate<Integer, String> p;
            switch (random.nextInt(4)) {
                case 0 -> { when = "{ score: \">= " + k + "\" }"; p = (sc, sg) -> sc >= k; }
                case 1 -> { when = "{ segment: \"== 'S" + s + "'\" }"; p = (sc, sg) -> Objects.equals(sg, "S" + s); }
                case 2 -> { when = "{ score: \"< " + k + "\", segment: \"!= 'S" + s + "'\" }";
                    p = (sc, sg) -> sc < k && !Objects.equals(sg, "S" + s); }
                default -> { when = "{}"; p = (sc, sg) -> true; }
            }
            int points = random.nextInt(101) - 50;
            int best = random.nextInt(9);
            rows.add(new Row("R" + i, p, "C" + i, points, best));
            yaml.append("  - { id: R").append(i).append(", when: ").append(when).append(", then: { codes: \"'C").append(i)
                    .append("'\", points: ").append(points).append(collect ? ", best: " + best + ", low: " + best + ", hits: 1" : "")
                    .append(" } }\n");
        }
        CompiledRuleSet table = CompiledRuleSet.compile(
                RuleSetReader.read(SourceFormat.YAML, yaml.toString().getBytes(StandardCharsets.UTF_8)).model());
        int score = random.nextInt(1000);
        String segment = random.nextInt(5) == 0 ? null : "S" + random.nextInt(4);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("score", score);
        values.put("segment", segment);

        // When
        DecisionResult.Outcome outcome = table.evaluate(DecisionInput.of(values));

        // Then
        List<Row> hit = rows.stream().filter(r -> r.when().test(score, segment)).toList();
        List<String> ids = hit.stream().map(Row::id).toList();
        if (collect) {
            if (hit.isEmpty()) {
                assertThat(outcome).isInstanceOf(DecisionResult.NoMatch.class);
                return;
            }
            DecisionResult.Matched m = (DecisionResult.Matched) outcome;
            assertThat(m.ruleIds()).isEqualTo(ids);
            assertThat(m.outputs().get("codes")).isEqualTo(hit.stream().map(Row::code).toList());
            assertThat((BigDecimal) m.outputs().get("points"))
                    .isEqualByComparingTo(BigDecimal.valueOf(hit.stream().mapToInt(Row::points).sum()));
            assertThat((BigDecimal) m.outputs().get("best"))
                    .isEqualByComparingTo(BigDecimal.valueOf(hit.stream().mapToInt(Row::best).max().orElseThrow()));
            assertThat((BigDecimal) m.outputs().get("low"))
                    .isEqualByComparingTo(BigDecimal.valueOf(hit.stream().mapToInt(Row::best).min().orElseThrow()));
            assertThat((BigDecimal) m.outputs().get("hits")).isEqualByComparingTo(BigDecimal.valueOf(hit.size()));
        } else if (hit.size() == 1) {
            DecisionResult.Matched m = (DecisionResult.Matched) outcome;
            assertThat(m.ruleIds()).isEqualTo(ids);
            assertThat(m.outputs()).containsEntry("codes", hit.getFirst().code());
        } else {
            DecisionResult.Failed f = (DecisionResult.Failed) outcome;
            assertThat(f.code()).isEqualTo("UNIQUE_HIT_VIOLATION");
            assertThat(f.matchedRuleIds()).isEqualTo(ids);
        }
    }
}
