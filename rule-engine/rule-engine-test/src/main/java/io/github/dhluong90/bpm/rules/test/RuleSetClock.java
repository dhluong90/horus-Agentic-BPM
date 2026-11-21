package io.github.dhluong90.bpm.rules.test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The clock of one test, overriding {@link RuleSetTest#clock()}: the instant {@code today()} and {@code now()} read
 * in that test (RLE-059). Fixed, so a test of a rule that depends on the date gives the same result on every run.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RuleSetClock {

    /**
     * @return an ISO 8601 instant with a zone, for example {@code 2027-06-30T17:00:00Z}
     */
    String value();
}
