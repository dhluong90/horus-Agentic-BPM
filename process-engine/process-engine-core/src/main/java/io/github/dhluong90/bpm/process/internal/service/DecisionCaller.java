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
     * <p>Not a record: its constructor is package-private, so only the engine's timer path can ask for engine work,
     * and host code holding a {@link ServiceInvoker} cannot use the tenant-scoped entry point (#336).
     */
    final class Engine implements DecisionCaller {

        private final String actor;

        /** @param actor the engine actor that triggered the step, for example {@code system:timer} */
        Engine(String actor) {
            this.actor = Objects.requireNonNull(actor, "actor must not be null");
        }

        /** @return the engine actor that triggered the step */
        public String actor() {
            return actor;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Engine other && actor.equals(other.actor);
        }

        @Override
        public int hashCode() {
            return actor.hashCode();
        }

        @Override
        public String toString() {
            return "Engine[actor=" + actor + "]";
        }
    }

    /**
     * A step reached inside a caller's request. The Rule Engine resolves that caller from the host's security
     * context, after the engine has checked that the caller's tenant is the instance's tenant.
     */
    record Request() implements DecisionCaller {
    }
}
