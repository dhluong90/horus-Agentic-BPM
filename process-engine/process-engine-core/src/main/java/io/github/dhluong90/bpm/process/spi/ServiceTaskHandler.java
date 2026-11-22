package io.github.dhluong90.bpm.process.spi;

/**
 * Host code behind a local {@code serviceTask}, registered under a name and referenced from BPMN by
 * {@code horus:handler="<name>"}. The engine calls it through this interface only, never by reflection
 * (TR-SEC-01). The starter collects every bean of this type.
 *
 * <p>Transactions: called inside the engine transaction (local mode: a step with no {@code horus:mode}, or
 * {@code horus:mode="local"}). It may write to the host database through the same transaction manager, and those
 * writes commit or roll back with the step, so a handler needs no idempotency key (PRE-070, PRE-071). It must not call
 * the network: it holds the instance lock and a connection for its whole duration, and a call above
 * {@code handlers.slow-call-warning} is logged as {@code process.step.slow}. A call to another system is a remote step
 * ({@code horus:mode="remote"}, a {@link ServiceAdapter} on the Async Wait-State of ADR-0008).
 *
 * <p>Failure: a thrown exception rolls the step back with everything written before it in the same transaction; a
 * request answers {@code 500 INTERNAL} with a correlation id only, and a job firing is retried after
 * {@code jobs.retry-backoff} times the attempt, up to {@code jobs.max-attempts}.
 *
 * <p>Implementations must be thread-safe.
 */
public interface ServiceTaskHandler {

    /**
     * @return the name used in {@code horus:handler}, for example {@code cardProfiles.load}
     */
    String name();

    /**
     * Runs the step.
     *
     * @param call the bounded inputs and identifiers of the step
     * @return the result; {@code Completed} output is stored in the step's result variable
     */
    HandlerResult handle(ServiceCall call);

    /**
     * Describes the input this step takes, for the step catalogue a modeller reads (PRE-032, {@code GET /step-catalogue}):
     * a JSON Schema object. Never put a configuration value, a credential or an endpoint in it.
     *
     * @return the input schema, or an empty map when the step declares none (the default)
     */
    default java.util.Map<String, Object> inputSchema() {
        return java.util.Map.of();
    }

    /**
     * @return whether the host can undo this step through a compensation handler, shown in the step catalogue
     *     (PRE-032); false by default
     */
    default boolean compensable() {
        return false;
    }
}
