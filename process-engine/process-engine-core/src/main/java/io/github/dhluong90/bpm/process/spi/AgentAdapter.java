package io.github.dhluong90.bpm.process.spi;

/**
 * Host code behind an agent step ({@code serviceTask} with {@code horus:type="agentTask"}, #52), registered under a
 * name and referenced from BPMN by {@code horus:agent="<name>"}. The engine never embeds a model SDK: the host's
 * adapter calls its model, tool chain or vendor. The engine calls it through this interface only, never by
 * reflection (TR-SEC-01). The starter collects every bean of this type; there is no default.
 *
 * <p>Transactions (ADR-0008): called on the engine's adapter executor, in the agent runner's slots
 * ({@code runners.async-agent.*}), with no database connection and no row lock held. The engine abandons the call when
 * the step timeout elapses and interrupts its thread.
 *
 * <p>Checks: the adapter returns the model's answer as it is. The engine checks it against the step's output schema
 * and guardrail rule set in the result transaction, whichever path reported it; an adapter's own check is never the
 * authoritative one. A malformed answer is retried with a new attempt, at most
 * {@code runners.async-agent.schema-retries} times (default 1), then refused.
 *
 * <p>Keys: key the model call by {@link AgentInvocation#modelCallKey()} (the attempt id), so a retry is a fresh
 * generation; key side effects outside the model by {@link AgentInvocation#idempotencyKey()} (the job id).
 *
 * <p>Hand-off: an adapter that answers {@link AdapterResult.Accepted} passes the job id, the attempt id and the
 * completion token to the remote side, which reports the answer through the completion API; its caller needs
 * {@code processes:step:complete} and a role listed for this adapter in
 * {@code horus.bpm.processes.adapters.completer-roles}.
 *
 * <p>Implementations must be thread-safe.
 */
public interface AgentAdapter {

    /**
     * @return the name used in {@code horus:agent}, for example {@code documents.assess}
     */
    String name();

    /**
     * Asks the agent, or hands the call off.
     *
     * @param invocation the bounded inputs and identifiers of this attempt
     * @return the answer as {@link AdapterResult.Completed}, a failure, or {@link AdapterResult.Accepted} when the
     *     answer is reported later through the completion API
     */
    AdapterResult execute(AgentInvocation invocation);

    /**
     * Describes the input this agent takes, for the step catalogue a modeller reads (PRE-032, {@code GET /step-catalogue}):
     * a JSON Schema object. Never put a configuration value, a credential or an endpoint in it.
     *
     * @return the input schema, or an empty map when the step declares none (the default)
     */
    default java.util.Map<String, Object> inputSchema() {
        return java.util.Map.of();
    }
}
