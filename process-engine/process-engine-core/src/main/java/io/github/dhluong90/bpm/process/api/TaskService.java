package io.github.dhluong90.bpm.process.api;

import java.util.Map;

/**
 * Completes user tasks. Safe for concurrent use.
 *
 * <p>Transactions: joins the caller's transaction or runs in its own. Takes the {@code pre_instance} row lock,
 * completes the task, and runs the following synchronous steps in the same transaction.
 */
public interface TaskService {

    /**
     * Completes an open task. Needs {@code processes:task:work}, and the caller must be the assigned user, hold
     * the assigned role or belong to the assigned group.
     *
     * @param taskId the task id
     * @param result values stored in the task's result variable, or merged into the instance variables when
     *     the task declares none
     * @param expectedLockVersion the task lock version from {@code If-Match}, or null to skip the check
     * @param idempotencyKey optional; a replay with the same key and request returns the original result
     * @return the instance after the following synchronous steps ran
     * @throws TaskNotFoundException when no such task exists in the tenant
     * @throws InvalidStateException when the task is not open
     * @throws VersionConflictException when {@code expectedLockVersion} is stale
     * @throws TaskNotAssignedException when the caller is not an assignee
     */
    ProcessInstance complete(String taskId, Map<String, Object> result, Long expectedLockVersion,
            String idempotencyKey);
}
