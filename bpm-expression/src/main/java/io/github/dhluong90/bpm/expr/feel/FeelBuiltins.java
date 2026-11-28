package io.github.dhluong90.bpm.expr.feel;

import java.util.List;
import java.util.Optional;

/**
 * The registry of FEEL built-in functions (#199) and the contract of one built-in. The engine looks a function up by
 * its specification name, including names with spaces ({@code string length}, {@code date and time}). Oracle: the
 * specification's own examples, one table-driven test per function, and the TCK built-in folders.
 */
public interface FeelBuiltins {

    /** @return the registry of this release, empty until #199 */
    static FeelBuiltins create() {
        return name -> Optional.empty();
    }

    /**
     * @param name the specification name, spaces single
     * @return the built-in, or empty
     */
    Optional<Builtin> find(String name);

    /** One built-in. Pure and thread-safe: no reflection, no locale, no clock except through the call. */
    interface Builtin extends FeelFunction {

        /** @return the specification name */
        String name();

        /** @return the result type for static checking, {@link FeelType.Any} when it depends on the arguments */
        FeelType resultType(List<FeelType> arguments);

        /** @return the step cost of one call, at least 1; list functions add one per element they visit */
        default int cost() {
            return 1;
        }
    }
}
