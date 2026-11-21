package io.github.dhluong90.bpm.process.api;

import java.util.List;
import java.util.Map;

/**
 * Reads and fixes incidents (#58, #68, DESIGN section 3.6): an operator retries the failed step, or proposes a
 * resolution that a second person approves (PD-126). The tenant comes from the tenant resolver, never from a
 * parameter; another tenant's incident is {@link IncidentNotFoundException} and nothing is written. Safe for
 * concurrent use.
 *
 * <p>Every command runs in one transaction under the instance's row lock, is optimistically locked by the incident's
 * {@code lockVersion} and idempotent by its key: a replay returns the first answer and writes nothing, the same key
 * with another request is {@link IdempotencyKeyReuseException}. Each command records its actor and reason in the
 * append-only history and hands its event to the host publisher in the same transaction.
 */
public interface IncidentService {

    /**
     * @param instanceId an instance of the caller's tenant
     * @return its unresolved incidents ({@code OPEN} and {@code RESOLUTION_PENDING}), oldest first
     * @throws InstanceNotFoundException when the tenant has no such instance, also when another tenant has it
     * @throws PermissionDeniedException without {@code processes:read}
     */
    List<Incident> openIncidents(String instanceId);

    /**
     * Lists the caller's tenant's incidents of one status, newest first (PRE-080).
     *
     * @param query the status and page
     * @return a page; its cursor is signed and bound to the tenant and status
     * @throws QueryInvalidException for an unknown status, a bad limit or a cursor issued for another list
     * @throws PermissionDeniedException without {@code processes:read}
     */
    Page<Incident> incidents(IncidentQuery query);

    /**
     * @param incidentId an incident of the caller's tenant
     * @return the incident, with the actions the caller may take
     * @throws IncidentNotFoundException when the tenant has no such incident
     * @throws PermissionDeniedException without {@code processes:read}
     */
    Incident incident(String incidentId);

    /**
     * Runs the failed step again (PRE-077). An async step's job is due now with one more attempt and keeps its job id,
     * so the remote side deduplicates the call; the attempt id is new and an agent's result is checked again. A local
     * step's handler runs again in this transaction. One person's action: it repeats the same step, it decides
     * nothing. The incident stays {@code OPEN} with {@code retrying} until the step is accepted
     * ({@code RETRY_SUCCEEDED}) or fails again, which keeps it open with the new cause.
     *
     * @param incidentId the incident
     * @param expectedLockVersion its lock version
     * @param reason why, required, at most {@code lifecycle.reason-max-length} characters
     * @param idempotencyKey required
     * @return the incident after the retry
     * @throws VersionConflictException when the lock version is stale
     * @throws InvalidStateException when the incident is not {@code OPEN}, a retry is in flight, or the step cannot
     *     run again (an agent step whose memory failed before any call)
     * @throws PreconditionRequiredException without an idempotency key
     * @throws InputInvalidException without a reason or with a reason that is too long
     * @throws PermissionDeniedException without {@code processes:incident:manage}
     */
    Incident retry(String incidentId, long expectedLockVersion, String reason, String idempotencyKey);

    /**
     * Proposes a resolution (PRE-078): the waiting step completes with {@code output}. The output is checked against
     * the step's output schema now; the guardrail is not run, because a person supplied it. Under
     * {@code incidents.resolve-approval: four-eyes} (the default) the incident becomes {@code RESOLUTION_PENDING}
     * and nothing moves until a second person approves; under {@code single} ({@code dev} profile only) it is applied
     * at once.
     *
     * @param incidentId the incident
     * @param expectedLockVersion its lock version
     * @param output the step's output, a JSON object
     * @param reason why, required, at most {@code lifecycle.reason-max-length} characters
     * @param idempotencyKey required
     * @return the incident
     * @throws OutputInvalidException when the output breaks the step's output schema; nothing is written
     * @throws VersionConflictException when the lock version is stale
     * @throws InvalidStateException when the incident is not {@code OPEN} or a retry is in flight
     */
    Incident resolve(String incidentId, long expectedLockVersion, Map<String, Object> output, String reason,
            String idempotencyKey);

    /**
     * Approves a proposed resolution as a second person (PRE-078): the step completes with the proposed output and the
     * token moves on; the incident is {@code RESOLVED} with {@code COMPLETED_MANUALLY}, recorded with both identities.
     *
     * @param incidentId the incident
     * @param expectedLockVersion its lock version
     * @param comment optional, at most {@code lifecycle.reason-max-length} characters
     * @param idempotencyKey required
     * @return the resolved incident
     * @throws SelfApprovalForbiddenException when the caller proposed it; nothing is written
     * @throws InvalidStateException when no resolution is pending
     */
    Incident approveResolution(String incidentId, long expectedLockVersion, String comment, String idempotencyKey);

    /**
     * Rejects a proposed resolution as a second person (PRE-078): the proposal is dropped, the incident is
     * {@code OPEN} again and the token stays on the step.
     *
     * @param incidentId the incident
     * @param expectedLockVersion its lock version
     * @param reason why, required
     * @param idempotencyKey required
     * @return the incident
     * @throws SelfApprovalForbiddenException when the caller proposed it
     * @throws InvalidStateException when no resolution is pending
     */
    Incident rejectResolution(String incidentId, long expectedLockVersion, String reason, String idempotencyKey);
}
