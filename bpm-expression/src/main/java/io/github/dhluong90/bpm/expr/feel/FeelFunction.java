package io.github.dhluong90.bpm.expr.feel;

import java.util.List;

/**
 * A FEEL function value: a user function, a business knowledge model or a built-in. A pure closure over immutable
 * frames; calling it costs steps from the one budget of the evaluation. Thread-safe.
 */
public interface FeelFunction {

    /** @return the formal parameter names in order, as the specification names them for built-ins */
    List<String> parameterNames();

    /**
     * @param arguments positional values, already matched to {@link #parameterNames()}
     * @param call the evaluation state of the caller
     * @return the value, possibly null
     */
    Object invoke(List<Object> arguments, FeelCall call);
}
