package io.github.dhluong90.bpm.testing.property;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.random.RandomGenerator;

/**
 * A seeded generator of test values for {@link Property}. Test scope only; never published.
 *
 * <p>A generator draws one value from a {@link RandomGenerator}; the same seed always gives the same
 * values. A finite generator also knows all its values, so {@link Property} can walk every
 * combination before it draws at random. Number generators mix in their edge cases (the bounds,
 * zero, one and minus one when in range) one draw in {@value #EDGE_ONE_IN}. There is no shrinking:
 * a failure reports the seed and the sample instead.
 *
 * @param <T> the type of the values
 */
public final class Gen<T> {

    /** One draw in this many picks an edge case of a number generator. */
    static final int EDGE_ONE_IN = 10;

    private final Function<RandomGenerator, T> sampler;
    private final List<T> values;

    private Gen(Function<RandomGenerator, T> sampler, List<T> values) {
        this.sampler = Objects.requireNonNull(sampler, "sampler");
        this.values = values;
    }

    /** A generator from a sampling function; not finite. */
    public static <T> Gen<T> from(Function<RandomGenerator, T> sampler) {
        return new Gen<>(sampler, null);
    }

    /** One of the given values, uniformly; finite. {@code null} is not allowed here, use {@link #orNull}. */
    @SafeVarargs
    public static <T> Gen<T> of(T... values) {
        List<T> all = List.of(values);
        if (all.isEmpty()) {
            throw new IllegalArgumentException("at least one value is needed");
        }
        return new Gen<>(random -> all.get(random.nextInt(all.size())), all);
    }

    /** A long between {@code min} and {@code max}, both inclusive, with edge cases mixed in. */
    public static Gen<Long> longs(long min, long max) {
        if (min > max) {
            throw new IllegalArgumentException("min " + min + " is greater than max " + max);
        }
        Set<Long> edges = new LinkedHashSet<>(List.of(min, max));
        for (long edge : new long[] {0, 1, -1}) {
            if (edge >= min && edge <= max) {
                edges.add(edge);
            }
        }
        List<Long> edgeList = List.copyOf(edges);
        return from(random -> random.nextInt(EDGE_ONE_IN) == 0
                ? edgeList.get(random.nextInt(edgeList.size()))
                : inclusive(random, min, max));
    }

    /** An int between {@code min} and {@code max}, both inclusive, with edge cases mixed in. */
    public static Gen<Integer> ints(int min, int max) {
        return longs(min, max).map(Long::intValue);
    }

    /**
     * A decimal of exactly {@code scale} digits after the point, between {@code min} and {@code max}
     * inclusive; the edge cases are the bounds, zero and plus or minus one unit of the last place.
     */
    public static Gen<BigDecimal> decimals(String min, String max, int scale) {
        long low = new BigDecimal(min).setScale(scale).unscaledValue().longValueExact();
        long high = new BigDecimal(max).setScale(scale).unscaledValue().longValueExact();
        return longs(low, high).map(unscaled -> BigDecimal.valueOf(unscaled, scale));
    }

    /** Any text of up to {@code maxLength} UTF-16 units, any {@code char} included (lone surrogates too). */
    public static Gen<String> strings(int maxLength) {
        return text(maxLength, random -> (char) random.nextInt(Character.MAX_VALUE + 1));
    }

    /** Text of up to {@code maxLength} ASCII letters, {@code a-z} and {@code A-Z}. */
    public static Gen<String> alphaStrings(int maxLength) {
        String letters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        return text(maxLength, random -> letters.charAt(random.nextInt(letters.length())));
    }

    /** A value of one of the generators, each chosen with equal chance; finite when all are. */
    @SafeVarargs
    public static <T> Gen<T> oneOf(Gen<? extends T>... choices) {
        List<Gen<? extends T>> all = List.of(choices);
        if (all.isEmpty()) {
            throw new IllegalArgumentException("at least one generator is needed");
        }
        List<T> union = null;
        if (all.stream().allMatch(Gen::isFinite)) {
            Set<T> distinct = new LinkedHashSet<>();
            all.forEach(choice -> distinct.addAll(choice.values));
            union = Collections.unmodifiableList(new ArrayList<>(distinct));
        }
        return new Gen<>(random -> all.get(random.nextInt(all.size())).next(random), union);
    }

    /** Draws one value. */
    public T next(RandomGenerator random) {
        return sampler.apply(random);
    }

    /** A generator of {@code mapper} applied to this one's values; finite when this one is. */
    public <R> Gen<R> map(Function<? super T, ? extends R> mapper) {
        List<R> mapped = null;
        if (isFinite()) {
            List<R> list = new ArrayList<>(values.size());
            values.forEach(value -> list.add(mapper.apply(value)));
            mapped = Collections.unmodifiableList(list);
        }
        return new Gen<>(random -> mapper.apply(next(random)), mapped);
    }

    /** This generator, giving {@code null} with the given probability instead; finite when this one is. */
    public Gen<T> orNull(double probability) {
        if (probability < 0 || probability > 1) {
            throw new IllegalArgumentException("probability must be between 0 and 1: " + probability);
        }
        List<T> withNull = null;
        if (isFinite()) {
            List<T> list = new ArrayList<>(values);
            list.add(null);
            withNull = Collections.unmodifiableList(list);
        }
        return new Gen<>(random -> random.nextDouble() < probability ? null : next(random), withNull);
    }

    /** Lists of zero to {@code maxSize} values of this generator. */
    public Gen<List<T>> list(int maxSize) {
        return from(random -> {
            int size = random.nextInt(maxSize + 1);
            List<T> list = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                list.add(next(random));
            }
            return Collections.unmodifiableList(list);
        });
    }

    /** Whether every value is known, for {@link Property}'s exhaustive walk. */
    boolean isFinite() {
        return values != null;
    }

    /** All values of a finite generator, possibly including {@code null}. */
    List<T> values() {
        if (!isFinite()) {
            throw new IllegalStateException("not a finite generator");
        }
        return values;
    }

    private static Gen<String> text(int maxLength, Function<RandomGenerator, Character> chars) {
        if (maxLength < 0) {
            throw new IllegalArgumentException("maxLength must be >= 0: " + maxLength);
        }
        return from(random -> {
            // the empty text and the longest text are edge cases
            int length = random.nextInt(EDGE_ONE_IN) == 0
                    ? (random.nextBoolean() ? 0 : maxLength)
                    : random.nextInt(maxLength + 1);
            char[] text = new char[length];
            for (int i = 0; i < length; i++) {
                text[i] = chars.apply(random);
            }
            return new String(text);
        });
    }

    private static long inclusive(RandomGenerator random, long min, long max) {
        if (max < Long.MAX_VALUE) {
            return random.nextLong(min, max + 1);
        }
        if (min > Long.MIN_VALUE) {
            return random.nextLong(min - 1, max) + 1;
        }
        return random.nextLong();
    }
}
