package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.internal.store.JobStore;
import io.github.dhluong90.bpm.process.spi.AdapterCall;
import io.github.dhluong90.bpm.process.spi.AdapterResult;
import io.github.dhluong90.bpm.process.spi.ServiceAdapter;
import java.util.Map;

/** The runner of remote service steps ({@code ASYNC_SERVICE}): calls the host's {@link ServiceAdapter} by name. */
public final class ServiceStepRunner implements AsyncStepRunner {

    private final Map<String, ServiceAdapter> adapters;

    /** @param adapters the host's adapters by name */
    public ServiceStepRunner(Map<String, ServiceAdapter> adapters) {
        this.adapters = Map.copyOf(adapters);
    }

    @Override
    public String jobKind() {
        return JobStore.ASYNC_SERVICE;
    }

    @Override
    public AdapterResult run(String target, AdapterCall call) {
        ServiceAdapter adapter = adapters.get(target);
        if (adapter == null) {
            return new AdapterResult.Failed("ADAPTER_NOT_REGISTERED", "No ServiceAdapter named " + target, false);
        }
        return adapter.execute(call);
    }
}
