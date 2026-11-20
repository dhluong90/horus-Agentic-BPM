package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.internal.exec.Effect;
import io.github.dhluong90.bpm.process.internal.exec.StepResult;

/** Runs an {@link Effect.InvokeService} in {@code SYNC_LOCAL} mode, inside the current transaction. */
@FunctionalInterface
public interface ServiceInvoker {

    /**
     * @param tenantId tenant
     * @param instanceId instance
     * @param businessKey business key or null
     * @param call the invocation
     * @return the result to deliver back to the kernel
     */
    StepResult invoke(String tenantId, String instanceId, String businessKey, Effect.InvokeService call);
}
