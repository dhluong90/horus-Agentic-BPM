package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.spi.AdapterCall;
import io.github.dhluong90.bpm.process.spi.AdapterResult;

/**
 * Runs the calls of one async job kind (#363): {@code ASYNC_SERVICE} through the host's {@code ServiceAdapter}s, and
 * {@code ASYNC_AGENT} through the agent adapter of the agent step. The {@link AsyncStepExecutor} gives each runner
 * its own slots, timeout and retry settings ({@link RunnerSettings}), claims jobs of its kind only, and applies the
 * timeout, the transaction guard and the error mapping around {@link #run}, so a runner holds no engine logic.
 *
 * <p>A runner never checks the result it returns: the output schema and the guardrails run in the result
 * transaction ({@link ProcessServices#recordAttempt}), where a result reported through the completion API is checked
 * the same way. A runner may pre-check for early feedback, but that check is never the authoritative one.
 */
public interface AsyncStepRunner {

    /** @return the job kind this runner runs, as stored in {@code pre_timer.job_kind} */
    String jobKind();

    /**
     * One call, on an executor thread with no transaction, no connection and no row lock (ADR-0008). Called at most
     * once per claim; interrupted when the step timeout elapses.
     *
     * @param target the adapter name the step names
     * @param call the call, with the attempt's ids, completion token and deadline
     * @return the result, or {@link AdapterResult.Accepted} when the remote side reports it later
     */
    AdapterResult run(String target, AdapterCall call);

    /**
     * The same call, with the job's stored payload, for a runner that passes part of the step's published spec on to
     * its adapter (the agent runner passes the output schema). The payload was written when the step parked, from the
     * approved definition only; a runner reads it and never changes it. By default the payload is not used.
     *
     * @param target the adapter name the step names
     * @param call the call
     * @param payload the job payload, read-only
     * @return the result
     */
    default AdapterResult run(String target, AdapterCall call, java.util.Map<String, Object> payload) {
        return run(target, call);
    }
}
