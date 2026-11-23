package io.github.dhluong90.bpm.process.internal.exec;

/**
 * The kernel's view of the host's data stores (#499): a read and a write by tenant and store name. The default one
 * refuses every access with {@code DATA_STORE_ADAPTER_MISSING}, so a kernel built without a host (tests, tools) never
 * touches a store.
 */
public interface DataStorePort {

    /** A port with no store behind it. */
    DataStorePort NONE = new DataStorePort() {
        @Override
        public Object read(Access access) {
            throw new ExecutionException("DATA_STORE_ADAPTER_MISSING", access.nodeId(), "No adapter serves data store "
                    + access.store());
        }

        @Override
        public void write(Access access, Object value) {
            read(access);
        }
    };

    /**
     * @param access the access
     * @return the value
     */
    Object read(Access access);

    /**
     * @param access the access
     * @param value the value
     */
    void write(Access access, Object value);

    /**
     * One access. The tenant is the instance's own.
     *
     * @param tenantId the tenant of the instance
     * @param store the data store id
     * @param item the data input or output name
     * @param instanceId the instance
     * @param nodeId the step
     * @param idempotencyKey stable for the step's attempt
     */
    record Access(String tenantId, String store, String item, String instanceId, String nodeId, String idempotencyKey) {
    }
}
