package io.github.dhluong90.bpm.rules.test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Loads a rule set file for the tests of a class (RLE-059). The file is read, checked and compiled once, before the
 * first test, with the engine's own reader, compiler and evaluator, so a test result is what production decides for the
 * same version, inputs and clock. A file that fails import fails the class before any test runs, with the violations
 * of {@code 422 VALIDATION_FAILED}. A test takes a {@link RuleSetUnderTest} as a parameter.
 *
 * <pre>{@code
 * @RuleSetTest("rules/card-fee.yaml")
 * class CardFeeRulesTest {
 *     @Test
 *     void silver_with_five_transactions_pays_ten_thousand(RuleSetUnderTest rules) {
 *         assertThat(rules.evaluate(Map.of("tier", "SILVER", "txCount", 5)).matchedRuleIds()).containsExactly("R2");
 *     }
 * }
 * }</pre>
 *
 * <p>Test scope only. The module has no database, no Spring context, no masking key and no events; the tenant is the
 * fixed test tenant {@value RuleSetUnderTest#TENANT}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ExtendWith(RuleSetExtension.class)
public @interface RuleSetTest {

    /** The clock of every evaluation unless a class or a test says otherwise. */
    String DEFAULT_CLOCK = "2027-01-01T00:00:00Z";

    /**
     * The rule set file on the class path: YAML, JSON or DMN XML, by its extension ({@code .yaml}, {@code .yml},
     * {@code .json}, {@code .dmn}, {@code .xml}).
     *
     * @return the class path location, for example {@code rules/card-fee.yaml}
     */
    String value();

    /**
     * The instant {@code today()} and {@code now()} read, for the whole class; {@code today()} is its date in the rule
     * set's time zone. A test overrides it with {@link RuleSetClock}.
     *
     * @return an ISO 8601 instant with a zone, for example {@code 2027-06-30T17:00:00Z}
     */
    String clock() default DEFAULT_CLOCK;
}
