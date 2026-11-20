package io.github.dhluong90.bpm.process.internal.service;

import java.util.Objects;

/**
 * Who a decision step runs for (ADR-0027). Built by the engine only, from its own resolvers or job context, never
 * from request data. Every decision is evaluated as {@link #ENGINE_IDENTITY}, on behalf of {@link #onBehalfOf()}, so
 * the decision log stays attributable without granting {@code rules:evaluate} to task workers.
 */
public sealed interface DecisionCaller {

    /** The engine identity written as the caller of every decision a process step evaluates. */
    String ENGINE_IDENTITY = "system:process-engine";

    /** @return the human the engine acts for, or null for engine-owned work such as a timer */
    String onBehalfOf();

    /**
     * Engine-owned work with no caller signed in: a step reached after a timer. On behalf of nobody.
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

        @Override
        public String onBehalfOf() {
            return null;
        }
    }

    /**
     * A step reached inside a caller's request.
     *
     * @param tenantId the request's tenant, from the engine's {@code TenantResolver}; the adapter refuses the step
     *     when it is not the instance's tenant
     * @param userId the signed-in caller, from the engine's {@code CallerResolver}; written as {@code onBehalfOf}
     */
    record Request(String tenantId, String userId) implements DecisionCaller {
        @Override
        public String onBehalfOf() {
            return userId;
        }
    }
}
