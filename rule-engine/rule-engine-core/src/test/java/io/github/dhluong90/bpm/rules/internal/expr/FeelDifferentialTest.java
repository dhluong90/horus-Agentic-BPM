package io.github.dhluong90.bpm.rules.internal.expr;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.testing.property.Gen;
import io.github.dhluong90.bpm.testing.property.Property;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;
import org.camunda.feel.api.EvaluationResult;
import org.camunda.feel.api.FeelEngineApi;
import org.camunda.feel.api.FeelEngineBuilder;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * RLE-021 (TR-STD-02): every generated well-typed {@code horus-expr-v1} expression gives the same result as its
 * FEEL translation ({@link FeelTranslation}) evaluated by a reference FEEL implementation, with the same inputs and
 * clock. The reference runs in test scope only and never ships (ArchUnit and the root POM's enforcer rule).
 *
 * <p>The generators cover {@code string}, {@code integer}, {@code decimal}, {@code boolean}, {@code date},
 * {@code datetime}, {@code in} lists and {@code matches}, with every input {@code null} one time in four. Money is excluded: it is a
 * HORUS extension with no FEEL type. 1,000 tries per property in CI; the nightly job sets
 * {@code -Dbpm.property.tries=100000}. A failure prints the seed, the try, the v1 text, the FEEL text and both
 * results.
 *
 * <p>Two documented differences are accepted, nothing else:
 * <ul>
 *   <li><b>Strict error mode (PD-066).</b> Where FEEL gives {@code null} for an invalid operation (division by
 *       zero), a v1 rule set fails with {@code EVALUATION_ERROR}. The property accepts an engine failure only when
 *       the reference gives {@code null}.</li>
 *   <li><b>String order.</b> The engine orders strings by Unicode code point, as DMN 1.5 10.3.2.1; the reference
 *       compares UTF-16 code units, which differs only between U+E000 to U+FFFF and characters outside the Basic
 *       Multilingual Plane. The string alphabet leaves that range out; the engine's own order is proved by
 *       {@code RLE_003_collect_min_and_max_order_strings_like_the_lt_operator}.</li>
 *   <li><b>String escapes.</b> The reference reads {@code "\\\""} (a backslash, then a quote) as three
 *       characters, where FEEL reads two. Generated strings hold quotes but no backslash.</li>
 * </ul>
 */
class FeelDifferentialTest {

    /** Inputs: name, slot and type. Names are valid in both languages. */
    enum Input {
        S1("s1", Type.STRING), S2("s2", Type.STRING),
        I1("i1", Type.NUMBER), I2("i2", Type.NUMBER), N1("n1", Type.NUMBER),
        B1("b1", Type.BOOLEAN), B2("b2", Type.BOOLEAN),
        D1("d1", Type.DATE), D2("d2", Type.DATE),
        T1("t1", Type.DATETIME);

        final String text;
        final Type type;

        Input(String text, Type type) {
            this.text = text;
            this.type = type;
        }
    }

    enum Type { STRING, NUMBER, BOOLEAN, DATE, DATETIME }

    static final int MAX_DEPTH = 3;

    /**
     * Code points that order the same by code point and by UTF-16 unit: ASCII, Vietnamese, CJK and an emoji. No
     * backslash: the reference reads the FEEL escape {@code \\} before {@code \"} as two characters.
     */
    static final int[] ALPHABET = {'a', 'b', 'G', 'O', 'L', 'D', '0', '7', ' ', '-', '\'', '"',
        0x1EA1, 0x00E2, 0x0110, 0x4E2D, 0x1F600};

    /**
     * Patterns that the XPath dialect and Java read the same way, over the alphabet: anchors, classes, alternation,
     * repetition and a general category. No backslash escape other than the category, for the reason of
     * {@link #ALPHABET}.
     */
    static final String[] PATTERNS = {"a", "^a", "b$", "^$", "[0-9]+", "G.L", "a|b", "(ab)+", "[^a-z]", "^[ạâ]",
        "\\p{Lu}", "O{2}", "a*b?"};

    static final Instant[] INSTANTS = {Instant.parse("2026-12-01T00:00:00Z"), Instant.parse("2026-11-30T17:00:00Z"),
        Instant.parse("2027-01-15T18:30:00Z"), Instant.parse("2028-02-29T12:00:00Z")};

    static final FeelEngineApi REFERENCE = FeelEngineBuilder.create()
            .withClock(() -> ZonedDateTime.of(2027, 3, 1, 2, 0, 0, 0, ZoneOffset.UTC))
            .build();

    final Evaluator engine = new Evaluator(RoundingMode.HALF_EVEN);

    // ------------------------------------------------------------------ properties

    @TestFactory
    Stream<DynamicTest> RLE_021_v1_expressions_match_their_feel_translation_for_booleans() {
        return Property.tries(1_000).forAll(expressions(Type.BOOLEAN), INPUTS, this::sameResult);
    }

    @TestFactory
    Stream<DynamicTest> RLE_021_v1_expressions_match_their_feel_translation_for_numbers() {
        return Property.tries(1_000).forAll(expressions(Type.NUMBER), INPUTS, this::sameResult);
    }

    @TestFactory
    Stream<DynamicTest> RLE_021_v1_expressions_match_their_feel_translation_for_strings() {
        return Property.tries(1_000).forAll(expressions(Type.STRING), INPUTS, this::sameResult);
    }

    @TestFactory
    Stream<DynamicTest> RLE_021_v1_expressions_match_their_feel_translation_for_dates_and_date_times() {
        return Property.tries(1_000).forAll(Gen.oneOf(expressions(Type.DATE), expressions(Type.DATETIME)), INPUTS,
                this::sameResult);
    }

    @TestFactory
    Stream<DynamicTest> RLE_021_v1_conditions_match_their_feel_translation_when_every_input_is_null() {
        return Property.tries(1_000).forAll(expressions(Type.BOOLEAN), v1 -> sameResult(v1, new Object[Input.values().length]));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "7 / 2                                                 | 3.5",
        "round(2.5, 0)                                         | 2",
        "daysBetween(date('2027-01-01'), date('2027-03-01'))   | 59",
        "matches('ab12', '[0-9]+')                             | true"
    })
    void RLE_021_the_fixed_cases_agree_with_the_reference(String v1, String expected) {
        // When
        Object ours = engine.evaluate(parse(v1.strip()), new Object[Input.values().length]);
        Object theirs = reference(parse(v1.strip()), new Object[Input.values().length]);

        // Then
        assertThat(ours.toString()).isEqualTo(expected.strip());
        assertThat(same(ours, theirs)).as("reference gives %s", theirs).isTrue();
    }

    @Test
    void RLE_021_the_condition_generator_gives_true_false_and_null_and_reaches_every_construct() {
        // Given 2,000 generated conditions with generated inputs, from a fixed seed
        SplittableRandom random = new SplittableRandom(Property.DEFAULT_SEED);
        Map<Object, Integer> outcomes = new HashMap<>();
        Set<String> constructs = new TreeSet<>();
        for (int i = 0; i < 2_000; i++) {
            String v1 = expression(random, Type.BOOLEAN, MAX_DEPTH);
            Object[] slots = INPUTS.next(random);
            for (String construct : List.of(" in [", " not in [", " between ", " is null", " is not null",
                    "round(", "daysBetween(", "matches(", " / ", "not (", " and ", " or ", " == null", "datetime(")) {
                if (v1.contains(construct)) {
                    constructs.add(construct.strip());
                }
            }
            Object value;
            try {
                value = engine.evaluate(parse(v1), slots);
            } catch (EvaluationException e) {
                value = "EVALUATION_ERROR";
            }
            outcomes.merge(value == null ? "null" : value, 1, Integer::sum);
        }

        // Then: no outcome dominates, and every construct of the grammar appears
        assertThat(outcomes.getOrDefault(true, 0)).isGreaterThan(300);
        assertThat(outcomes.getOrDefault(false, 0)).isGreaterThan(300);
        assertThat(outcomes.getOrDefault("null", 0)).isGreaterThan(200);
        assertThat(constructs).hasSize(14);
    }

    // ------------------------------------------------------------------ the check

    void sameResult(String v1, Object[] slots) {
        Expr expr = parse(v1);
        String feel = FeelTranslation.feel(expr);
        EvaluationResult result = evaluateReference(expr, slots);
        Object theirs = result.result();
        Object ours;
        try {
            ours = engine.evaluate(expr, slots);
        } catch (EvaluationException strict) {
            // strict error mode (PD-066): EVALUATION_ERROR where FEEL gives null for the same invalid operation
            assertThat(strict.getMessage()).isEqualTo("Division by zero");
            assertThat(result.getSuppressedFailures().toString())
                    .as("engine failed with '%s'; FEEL %s reported no division by zero", strict.getMessage(), feel)
                    .contains(DIVISION_BY_ZERO);
            return;
        }
        assertThat(same(ours, theirs)).as("v1 %s gave %s; FEEL %s gave %s", v1, ours, feel, theirs).isTrue();
    }

    /** How the reference reports a division by zero among its suppressed failures. */
    static final String DIVISION_BY_ZERO = "divi";

    static Expr parse(String v1) {
        return Parser.parse(v1, name -> {
            for (Input input : Input.values()) {
                if (input.text.equals(name)) {
                    return input.ordinal();
                }
            }
            return -1;
        });
    }

    static Object reference(Expr expr, Object[] slots) {
        return evaluateReference(expr, slots).result();
    }

    static EvaluationResult evaluateReference(Expr expr, Object[] slots) {
        Map<String, Object> variables = new HashMap<>();
        for (Input input : Input.values()) {
            Object value = slots[input.ordinal()];
            variables.put(input.text, value instanceof Instant instant ? instant.atZone(ZoneOffset.UTC) : value);
        }
        String feel = FeelTranslation.feel(expr);
        EvaluationResult result = REFERENCE.evaluateExpression(feel, variables);
        if (!result.isSuccess()) {
            throw new AssertionError("the reference refused " + feel + ": " + result.failure().message());
        }
        return result;
    }

    /** Equal values: numbers by value, date-times by instant, null only with null. */
    static boolean same(Object ours, Object theirs) {
        if (ours == null || theirs == null) {
            return ours == theirs;
        }
        if (ours instanceof BigDecimal number) {
            BigDecimal other = theirs instanceof scala.math.BigDecimal scalaNumber ? scalaNumber.bigDecimal()
                    : theirs instanceof BigDecimal javaNumber ? javaNumber
                    : theirs instanceof Long whole ? BigDecimal.valueOf(whole) : null;
            return other != null && number.compareTo(other) == 0;
        }
        if (ours instanceof Instant instant) {
            return theirs instanceof ZonedDateTime zoned && zoned.toInstant().equals(instant);
        }
        return ours.equals(theirs);
    }

    // ------------------------------------------------------------------ generators

    static final Gen<Object[]> INPUTS = Gen.from(random -> {
        Object[] slots = new Object[Input.values().length];
        for (Input input : Input.values()) {
            slots[input.ordinal()] = random.nextInt(4) == 0 ? null : switch (input) {
                case I1, I2 -> BigDecimal.valueOf(random.nextInt(-50, 51));
                case N1 -> BigDecimal.valueOf(random.nextLong(-100_000, 100_001), 3);
                case S1, S2 -> text(random);
                case B1, B2 -> random.nextBoolean();
                case D1, D2 -> date(random);
                case T1 -> INSTANTS[random.nextInt(INSTANTS.length)];
            };
        }
        return slots;
    });

    static Gen<String> expressions(Type type) {
        return Gen.from(random -> expression(random, type, MAX_DEPTH));
    }

    static String expression(RandomGenerator random, Type type, int depth) {
        if (depth == 0 || random.nextInt(4) == 0) {
            return leaf(random, type);
        }
        int next = depth - 1;
        return switch (type) {
            case NUMBER -> switch (random.nextInt(8)) {
                case 0 -> "(" + expression(random, type, next) + " + " + expression(random, type, next) + ")";
                case 1 -> "(" + expression(random, type, next) + " - " + expression(random, type, next) + ")";
                case 2 -> "(" + expression(random, type, next) + " * " + expression(random, type, next) + ")";
                case 3 -> "(" + expression(random, type, next) + " / " + expression(random, type, next) + ")";
                case 4 -> "-(" + expression(random, type, next) + ")";
                case 5 -> "round(" + expression(random, type, next) + ", " + random.nextInt(-2, 4) + ")";
                case 6 -> "daysBetween(" + expression(random, Type.DATE, next) + ", " + expression(random, Type.DATE, next) + ")";
                default -> leaf(random, type);
            };
            case STRING -> random.nextBoolean()
                    ? "(" + expression(random, type, next) + " + " + expression(random, type, next) + ")"
                    : leaf(random, type);
            case BOOLEAN -> condition(random, next);
            case DATE, DATETIME -> leaf(random, type);
        };
    }

    static String condition(RandomGenerator random, int depth) {
        Type operand = ordered(random);
        return switch (random.nextInt(12)) {
            case 0 -> "(" + expression(random, Type.BOOLEAN, depth) + " and " + expression(random, Type.BOOLEAN, depth) + ")";
            case 1 -> "(" + expression(random, Type.BOOLEAN, depth) + " or " + expression(random, Type.BOOLEAN, depth) + ")";
            case 2 -> "(not (" + expression(random, Type.BOOLEAN, depth) + "))";
            case 3 -> "(" + expression(random, operand, depth) + " " + pick(random, "<", "<=", ">", ">=") + " "
                    + expression(random, operand, depth) + ")";
            case 4 -> {
                Type any = random.nextInt(5) == 0 ? Type.BOOLEAN : operand;
                String right = random.nextInt(5) == 0 ? "null" : expression(random, any, depth);
                yield "(" + expression(random, any, depth) + " " + pick(random, "==", "!=") + " " + right + ")";
            }
            case 5, 6 -> {
                List<String> elements = new ArrayList<>();
                int size = random.nextInt(4);
                for (int i = 0; i < size; i++) {
                    elements.add(random.nextInt(6) == 0 ? "null" : expression(random, operand, depth));
                }
                yield "(" + expression(random, operand, depth) + (random.nextBoolean() ? " in [" : " not in [")
                        + String.join(", ", elements) + "])";
            }
            case 7 -> "(" + expression(random, operand, depth) + " between " + expression(random, operand, depth)
                    + " and " + expression(random, operand, depth) + ")";
            case 8 -> "(" + expression(random, ordered(random), depth) + (random.nextBoolean() ? " is null)" : " is not null)");
            case 9 -> "matches(" + expression(random, Type.STRING, depth) + ", " + v1String(pick(random, PATTERNS)) + ")";
            default -> leaf(random, Type.BOOLEAN);
        };
    }

    static String leaf(RandomGenerator random, Type type) {
        List<Input> inputs = Stream.of(Input.values()).filter(input -> input.type == type).toList();
        if (random.nextBoolean()) {
            return inputs.get(random.nextInt(inputs.size())).text;
        }
        return switch (type) {
            case STRING -> v1String(text(random));
            case NUMBER -> random.nextBoolean()
                    ? Integer.toString(random.nextInt(-20, 1001))
                    : BigDecimal.valueOf(random.nextLong(0, 100_000), random.nextInt(1, 4)).toPlainString();
            case BOOLEAN -> Boolean.toString(random.nextBoolean());
            case DATE -> "date('" + date(random) + "')";
            case DATETIME -> "datetime('" + INSTANTS[random.nextInt(INSTANTS.length)] + "')";
        };
    }

    static Type ordered(RandomGenerator random) {
        return pick(random, Type.NUMBER, Type.NUMBER, Type.STRING, Type.DATE, Type.DATETIME);
    }

    static String text(RandomGenerator random) {
        StringBuilder text = new StringBuilder();
        int length = random.nextInt(5);
        for (int i = 0; i < length; i++) {
            text.appendCodePoint(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return text.toString();
    }

    static LocalDate date(RandomGenerator random) {
        return LocalDate.of(2027, 1, 1).plusDays(random.nextInt(-400, 400));
    }

    static String v1String(String text) {
        return "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    @SafeVarargs
    static <T> T pick(RandomGenerator random, T... choices) {
        return choices[random.nextInt(choices.length)];
    }
}
