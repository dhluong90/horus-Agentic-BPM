package io.github.dhluong90.bpm.expr.feel;

import java.util.Map;

/**
 * The FEEL type root, used by the static checker and by DMN item definitions. FEEL is dynamically typed: a static type
 * says what can be proved at publish time, {@link Any} when nothing can. The Java value of each type is in
 * {@link FeelValues}. Money is the HORUS extension (rule engine DESIGN section 3.4). Owned by the evaluator stream
 * (#198); adding a type is additive.
 */
public sealed interface FeelType {

    /** The type of {@code null} and the unknown: every type conforms to it. */
    record Any() implements FeelType { }

    /** The type of the literal {@code null}. */
    record Null() implements FeelType { }

    /** Number, string, boolean, date, time, date and time and the two durations, by name. */
    record Scalar(Kind kind) implements FeelType {
        /** The scalar kinds. */
        public enum Kind { NUMBER, STRING, BOOLEAN, DATE, TIME, DATE_TIME, DAYS_TIME_DURATION, YEARS_MONTHS_DURATION, MONEY }
    }

    /** {@code list<element>}. */
    record ListOf(FeelType element) implements FeelType { }

    /** {@code context<fields>}; {@code open} when more keys may exist at run time. Field order is source order. */
    record ContextOf(Map<String, FeelType> fields, boolean open) implements FeelType {
        public ContextOf { fields = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(fields)); }
    }

    /** {@code range<point>}. */
    record RangeOf(FeelType point) implements FeelType { }

    /** {@code function<parameters> -> result}. */
    record FunctionOf(java.util.List<FeelType> parameters, FeelType result) implements FeelType {
        public FunctionOf { parameters = java.util.List.copyOf(parameters); }
    }

    /**
     * A DMN item definition: a base type narrowed by allowed values, a unary-tests text kept for the model and its
     * compiled test supplied by the DMN stream (#202).
     */
    record Constrained(String name, FeelType base, String allowedValues) implements FeelType { }
}
