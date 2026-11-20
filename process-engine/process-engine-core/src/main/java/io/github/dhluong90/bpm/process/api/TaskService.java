package io.github.dhluong90.bpm.process.api;

import java.util.Map;

/**
 * Reads, claims, unclaims and completes user tasks (DESIGN section 3.5). Safe for concurrent use.
 *
 * <p>Transactions: joins the caller's transaction or runs in its own. Every change takes the {@code pre_instance}
 * row lock, checks the task's lock version, changes the task with a conditional update, and writes its history entry
 * and event in the same transaction; a completion then runs the following synchronous steps in it too.
 *
 * <p>Deliberate gaps of this release: forms are not validated, and delegate and reassign come later.
 */
public interface TaskService {

    /**
     * Reads a task of the caller's tenant with the actions the caller may take. Needs {@code processes:task:work}.
     *
     * @param taskId the task id
     * @return the task
     * @throws TaskNotFoundException when no such task exists in the tenant
     */
    TaskView task(String taskId);

    /**
     * Claims an open task for the caller ({@code OPEN} to {@code CLAIMED}). Needs {@code processes:task:work}, and
     * the caller must be the assigned user, hold the assigned role or belong to the assigned group.
     *
     * @param taskId the task id
     * @param expectedLockVersion the task's lock version, sent as {@code If-Match}
     * @param idempotencyKey required; a replay with the same key and request returns the original result
     * @return the claimed task
     * @throws TaskNotFoundException when no such task exists in the tenant
     * @throws VersionConflictException when {@code expectedLockVersion} is stale
     * @throws InvalidStateException when the task is not open
     * @throws TaskNotAssignedException when the caller is not an assignee
     * @throws SelfApprovalForbiddenException when the task is four-eyes and the caller started the instance or
     *     completed an earlier task of it (PRE-007)
     */
    TaskView claim(String taskId, long expectedLockVersion, String idempotencyKey);

    /**
     * Hands a claimed task back to its candidates ({@code CLAIMED} to {@code OPEN}). Needs
     * {@code processes:task:work}; only the claimant may unclaim.
     *
     * @param taskId the task id
     * @param expectedLockVersion the task's lock version, sent as {@code If-Match}
     * @param idempotencyKey required; a replay with the same key and request returns the original result
     * @return the open task
     * @throws TaskNotFoundException when no such task exists in the tenant
     * @throws VersionConflictException when {@code expectedLockVersion} is stale
     * @throws InvalidStateException when the task is not claimed
     * @throws TaskNotAssignedException when the caller is not the claimant
     */
    TaskView unclaim(String taskId, long expectedLockVersion, String idempotencyKey);

    /**
     * Completes a task the caller has claimed. Needs {@code processes:task:work}; the caller must be the claimant and
     * still be the assigned user, hold the assigned role or belong to the assigned group.
     *
     * @param taskId the task id
     * @param result values stored in the task's result variable, or merged into the instance variables when
     *     the task declares none
     * @param expectedLockVersion the task's lock version ({@code ProcessInstance.Task.lockVersion}), sent as
     *     {@code If-Match}; required
     * @param idempotencyKey optional; a replay with the same key and request returns the original result
     * @return the instance after the following synchronous steps ran
     * @throws TaskNotFoundException when no such task exists in the tenant
     * @throws InvalidStateException when the task is not claimed (an {@code OPEN} task must be claimed first)
     * @throws VersionConflictException when {@code expectedLockVersion} is stale
     * @throws TaskNotAssignedException when another user claimed the task, or the caller is no longer an assignee
     * @throws SelfApprovalForbiddenException when the task is four-eyes and the caller started the instance or
     *     completed an earlier task of it ({@code SELF_APPROVAL_FORBIDDEN}, PRE-007); every implementation checks it
     */
    ProcessInstance complete(String taskId, Map<String, Object> result, long expectedLockVersion,
            String idempotencyKey);
}
