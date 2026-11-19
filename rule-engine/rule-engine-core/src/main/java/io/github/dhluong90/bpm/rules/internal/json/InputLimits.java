package io.github.dhluong90.bpm.rules.internal.json;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * Bounds every number in evaluation inputs before it is hashed, compiled or logged: at most
 * {@code maxPrecision} significant digits and a scale within plus or minus {@code maxScale}. A number
 * outside the bounds is {@code INPUT_INVALID}, so one small request cannot spend seconds in rounding
 * ({@code 1e-30000000}) or fail the log write ({@code 1e999999999}).
 */
public final class InputLimits {

    /** Bits that always fit in 34 decimal digits; larger integers get an exact digit count. */
    private static final int SAFE_BITS = 112;

    private InputLimits() {
    }

    /**
     * Checks every number of the inputs, in lists too.
     *
     * @param input the inputs
     * @param maxPrecision the largest number of significant digits
     * @param maxScale the largest absolute scale
     * @throws InputInvalidException naming the input
     */
    public static void check(DecisionInput input, int maxPrecision, int maxScale) {
        input.values().forEach((name, value) -> check(name, value, maxPrecision, maxScale));
    }

    private static void check(String name, Object value, int maxPrecision, int maxScale) {
        switch (value) {
            case BigDecimal d -> {
                if (Math.abs((long) d.scale()) > maxScale || (d.signum() != 0 && d.precision() > maxPrecision)) {
                    throw outOfBounds(name, maxPrecision, maxScale);
                }
            }
            case BigInteger i -> {
                if (i.bitLength() > SAFE_BITS && new BigDecimal(i).precision() > maxPrecision) {
                    throw outOfBounds(name, maxPrecision, maxScale);
                }
            }
            case Double ignored -> throw new InputInvalidException("Input " + name + " is a floating-point value; "
                    + "use an integer, a BigDecimal or a decimal string");
            case Float ignored -> throw new InputInvalidException("Input " + name + " is a floating-point value; "
                    + "use an integer, a BigDecimal or a decimal string");
            case List<?> list -> list.forEach(element -> check(name, element, maxPrecision, maxScale));
            case Map<?, ?> map -> map.values().forEach(element -> check(name, element, maxPrecision, maxScale));
            case null, default -> {
                // Strings, booleans, money (long minor units), dates and longs are bounded by their types.
            }
        }
    }

    private static InputInvalidException outOfBounds(String name, int maxPrecision, int maxScale) {
        return new InputInvalidException("Input " + name + " must have at most " + maxPrecision
                + " significant digits and a scale between -" + maxScale + " and " + maxScale);
    }
}
