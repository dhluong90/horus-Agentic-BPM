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
}
