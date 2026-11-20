package io.github.dhluong90.bpm.expr.api;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * An amount of money in one currency, held in minor units (rule-engine/docs/DESIGN.md section 3.4).
 *
 * <p>There is no constructor without a unit in its name: use {@link #ofMinor(String, long)} or
 * {@link #ofMajor(String, String)}. Construction never rounds. Immutable and thread-safe.
 *
 * @param currency ISO 4217 alphabetic code, for example {@code VND}
 * @param minorUnits the amount in minor units of the currency, for example 1250 for 12.50 USD
 */
public record Money(String currency, long minorUnits) {

    /** Largest absolute amount that JSON clients read exactly: 2^53 - 1. */
    public static final long MAX_JSON_SAFE = 9_007_199_254_740_991L;

    public Money {
        Objects.requireNonNull(currency, "currency must not be null");
        scaleOf(currency);
    }

    /**
     * Creates money from minor units.
     *
     * @param currency ISO 4217 alphabetic code
     * @param minorUnits amount in minor units
     * @return the money value
     * @throws IllegalArgumentException when the currency is unknown or has no minor unit
     */
    public static Money ofMinor(String currency, long minorUnits) {
        return new Money(currency, minorUnits);
    }

    /**
     * Creates money from a decimal string in major units, for example {@code ofMajor("USD", "12.50")}.
     *
     * @param currency ISO 4217 alphabetic code
     * @param amount decimal string, never a floating-point value
     * @return the money value
     * @throws IllegalArgumentException when the amount has more fraction digits than the currency scale
     */
    public static Money ofMajor(String currency, String amount) {
        Objects.requireNonNull(amount, "amount must not be null");
        return fromMajor(currency, new BigDecimal(amount));
    }

    /**
     * Returns the number of fraction digits of a currency.
     *
     * @param currency ISO 4217 alphabetic code
     * @return 2 for USD, 0 for VND
     * @throws IllegalArgumentException when the code is unknown or has no defined minor unit
     */
    public static int scaleOf(String currency) {
        Currency c;
        try {
            c = Currency.getInstance(currency);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown currency: " + currency, e);
        }
        int digits = c.getDefaultFractionDigits();
        if (digits < 0) {
            throw new IllegalArgumentException("Currency without a minor unit: " + currency);
        }
        return digits;
    }

    /**
     * Returns the amount in major units with the currency scale.
     *
     * @return for example {@code 12.50} for 1250 USD minor units
     */
    public BigDecimal toMajor() {
        return BigDecimal.valueOf(minorUnits, scaleOf(currency));
    }

    private static Money fromMajor(String currency, BigDecimal major) {
        int scale = scaleOf(currency);
        BigDecimal stripped = major.stripTrailingZeros();
        if (stripped.scale() > scale) {
            throw new IllegalArgumentException(
                    "Amount " + major.toPlainString() + " has more fraction digits than " + currency + " allows (" + scale + ")");
        }
        return new Money(currency, major.movePointRight(scale).longValueExact());
    }

    @Override
    public String toString() {
        return toMajor().toPlainString() + " " + currency;
    }
}
