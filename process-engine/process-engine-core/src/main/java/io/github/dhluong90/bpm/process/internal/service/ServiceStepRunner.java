package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.internal.store.JobStore;
import io.github.dhluong90.bpm.process.spi.AdapterCall;
import io.github.dhluong90.bpm.process.spi.AdapterResult;
import io.github.dhluong90.bpm.process.spi.ServiceAdapter;
import java.util.Map;
import java.util.Objects;

/**
 * The runner of the host's {@link ServiceAdapter}s by name, for one job kind: remote service steps
 * ({@code ASYNC_SERVICE}) or the undos of a compensation walk ({@code COMPENSATION}, #67). A compensation job of a
 * local undo calls no adapter here: its handler runs in the result transaction, where its writes commit with the
 * walk ({@link ProcessServices#recordAttempt}).
 */
public final class ServiceStepRunner implements AsyncStepRunner {

    /** The payload key that tells a compensation job's mode, {@code local} or {@code remote}. */
    static final String MODE = "mode";
    /** The mode of a compensation job whose undo is a local handler. */
    static final String LOCAL = "local";

    private final Map<String, ServiceAdapter> adapters;
    private final String jobKind;

    /** @param adapters the host's adapters by name; runs remote service steps */
    public ServiceStepRunner(Map<String, ServiceAdapter> adapters) {
        this(adapters, JobStore.ASYNC_SERVICE);
    }

    /**
     * @param adapters the host's adapters by name
     * @param jobKind {@code ASYNC_SERVICE} or {@code COMPENSATION}
     */
    public ServiceStepRunner(Map<String, ServiceAdapter> adapters, String jobKind) {
        this.adapters = Map.copyOf(adapters);
        this.jobKind = Objects.requireNonNull(jobKind, "jobKind must not be null");
        if (!JobStore.ASYNC_SERVICE.equals(jobKind) && !JobStore.COMPENSATION.equals(jobKind)) {
            throw new IllegalArgumentException("A service runner runs ASYNC_SERVICE or COMPENSATION jobs, not "
                    + jobKind);
        }
    }

    @Override
    public String jobKind() {
        return jobKind;
    }

    @Override
    public AdapterResult run(String target, AdapterCall call) {
        return call(target, call);
    }

    private AdapterResult call(String target, AdapterCall call) {
        ServiceAdapter adapter = adapters.get(target);
        if (adapter == null) {
            return new AdapterResult.Failed("ADAPTER_NOT_REGISTERED", "No ServiceAdapter named " + target, false);
        }
        return adapter.execute(call);
    }

    @Override
    public AdapterResult run(String target, AdapterCall call, Map<String, Object> payload) {
        if (JobStore.COMPENSATION.equals(jobKind) && LOCAL.equals(payload.get(MODE))) {
            // A local undo is a handler, never a network call: it runs in the result transaction.
            return AdapterResult.completed(Map.of());
        }
        return call(target, call);
    }
}
