package com.horus.bpm.rules.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Named input values of one evaluation. Values are {@code String}, {@code Boolean}, {@code Long},
 * {@code Integer}, {@code BigDecimal}, {@link Money}, {@code LocalDate}, {@code Instant} or null.
 * Immutable; keeps insertion order.
 *
 * @param values the input values by input name
 */
public record DecisionInput(Map<String, Object> values) {

    public DecisionInput {
        Objects.requireNonNull(values, "values must not be null");
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        values.forEach((name, value) -> copy.put(Objects.requireNonNull(name, "input name must not be null"), value));
        values = Collections.unmodifiableMap(copy);
    }

    /**
     * Creates inputs from a map; null values are allowed and mean "missing".
     *
     * @param values the input values by name
     * @return the inputs
     */
    public static DecisionInput of(Map<String, ?> values) {
        return new DecisionInput(new LinkedHashMap<>(values));
    }

    /**
     * Returns one input value.
     *
     * @param name the input name
     * @return the value, or null when absent or null
     */
    public Object get(String name) {
        return values.get(name);
    }
}
