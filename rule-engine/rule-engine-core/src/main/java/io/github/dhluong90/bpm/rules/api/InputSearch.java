package io.github.dhluong90.bpm.rules.api;

import java.time.Instant;

/**
 * Which decisions of one rule set to list by the exact value of an input that is stored in plain text (RLE-071).
 * A masked input is never searched this way: that is {@link MaskedInputQuery}, with its own permission. Values compare
 * by canonical form: money by currency and minor units, a number by exact value ({@code 30000} equals
 * {@code 30000.00}), a string by code point, with no case folding or trimming.
 *
 * @param ruleSet the rule set name
 * @param input the input name, declared by a version of the rule set and not masked
 * @param value the value: a {@code String}, number, {@code Boolean}, {@code Money}, {@code LocalDate} or
 *     {@code Instant}, of the type the input declares
 * @param from the first instant of the period, inclusive
 * @param to the end of the period, exclusive
 */
public record InputSearch(String ruleSet, String input, Object value, Instant from, Instant to) {

    /**
     * The value is not printed, so a search in a log line or an exception does not carry it.
     *
     * @return the rule set, input and period only
     */
    @Override
    public String toString() {
        return "InputSearch[ruleSet=" + ruleSet + ", input=" + input + ", value=(not printed), from=" + from
                + ", to=" + to + "]";
    }
}
