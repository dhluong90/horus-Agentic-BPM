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
     * @param actor the engine actor that triggered the step, for example {@code system:timer}
     */
    record Engine(String actor) implements DecisionCaller {
        public Engine {
            Objects.requireNonNull(actor, "actor must not be null");
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
