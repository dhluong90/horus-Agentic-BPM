package com.horus.bpm.testing.property;

import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

/**
 * Runs a property as JUnit 5 dynamic tests, one per try: return the stream from a
 * {@code @TestFactory} method whose name carries the specification id. Test scope only; never published.
 *
 * <pre>{@code
 * @TestFactory
 * Stream<DynamicTest> RLE_001_money_addition_is_exact() {
 *     return Property.tries(1_000).forAll(CURRENCY, Gen.longs(-1_000, 1_000), (currency, minor) -> ...);
 * }
 * }</pre>
 *
 * <p><b>Seeds.</b> A run starts from a fixed seed, so every build draws the same values. Try {@code i}
 * draws from its own generator seeded {@code seed + i * GOLDEN_GAMMA}, so one try can be replayed alone.
 * <b>Exhaustive walk.</b> When every generator is finite and all their combinations fit in the tries,
 * the first tries walk every combination; the remaining tries draw at random. <b>No shrinking.</b> A
 * failing try throws an {@link AssertionError} that names the try, the seeds, the sample and the
 * system properties that replay it, with the original failure as its cause.
 *
 * <p>System properties, all optional: {@value #SEED} (a long, or {@code random} for a fresh seed that
 * a failure prints), {@value #TRIES} (replaces the number of tries of every property, for example
 * 100000 in a nightly run) and {@value #TRY} (runs only that try).
 */
public final class Property {

    /** System property: the seed of the run, a long or {@code random}. */
    public static final String SEED = "bpm.property.seed";

    /** System property: the number of tries of every property, replacing the one in the test. */
    public static final String TRIES = "bpm.property.tries";

    /** System property: run only this try number, to replay a failure. */
    public static final String TRY = "bpm.property.try";

    /** The seed of a run when {@value #SEED} is not set. */
    public static final long DEFAULT_SEED = 0x5EED_2026_1119L;

    private static final long GOLDEN_GAMMA = 0x9E37_79B9_7F4A_7C15L;
    private static final int MAX_SAMPLE_LENGTH = 500;

    private final int tries;
    private final long seed;

    private Property(int tries, long seed) {
        if (tries < 1) {
            throw new IllegalArgumentException("tries must be >= 1: " + tries);
        }
        this.tries = tries;
        this.seed = seed;
    }

    /** A property checked {@code tries} times, unless {@value #TRIES} says otherwise. */
    public static Property tries(int tries) {
        String override = System.getProperty(TRIES);
        return new Property(override == null || override.isBlank() ? tries : Integer.parseInt(override.trim()),
                seedFromSystem());
    }

    /** The same property with a fixed seed, ignoring {@value #SEED}; for tests of this helper. */
    public Property seed(long fixedSeed) {
        return new Property(tries, fixedSeed);
    }

    /** The number of tries this property runs. */
    public int tries() {
        return tries;
    }

    /** The seed this property starts from. */
    public long seed() {
        return seed;
    }

    /** Checks a property of one value. */
    public <A> Stream<DynamicTest> forAll(Gen<A> a, Consumer<? super A> check) {
        return run(List.of(a), values -> check.accept(cast(values[0])));
    }

    /** Checks a property of two values. */
    public <A, B> Stream<DynamicTest> forAll(Gen<A> a, Gen<B> b, BiConsumer<? super A, ? super B> check) {
        return run(List.of(a, b), values -> check.accept(cast(values[0]), cast(values[1])));
    }

    /** Checks a property of three values. */
    public <A, B, C> Stream<DynamicTest> forAll(Gen<A> a, Gen<B> b, Gen<C> c, Check3<A, B, C> check) {
        return run(List.of(a, b, c), values -> check.accept(cast(values[0]), cast(values[1]), cast(values[2])));
    }

    /** Checks a property of four values. */
    public <A, B, C, D> Stream<DynamicTest> forAll(Gen<A> a, Gen<B> b, Gen<C> c, Gen<D> d, Check4<A, B, C, D> check) {
        return run(List.of(a, b, c, d),
                values -> check.accept(cast(values[0]), cast(values[1]), cast(values[2]), cast(values[3])));
    }

    /** A check of three values. */
    @FunctionalInterface
    public interface Check3<A, B, C> {
        void accept(A a, B b, C c);
    }

    /** A check of four values. */
    @FunctionalInterface
    public interface Check4<A, B, C, D> {
        void accept(A a, B b, C c, D d);
    }

    /** The values of try {@code index}: a combination of the exhaustive walk, or a seeded draw. */
    Object[] sample(List<? extends Gen<?>> gens, int index) {
        long combinations = combinations(gens);
        Object[] values = new Object[gens.size()];
        if (index < combinations) {
            long rest = index;
            for (int i = gens.size() - 1; i >= 0; i--) {
                List<?> all = gens.get(i).values();
                values[i] = all.get((int) (rest % all.size()));
                rest /= all.size();
            }
            return values;
        }
        RandomGenerator random = new SplittableRandom(trySeed(index));
        for (int i = 0; i < gens.size(); i++) {
            values[i] = gens.get(i).next(random);
        }
        return values;
    }

    /** Number of exhaustive tries: every combination when all generators are finite and it fits, else 0. */
    long combinations(List<? extends Gen<?>> gens) {
        long product = 1;
        for (Gen<?> gen : gens) {
            if (!gen.isFinite()) {
                return 0;
            }
            product *= gen.values().size();
            if (product > tries) {
                return 0;
            }
        }
        return product;
    }

    long trySeed(int index) {
        return seed + index * GOLDEN_GAMMA;
    }

    private Stream<DynamicTest> run(List<? extends Gen<?>> gens, Consumer<Object[]> check) {
        String only = System.getProperty(TRY);
        LongStream indexes = only == null || only.isBlank()
                ? LongStream.range(0, tries)
                : LongStream.of(Integer.parseInt(only.trim()));
        return indexes.mapToObj(i -> (int) i).map(index -> DynamicTest.dynamicTest(
                "try " + index + " of " + tries, () -> {
                    Object[] values = sample(gens, index);
                    try {
                        check.accept(values);
                    } catch (Throwable failure) {
                        throw new AssertionError(failureMessage(index, values, failure), failure);
                    }
                }));
    }

    String failureMessage(int index, Object[] values, Throwable failure) {
        return "Property failed on try " + index + " of " + tries
                + " (seed " + seed + ", try seed " + trySeed(index) + ")"
                + "\n  sample: " + describe(values)
                + "\n  replay: -D" + SEED + "=" + seed + " -D" + TRY + "=" + index
                + "\n  cause: " + failure;
    }

    static String describe(Object[] values) {
        String text = Arrays.deepToString(Arrays.stream(values).map(Property::quote).toArray());
        return text.length() <= MAX_SAMPLE_LENGTH ? text : text.substring(0, MAX_SAMPLE_LENGTH) + "... ("
                + text.length() + " characters)";
    }

    private static Object quote(Object value) {
        return value instanceof String text ? "\"" + text + "\"" : value;
    }

    private static long seedFromSystem() {
        String value = System.getProperty(SEED);
        if (value == null || value.isBlank()) {
            return DEFAULT_SEED;
        }
        if (value.trim().equalsIgnoreCase("random")) {
            return new SplittableRandom().nextLong();
        }
        return Long.decode(value.trim());
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }
}
