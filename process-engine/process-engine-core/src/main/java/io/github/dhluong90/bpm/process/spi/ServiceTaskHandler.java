package io.github.dhluong90.bpm.process.spi;

/**
 * Host code behind a local {@code serviceTask}, registered under a name and referenced from BPMN by
 * {@code horus:handler="<name>"}. The engine calls it through this interface only, never by reflection
 * (TR-SEC-01). The starter collects every bean of this type.
 *
 * <p>Transactions: called inside the engine transaction (local mode). It may write to the host database through
 * the same transaction manager, and those writes commit or roll back with the step. It must not call the
 * network; remote steps are the Async Wait-State of ADR-0008, not in this skeleton.
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
}
