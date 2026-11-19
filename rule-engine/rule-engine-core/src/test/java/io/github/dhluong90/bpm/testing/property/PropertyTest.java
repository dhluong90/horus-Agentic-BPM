package io.github.dhluong90.bpm.testing.property;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** The seeded generator and runner behave as their Javadoc says. Proves no specification id. */
class PropertyTest {

    @Test
    void same_seed_draws_the_same_values() {
        Gen<Long> gen = Gen.longs(-1_000_000, 1_000_000);
        Property property = Property.tries(50).seed(42);

        for (int i = 0; i < 50; i++) {
            assertThat(property.sample(List.of(gen), i)).isEqualTo(property.sample(List.of(gen), i));
        }
        assertThat(property.sample(List.of(gen), 7)).isNotEqualTo(Property.tries(50).seed(43).sample(List.of(gen), 7));
    }

    @Test
    void runs_the_configured_number_of_tries_as_dynamic_tests() {
        List<DynamicTest> tests = Property.tries(1_000).seed(1).forAll(Gen.ints(0, 9), n -> { }).toList();

        assertThat(tests).hasSize(1_000);
        assertThat(tests.getFirst().getDisplayName()).isEqualTo("try 0 of 1000");
    }

    @Test
    void finite_generators_are_walked_exhaustively_first() {
        Gen<Object> threeValued = Gen.<Object>of(Boolean.TRUE, Boolean.FALSE, "x").orNull(0.3);
        Property property = Property.tries(1_000).seed(1);
        List<Gen<Object>> gens = List.of(threeValued, threeValued);

        Set<List<Object>> seen = new HashSet<>();
        for (int i = 0; i < 16; i++) {
            seen.add(Arrays.asList(property.sample(gens, i)));
        }

        assertThat(property.combinations(gens)).isEqualTo(16);
        assertThat(seen).hasSize(16);
    }

    @Test
    void no_exhaustive_walk_when_the_combinations_exceed_the_tries() {
        Gen<Integer> digits = Gen.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);

        assertThat(Property.tries(50).seed(1).combinations(List.of(digits, digits))).isZero();
        assertThat(Property.tries(50).seed(1).combinations(List.of(digits, Gen.ints(0, 1)))).isZero();
    }

    @Test
    void a_failing_try_reports_its_seed_sample_and_replay_flags() throws Throwable {
        List<DynamicTest> tests = Property.tries(100).seed(7)
                .forAll(Gen.ints(0, 1_000), n -> assertThat(n).isLessThan(0)).toList();
        Executable first = tests.getFirst().getExecutable();

        assertThatThrownBy(first::execute)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Property failed on try 0 of 100")
                .hasMessageContaining("seed 7")
                .hasMessageContaining("-Dbpm.property.seed=7 -Dbpm.property.try=0")
                .hasMessageContaining("sample: [")
                .hasCauseInstanceOf(AssertionError.class);
    }

    @Test
    void longs_stay_in_range_and_hit_every_edge() {
        Gen<Long> gen = Gen.longs(-5, 1_000_000);
        RandomGenerator random = new SplittableRandom(3);
        Set<Long> edges = new HashSet<>();

        for (int i = 0; i < 2_000; i++) {
            long value = gen.next(random);
            assertThat(value).isBetween(-5L, 1_000_000L);
            if (Set.of(-5L, 1_000_000L, 0L, 1L, -1L).contains(value)) {
                edges.add(value);
            }
        }

        assertThat(edges).containsExactlyInAnyOrder(-5L, 1_000_000L, 0L, 1L, -1L);
    }

    @Test
    void longs_cover_the_whole_long_range() {
        Gen<Long> gen = Gen.longs(Long.MIN_VALUE, Long.MAX_VALUE);
        RandomGenerator random = new SplittableRandom(5);

        for (int i = 0; i < 100; i++) {
            gen.next(random);
        }
        assertThat(Gen.longs(Long.MAX_VALUE - 1, Long.MAX_VALUE).next(random)).isGreaterThanOrEqualTo(Long.MAX_VALUE - 1);
    }

    @Test
    void decimals_keep_their_scale_and_bounds() {
        Gen<BigDecimal> gen = Gen.decimals("-10", "10", 6);
        RandomGenerator random = new SplittableRandom(11);

        for (int i = 0; i < 1_000; i++) {
            BigDecimal value = gen.next(random);
            assertThat(value.scale()).isEqualTo(6);
            assertThat(value).isBetween(new BigDecimal("-10"), new BigDecimal("10"));
        }
    }

    @Test
    void strings_respect_their_length_and_alphabet() {
        RandomGenerator random = new SplittableRandom(13);

        for (int i = 0; i < 1_000; i++) {
            assertThat(Gen.strings(80).next(random)).hasSizeLessThanOrEqualTo(80);
            assertThat(Gen.alphaStrings(6).next(random)).matches("[a-zA-Z]{0,6}");
        }
    }

    @Test
    void or_null_and_list_and_one_of_behave() {
        RandomGenerator random = new SplittableRandom(17);
        Gen<String> maybe = Gen.of("a").orNull(0.5);
        Gen<List<String>> lists = Gen.of("x", "y").list(40);
        Gen<Object> mixed = Gen.oneOf(Gen.of("s"), Gen.ints(1, 1));
        Set<Object> seen = new HashSet<>();

        for (int i = 0; i < 200; i++) {
            seen.add(maybe.next(random));
            assertThat(lists.next(random)).hasSizeLessThanOrEqualTo(40);
            seen.add(mixed.next(random));
        }

        assertThat(seen).containsExactlyInAnyOrder("a", null, "s", 1);
        assertThat(Gen.oneOf(Gen.of("a"), Gen.of("b")).values()).containsExactly("a", "b");
    }
}
