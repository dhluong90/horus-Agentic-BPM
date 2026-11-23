package io.github.dhluong90.bpm.process.api;

/**
 * Starts and reads process instances. Safe for concurrent use.
 *
 * <p>Transactions: {@link #start} joins the caller's transaction when there is one, or runs in its own. Every
 * synchronous step until the first wait state ({@code userTask}) or the end runs in that transaction:
 * {@code serviceTask} handlers, {@code businessRuleTask} evaluations through {@code RuleEngine.evaluate} and
 * gateway conditions. When a step fails the whole transaction rolls back, with the host's own writes in it.
 */
public interface ProcessEngine {

    /**
     * Starts an instance on the effective version. Needs {@code processes:start}.
     *
     * @param request the request
     * @return the instance after its synchronous steps ran
     * @throws NoEffectiveVersionException when the key has no active version in the tenant
     * @throws StepFailedException when a synchronous step failed; nothing is written
     */
    ProcessInstance start(StartRequest request);

    /**
     * Reads an instance of the caller's tenant. Needs {@code processes:read}.
     *
     * @param instanceId the instance id
     * @return the instance
     * @throws InstanceNotFoundException when no such instance exists in the tenant
     */
    ProcessInstance instance(String instanceId);

    /**
     * Cancels a running instance of the caller's tenant: every live token and open task, with an
     * {@code INSTANCE_ENDED} history entry and {@code processes.instance.cancelled.v1}, both with the actor and the
     * reason, in one transaction. Needs {@code processes:cancel}, which {@code processes:start} does not grant (PD-103).
     * Cancel makes no adapter call and runs no compensation in 0.2.0 (ADR-0020 decision 2).
     *
     * @param instanceId the instance id
     * @param expectedLockVersion the instance's lock version ({@link ProcessInstance#lockVersion()}), sent as
     *     {@code If-Match}
     * @param reason why, required, at most {@code lifecycle.reason-max-length} (2,000) characters
     * @param idempotencyKey required; a replay with the same key and request returns the original result
     * @return the cancelled instance
     * @throws InstanceNotFoundException when no such instance exists in the tenant
     * @throws VersionConflictException when {@code expectedLockVersion} is stale
     * @throws InvalidStateException when the instance is not running
     * @throws PreconditionRequiredException when {@code idempotencyKey} is null
     * @throws InputInvalidException when the reason is missing, blank or too long
     */
    ProcessInstance cancel(String instanceId, long expectedLockVersion, String reason, String idempotencyKey);

    /**
     * Starts one activity of an open ad-hoc sub-process of a running instance (#500). Needs
     * {@code processes:task:manage}. The activity must stand in the sub-process, and with {@code ordering} Sequential
     * no other activity of it may be running.
     *
     * @param instanceId the instance id
     * @param expectedLockVersion the instance's lock version, sent as {@code If-Match}
     * @param scopeTokenId the scope token of the ad-hoc sub-process, from the instance's tokens
     * @param activityId the activity to start
     * @param idempotencyKey required; a replay with the same key and request returns the original result
     * @return the instance after the start
     * @throws InstanceNotFoundException when no such instance exists in the tenant
     * @throws VersionConflictException when {@code expectedLockVersion} is stale
     * @throws InvalidStateException when the instance is not running, the scope is not an open ad-hoc sub-process, or
     *     the activity is not enabled
     * @throws PreconditionRequiredException when {@code idempotencyKey} is null
     */
    ProcessInstance startAdHocActivity(String instanceId, long expectedLockVersion, String scopeTokenId,
            String activityId, String idempotencyKey);
}
