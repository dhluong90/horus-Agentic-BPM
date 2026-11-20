package io.github.dhluong90.bpm.rules.internal.json;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.expr.api.Money;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * An exact, unambiguous text form of evaluation inputs for request hashes. Unlike the RFC 8785 form it
 * never rounds through a double, so {@code 0.1} and {@code 0.10000000000000001} hash differently; equal
 * numbers ({@code 5}, {@code 5.0}) hash the same. Callers bound number inputs first ({@link InputLimits}).
 */
public final class ExactForm {

    private ExactForm() {
    }

    /**
     * Writes inputs with keys in code-unit order.
     *
     * @param input the inputs
     * @return the exact text
     */
    public static String of(DecisionInput input) {
        StringBuilder out = new StringBuilder();
        write(new TreeMap<>(input.values()), out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        switch (value) {
            case null -> out.append("null");
            case String s -> out.append('s').append(s.length()).append(':').append(s);
            case Boolean b -> out.append(b ? "true" : "false");
            case BigDecimal d -> out.append("n:").append(d.signum() == 0 ? "0" : d.stripTrailingZeros().toString());
            case BigInteger i -> out.append("n:").append(i);
            case Long l -> out.append("n:").append(l);
            case Integer i -> out.append("n:").append(i);
            case Short s -> out.append("n:").append(s);
            case Money m -> out.append("m:").append(m.currency()).append(':').append(m.minorUnits());
            case LocalDate d -> out.append("d:").append(d);
            case Instant t -> out.append("t:").append(t);
            case List<?> list -> {
                out.append('[');
                for (Object element : list) {
                    write(element, out);
                    out.append(',');
                }
                out.append(']');
            }
            case Map<?, ?> map -> {
                out.append('{');
                map.forEach((k, v) -> {
                    write(String.valueOf(k), out);
                    out.append('=');
                    write(v, out);
                    out.append(',');
                });
                out.append('}');
            }
            default -> throw new InputInvalidException("Unsupported input value type " + value.getClass().getSimpleName());
        }
    }
}
