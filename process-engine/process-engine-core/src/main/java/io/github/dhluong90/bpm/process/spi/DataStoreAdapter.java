package io.github.dhluong90.bpm.process.spi;

/**
 * The host's side of a BPMN data store (#499). A {@code dataStoreReference} that a task's data input association reads
 * from, or a data output association writes to, is served by the adapter registered under the id of the
 * {@code dataStore} it names. The engine never opens a connection of its own to the store.
 *
 * <p>Tenant isolation is a control: every call carries the tenant of the instance, taken from the instance and never
 * from a process variable or a request body. The adapter must scope every read and write by it, so one tenant's
 * instance can never read or change another tenant's rows. The engine keeps one adapter per store name for the whole
 * host and does not itself separate tenants inside the store.
 *
 * <p>Transactions: both methods run inside the step transaction, on the host's connection, so a write commits or
 * rolls back with the step. They must be fast and in-process. A step that rolls back and runs again repeats the call
 * with the same {@link DataStoreAccess#idempotencyKey()}; a write must be idempotent on it.
 *
 * <p>Both methods have a default that refuses the operation, so a read-only or write-only store implements one of
 * them. The starter collects every bean of this type; a definition whose association names a store with no adapter is
 * refused at deploy time with {@code DATA_STORE_ADAPTER_NOT_REGISTERED}.
 *
 * <p>Implementations must be thread-safe.
 */
public interface DataStoreAdapter {

    /**
     * @return the id of the BPMN {@code dataStore} this adapter serves, for example {@code ledger-notes}
     */
    String name();

    /**
     * Reads the value a data input association hands to a step.
     *
     * @param access who asks, for which store and item
     * @return the value; never null, an adapter with nothing to hand over throws instead
     */
    default Object read(DataStoreAccess access) {
        throw new UnsupportedOperationException("The data store " + access.store() + " cannot be read");
    }

    /**
     * Writes the value a data output association takes from a step's result.
     *
     * @param access who writes, for which store and item
     * @param value the value
     */
    default void write(DataStoreAccess access, Object value) {
        throw new UnsupportedOperationException("The data store " + access.store() + " cannot be written");
    }

    /**
     * One access to a data store.
     *
     * @param tenantId the instance's tenant; every read and write is scoped by it
     * @param store the store name
     * @param item the name of the step's data input or data output, or the id of the association when it has none
     * @param instanceId the instance
     * @param nodeId the step
     * @param idempotencyKey stable for the step's attempt in this instance, the key of a write
     */
    record DataStoreAccess(String tenantId, String store, String item, String instanceId, String nodeId,
            String idempotencyKey) {
    }
}
