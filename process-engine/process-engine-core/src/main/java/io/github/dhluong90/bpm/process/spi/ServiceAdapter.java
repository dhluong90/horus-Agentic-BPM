package io.github.dhluong90.bpm.process.spi;

/**
 * Host code behind a remote {@code serviceTask} ({@code horus:mode="remote"}), registered under a name and referenced
 * from BPMN by {@code horus:handler="<name>"}. The engine calls it through this interface only, never by reflection
 * (TR-SEC-01). The starter collects every bean of this type; there is no default.
 *
 * <p>Transactions (ADR-0008): called on the engine's adapter executor with no database connection and no row lock
 * held. The claim that leased the step has committed before the call, and the result is recorded in a fresh
 * transaction afterwards. The engine abandons the call when the step timeout elapses and interrupts its thread, so an
 * implementation should honour interruption and its own client timeouts.
 *
 * <p>Idempotency: a call can be repeated after a crash, a timeout or a retryable failure. Every repeat carries the
 * same {@link AdapterCall#idempotencyKey()}, the job id, and the remote side must deduplicate by it (TR-IDEM-04): a
 * result that arrives after its lease expired is discarded while its side effect may already have happened, and the
 * next attempt must not perform it again. The {@link AdapterCall#attemptId()} changes on every attempt and only
 * fences results; it is not an idempotency key.
 *
 * <p>Hand-off: an adapter that answers {@link AdapterResult.Accepted} passes {@link AdapterCall#jobId()},
 * {@link AdapterCall#attemptId()} and {@link AdapterCall#completionToken()} to the remote side, which presents them to
 * the completion API. The caller of that API needs {@code processes:step:complete} and one of the roles configured for
 * this adapter in {@code horus.bpm.processes.adapters.completer-roles}.
 *
 * <p>Implementations must be thread-safe.
 */
public interface ServiceAdapter {

    /**
     * @return the name used in {@code horus:handler}, for example {@code creditBureau.score}
     */
    String name();

    /**
     * Runs the call, or hands it off.
     *
     * @param call the bounded inputs and identifiers of this attempt
     * @return the result; {@link AdapterResult.Accepted} when the remote side will report the result later through
     *     the completion API with the attempt id and the completion token
     */
    AdapterResult execute(AdapterCall call);

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
