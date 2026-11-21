package io.github.dhluong90.bpm.process.api;

import java.util.Map;

/**
 * Offline replay of an agent step's recorded answers against a candidate guardrail or output schema, before it goes
 * live (PRE-092 to PRE-095, PD-128). A replay never calls a model or any adapter, never writes history or the decision
 * log, and never moves an instance: it re-runs the result check (output schema, then guardrail) over the recorded
 * answers through the Rule Engine's simulation path, at each answer's recorded time. Prompt or model changes are
 * replayed by a host harness, outside the engine, because masked inputs are kept only as keyed hashes.
 *
 * <p>Every method needs {@code processes:agent:replay} and reads only the caller's tenant, from the resolver. A run is
 * created at once and executed in pages of {@code replay.page-size} by the job poller, never inside a step.
 */
public interface AgentReplayService {

    /**
     * Starts a replay run.
     *
     * @param request the step, period and candidate
     * @return the queued run; on a repeat with the same idempotency key and request, the original run
     * @throws QueryInvalidException when {@code from} is after {@code to}, or the period is longer than
     *     {@code query.max-range}
     * @throws ReplayTooLargeException when the period holds more answers than {@code replay.max-cases}
     * @throws GuardrailNotFoundException when the caller's tenant has no such candidate version
     * @throws GuardrailContractInvalidException when the candidate breaks the guardrail contract (PRE-016); no run is
     *     created
     * @throws IdempotencyKeyReuseException when the key was used with a different request
     */
    AgentReplayRun start(AgentReplayRequest request);

    /**
     * Reads a run and its summary.
     *
     * @param runId the run id
     * @return the run
     * @throws ReplayNotFoundException when the caller's tenant has no such run
     */
    AgentReplayRun run(String runId);

    /**
     * Reads report lines in recorded order.
     *
     * @param runId the run id
     * @param page limit and cursor
     * @return the lines
     * @throws ReplayNotFoundException when the caller's tenant has no such run
     */
    Page<AgentReplayCase> cases(String runId, PageRequest page);

    /**
     * Opens one case's recorded answer as history keeps it: masked output fields as {@code ***} and masked inputs as
     * keyed hashes.
     *
     * @param runId the run id
     * @param attemptId the case's attempt id
     * @return {@code output} and {@code inputs} as recorded
     * @throws ReplayNotFoundException when the caller's tenant has no such run, or the run has no such case
     */
    Map<String, Object> recordedAnswer(String runId, String attemptId);
}
