package io.github.dhluong90.bpm.expr.spi;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A function a host registers for expressions: a name, typed parameters, a result type, a version label and a step
 * cost (TR-SEC-01, RLE-055).
 *
 * <p><b>The implementation must be a pure calculation.</b> It sees its argument values only: no tenant, caller,
 * clock, connection or network. It must give the same result for the same arguments on every call, with no side
 * effect, because a decision is replayed and simulated from its log entry (TR-DET-01, TR-DET-05). The engine cannot
 * prove purity; the host's own review owns it. Data a host looks up, such as a customer segment held in core banking,
 * is passed to the rules as an input and never fetched by a function (PD-119).
 *
 * <p>An argument or result is a {@code String}, {@code Boolean}, {@code BigDecimal}, {@code LocalDate},
 * {@code Instant} or {@code Money}, for {@link ValueType#STRING}, {@link ValueType#BOOLEAN}, {@link ValueType#NUMBER}
 * ({@code integer} and {@code decimal} inputs alike), {@link ValueType#DATE}, {@link ValueType#DATETIME} and
 * {@link ValueType#MONEY}. A call with a {@code null} argument gives {@code null} without calling the implementation
 * (FEEL). A result of another type, or an exception, fails the decision closed with {@code FUNCTION_FAILED}.
 *
 * @param name the name in expressions, {@code ^[a-z][A-Za-z0-9]{0,63}$}; not a built-in name
 * @param parameterTypes the parameter types, in order; none of them {@link ValueType#LIST} or {@link ValueType#ANY}
 * @param resultType the result type, not {@link ValueType#LIST} or {@link ValueType#ANY}
 * @param version the version label of the implementation, 1 to 64 characters without spaces; a change of label tells
 *     the engine the behaviour may have changed
 * @param stepCost the evaluation steps one call costs, counted against the decision's step budget, 1 to 1,000,000
 * @param implementation the calculation
 */
public record ExpressionFunction(String name, List<ValueType> parameterTypes, ValueType resultType, String version,
        int stepCost, Implementation implementation) {

    /** The allowed form of a function name. */
    public static final Pattern NAME = Pattern.compile("^[a-z][A-Za-z0-9]{0,63}$");

    private static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9._+-]{1,64}$");

    /** Ceiling of {@link #stepCost()}: the ceiling of the step budget. */
    public static final int STEP_COST_MAX = 1_000_000;

    /** The calculation of a function. */
    @FunctionalInterface
    public interface Implementation {

        /**
         * Calculates the result. Called with non-null arguments only, each of the declared type.
         *
         * @param arguments the argument values, immutable
         * @return the result, of the declared type, or null
         */
        Object apply(List<Object> arguments);
    }

    public ExpressionFunction {
        Objects.requireNonNull(name, "name must not be null");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Function '" + name + "' has an invalid name: use " + NAME.pattern());
        }
        Objects.requireNonNull(parameterTypes, "parameterTypes must not be null");
        parameterTypes = List.copyOf(parameterTypes);
        if (parameterTypes.isEmpty()) {
            throw new IllegalArgumentException("Function '" + name + "' needs at least one parameter");
        }
        for (ValueType type : parameterTypes) {
            requireScalar(name, "parameter", type);
        }
        requireScalar(name, "result", resultType);
        if (version == null || !VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("Function '" + name + "' has an invalid version label: 1 to 64 of"
                    + " letters, digits and . _ + -");
        }
        if (stepCost < 1 || stepCost > STEP_COST_MAX) {
            throw new IllegalArgumentException("Function '" + name + "' has step cost " + stepCost + "; it must be 1 to "
                    + STEP_COST_MAX);
        }
        Objects.requireNonNull(implementation, "implementation must not be null");
    }

    private static void requireScalar(String name, String role, ValueType type) {
        if (type == null || type == ValueType.LIST || type == ValueType.ANY) {
            throw new IllegalArgumentException("Function '" + name + "' has a " + role + " of type " + type
                    + "; use string, boolean, number, date, datetime or money");
        }
    }

    /**
     * Shorthand for a function with a step cost of 1.
     *
     * @param name the name
     * @param version the version label
     * @param resultType the result type
     * @param implementation the calculation
     * @param parameterTypes the parameter types
     * @return the function
     */
    public static ExpressionFunction of(String name, String version, ValueType resultType, Implementation implementation,
            ValueType... parameterTypes) {
        return new ExpressionFunction(name, List.of(parameterTypes), resultType, version, 1, implementation);
    }

    /** @return the signature as written in messages, for example {@code segmentOf(money, number)} */
    public String signature() {
        StringBuilder text = new StringBuilder(name).append('(');
        for (int i = 0; i < parameterTypes.size(); i++) {
            text.append(i == 0 ? "" : ", ").append(parameterTypes.get(i).name().toLowerCase(java.util.Locale.ROOT));
        }
        return text.append(')').toString();
    }

    @Override
    public String toString() {
        return signature() + "@" + version;
    }
}
