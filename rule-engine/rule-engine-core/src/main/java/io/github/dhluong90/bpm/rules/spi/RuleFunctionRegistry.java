package io.github.dhluong90.bpm.rules.spi;

import io.github.dhluong90.bpm.expr.spi.ExpressionFunction;
import java.util.Collection;

/**
 * The functions a host registers for rule cells, next to the built-in ones (RLE-055). Registered per host, never per
 * tenant. The default registry of the starter returns every {@link ExpressionFunction} bean of the host.
 *
 * <p><b>A function is a pure calculation</b> (PD-119): it sees its argument values and nothing else, gives the same
 * result for the same arguments on every call, reads no data and calls no network. A decision is replayed and
 * simulated from its log entry, which only works if the function is pure. The engine cannot prove it; the host's own
 * change control owns it. A customer segment held in core banking is looked up by the host and passed to the rules as
 * an input.
 *
 * <p>The host changes a function's {@linkplain ExpressionFunction#version() version label} whenever its behaviour
 * may change. A name registered twice, a built-in name and an invalid name stop the host at start-up.
 */
@FunctionalInterface
public interface RuleFunctionRegistry {

    /** @return the functions, in any order */
    Collection<ExpressionFunction> functions();

    /** @return a registry with no function */
    static RuleFunctionRegistry none() {
        return java.util.List::of;
    }
}
