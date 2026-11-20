package io.github.dhluong90.bpm.process.api;

import io.github.dhluong90.bpm.process.spi.AdapterResult;

/**
 * Completes an attempt of a remote step from outside the engine, for a {@code ServiceAdapter} that answered
 * {@link AdapterResult.Accepted} and reports the result later (a partner callback, a queue consumer). The tenant comes
 * from the host's {@code TenantResolver}. The caller needs {@code processes:step:complete} and one of the roles that
 * {@code horus.bpm.processes.adapters.completer-roles} lists for the step's adapter, and must present the attempt's
 * completion token.
 *
 * <p>The result is recorded in one transaction, and only while the attempt is current: the job is {@code CLAIMED}
 * under that attempt and its lease has not ended (ADR-0008, TR-ASY-02). Repeating a completion is safe: a repeat of a
 * recorded attempt answers {@code DUPLICATE} and writes nothing.
 */
public interface AsyncStepService {

    /**
     * @param jobId the job id the adapter received in {@code AdapterCall.jobId()}
     * @param attemptId the attempt id the adapter received
     * @param completionToken the completion token the adapter received in {@code AdapterCall.completionToken()}
     * @param result {@code Completed}, {@code BusinessError} or {@code Failed}; {@code Accepted} is refused
     * @return what the engine did with the result
     * @throws AsyncStepNotFoundException when the job is not in the caller's tenant; nothing is written
     * @throws PermissionDeniedException without {@code processes:step:complete}, without a completer role of the
     *     step's adapter, or with a token that does not match the attempt; nothing is written
     * @throws InputInvalidException for a blank id or token, or an {@code Accepted} result
     */
    StepCompletion complete(String jobId, String attemptId, String completionToken, AdapterResult result);
}
