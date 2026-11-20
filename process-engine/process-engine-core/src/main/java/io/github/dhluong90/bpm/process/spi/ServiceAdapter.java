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
 * <p>Idempotency: a call can be repeated after a crash or a timeout, each time with a new
 * {@link AdapterCall#attemptId()}. The remote side must deduplicate by it (TR-IDEM-04): a result that arrives after
 * its lease expired is discarded while its side effect may already have happened.
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
     *     the completion API with the attempt id
     */
    AdapterResult execute(AdapterCall call);
}
