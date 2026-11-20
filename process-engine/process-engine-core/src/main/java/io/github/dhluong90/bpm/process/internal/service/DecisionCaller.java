package io.github.dhluong90.bpm.process.internal.service;

import java.util.Objects;

/**
 * Who a decision step runs for (ADR-0027). Built by the engine only, never from request data.
 */
public sealed interface DecisionCaller {

    /** The engine identity written to the decision log for engine-owned work. */
    String ENGINE_IDENTITY = "system:process-engine";

    /**
     * Engine-owned work with no caller signed in: a step reached after a timer. The decision is evaluated in the
     * tenant read from the locked instance row, as {@link #ENGINE_IDENTITY}, on behalf of nobody.
     *
     * @param actor the engine actor that triggered the step, for example {@code system:timer}
     */
    record Engine(String actor) implements DecisionCaller {
        public Engine {
            Objects.requireNonNull(actor, "actor must not be null");
        }
    }

    /**
     * A step reached inside a caller's request. The Rule Engine resolves that caller from the host's security
     * context, after the engine has checked that the caller's tenant is the instance's tenant.
     */
    record Request() implements DecisionCaller {
    }
}
