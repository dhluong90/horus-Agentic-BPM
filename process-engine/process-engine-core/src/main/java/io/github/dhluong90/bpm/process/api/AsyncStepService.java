package io.github.dhluong90.bpm.process.api;

import io.github.dhluong90.bpm.process.spi.AdapterResult;

/**
 * Completes an attempt of a remote step from outside the engine, for a {@code ServiceAdapter} that answered
 * {@link AdapterResult.Accepted} and reports the result later (a partner callback, a queue consumer). The tenant comes
 * from the host's {@code TenantResolver}; needs {@code processes:step:complete}.
 *
 * <p>The result is recorded in one transaction, and only while the attempt is current: the job is {@code CLAIMED}
 * under that attempt and its lease has not ended (ADR-0008, TR-ASY-02). Repeating a completion is safe: the attempt id
 * is the idempotency key.
 */
public interface AsyncStepService {

    /**
     * @param jobId the job id the adapter received in {@code AdapterCall.jobId()}
     * @param attemptId the attempt id the adapter received
     * @param result {@code Completed}, {@code BusinessError} or {@code Failed}; {@code Accepted} is refused
     * @return what the engine did with the result
     * @throws AsyncStepNotFoundException when the job is not in the caller's tenant; nothing is written
     * @throws PermissionDeniedException without {@code processes:step:complete}
     * @throws InputInvalidException for a blank id or an {@code Accepted} result
     */
    StepCompletion complete(String jobId, String attemptId, AdapterResult result);
}
