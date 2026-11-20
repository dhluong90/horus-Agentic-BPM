package io.github.dhluong90.bpm.expr.spi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The names an expression may read.
 * <ul>
 *   <li>{@link #declared(Map)}: a closed list of names with their types, as a rule set's inputs. An unknown name is a
 *       compile error.</li>
 *   <li>{@link #untyped()}: any name, including a dotted path such as {@code customer.tier}, whose type is known only
 *       at evaluation, as a process variable. A value of the wrong type for an operator is then an evaluation error,
 *       never a silent {@code false}.</li>
 * </ul>
 */
public final class TypeEnvironment {

    private static final TypeEnvironment UNTYPED = new TypeEnvironment(null);

    private final Map<String, ValueType> names;

    private TypeEnvironment(Map<String, ValueType> names) {
        this.names = names;
    }

    /**
     * @param names the names and their types, in declaration order
     * @return a closed environment
     */
    public static TypeEnvironment declared(Map<String, ValueType> names) {
        Objects.requireNonNull(names, "names must not be null");
        return new TypeEnvironment(Collections.unmodifiableMap(new LinkedHashMap<>(names)));
    }

    /** @return the open environment of dynamically typed names */
    public static TypeEnvironment untyped() {
        return UNTYPED;
    }

    /** @return whether names are declared up front */
    public boolean isDeclared() {
        return names != null;
    }

    /**
     * @param name a name or dotted path
     * @return its type; {@link ValueType#ANY} for any name of an untyped environment; empty when undeclared
     */
    public Optional<ValueType> typeOf(String name) {
        if (names == null) {
            return Optional.of(ValueType.ANY);
        }
        return Optional.ofNullable(names.get(name));
    }

    /** @return the declared names in order; empty for an untyped environment */
    public Map<String, ValueType> names() {
        return names == null ? Map.of() : names;
    }
}
