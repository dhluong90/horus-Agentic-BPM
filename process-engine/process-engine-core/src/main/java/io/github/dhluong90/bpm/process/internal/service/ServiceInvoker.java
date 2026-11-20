package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.internal.exec.Effect;
import io.github.dhluong90.bpm.process.internal.exec.StepResult;
import java.time.Instant;

/** Runs an {@link Effect.InvokeService} in {@code SYNC_LOCAL} mode, inside the current transaction. */
@FunctionalInterface
public interface ServiceInvoker {

    /**
     * @param tenantId the instance's tenant, from the stored instance row
     * @param instanceId instance
     * @param businessKey business key or null
     * @param call the invocation
     * @param caller who the step runs for; used by decisions
     * @param now the engine clock at the step; a decision's evaluation time
     * @return the result to deliver back to the kernel
     */
    StepResult invoke(String tenantId, String instanceId, String businessKey, Effect.InvokeService call,
            DecisionCaller caller, Instant now);
}
