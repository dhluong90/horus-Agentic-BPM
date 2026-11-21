package io.github.dhluong90.bpm.expr.spi;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The functions a host registered, by name. Immutable and thread-safe. One library serves the whole host: functions
 * are never registered per tenant (RLE-055).
 */
public final class FunctionLibrary {

    /** Names the language already uses: a host function may not take one. */
    public static final Set<String> RESERVED_NAMES = Set.of("round", "daysBetween", "today", "now", "date", "datetime",
            "matches", "not", "and", "or", "in", "between", "is", "null", "true", "false");

    private static final FunctionLibrary EMPTY = new FunctionLibrary(Map.of());

    private final Map<String, ExpressionFunction> functions;

    private FunctionLibrary(Map<String, ExpressionFunction> functions) {
        this.functions = functions;
    }

    /** @return the library with no function, the default of a host that registers none */
    public static FunctionLibrary empty() {
        return EMPTY;
    }

    /**
     * Builds a library, refusing what would make a name ambiguous.
     *
     * @param functions the functions
     * @return the library
     * @throws IllegalArgumentException naming the function, when a name is registered twice or is a built-in name
     */
    public static FunctionLibrary of(Collection<ExpressionFunction> functions) {
        Objects.requireNonNull(functions, "functions must not be null");
        Map<String, ExpressionFunction> byName = new TreeMap<>();
        for (ExpressionFunction function : functions) {
            Objects.requireNonNull(function, "a function must not be null");
            if (RESERVED_NAMES.contains(function.name())) {
                throw new IllegalArgumentException("Function '" + function.name()
                        + "' is registered with the name of a built-in; choose another name");
            }
            if (byName.putIfAbsent(function.name(), function) != null) {
                throw new IllegalArgumentException("Function '" + function.name() + "' is registered twice");
            }
        }
        return byName.isEmpty() ? EMPTY : new FunctionLibrary(Collections.unmodifiableMap(new LinkedHashMap<>(byName)));
    }

    /**
     * @param name a name in an expression
     * @return the function, or empty when none is registered under it
     */
    public Optional<ExpressionFunction> find(String name) {
        return Optional.ofNullable(functions.get(name));
    }

    /** @return the functions by name, in name order */
    public Map<String, ExpressionFunction> functions() {
        return functions;
    }

    /** @return whether no function is registered */
    public boolean isEmpty() {
        return functions.isEmpty();
    }
}
