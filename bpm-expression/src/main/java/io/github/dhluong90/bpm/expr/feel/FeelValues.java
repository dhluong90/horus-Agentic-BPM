package io.github.dhluong90.bpm.expr.feel;

/**
 * The Java value of each FEEL type, fixed by ADR-0028. {@code null} is Java {@code null}; there is no wrapper type.
 *
 * <ul>
 *   <li>number: {@link java.math.BigDecimal}, rounded to {@code DECIMAL128}; integers and longs are widened on
 *       entry; {@code Double} and {@code Float} are refused;</li>
 *   <li>string, boolean: {@link String} (compared by code point), {@link Boolean};</li>
 *   <li>date: {@link java.time.LocalDate}; time: {@link java.time.LocalTime} or {@link java.time.OffsetTime};</li>
 *   <li>date and time: {@link java.time.LocalDateTime} (no offset), {@link java.time.OffsetDateTime} (offset) or
 *       {@link java.time.ZonedDateTime} (zone id); an incoming {@link java.time.Instant} is an
 *       {@code OffsetDateTime} at {@code Z}, so values of the current language keep their meaning;</li>
 *   <li>days and time duration: {@link java.time.Duration}; years and months duration: {@link java.time.Period}
 *       with zero days;</li>
 *   <li>list: immutable {@link java.util.List}; context: immutable {@link java.util.Map} in insertion order, keys
 *       are strings;</li>
 *   <li>range: {@link FeelRange}; function: {@link FeelFunction};</li>
 *   <li>money: {@link io.github.dhluong90.bpm.expr.api.Money}, with {@code .amount} (major units, a number) and
 *       {@code .currency} (a string); it has no other member.</li>
 * </ul>
 * This type holds the rules that all streams share; the conversion code itself is #198.
 */
public final class FeelValues {

    private FeelValues() {
    }

    /**
     * Maps a host or instance value to a FEEL value, extending
     * {@link io.github.dhluong90.bpm.expr.api.ExpressionValues#toExpressionValue(Object)}.
     *
     * @param value a host value, possibly null
     * @return the FEEL value
     * @throws FeelNotImplementedException until #198
     */
    public static Object fromHost(Object value) {
        throw new FeelNotImplementedException("FeelValues.fromHost", "#198");
    }

    /**
     * @param value a FEEL value
     * @return its static type, {@link FeelType.Any} for null
     * @throws FeelNotImplementedException until #198
     */
    public static FeelType typeOf(Object value) {
        throw new FeelNotImplementedException("FeelValues.typeOf", "#198");
    }
}
