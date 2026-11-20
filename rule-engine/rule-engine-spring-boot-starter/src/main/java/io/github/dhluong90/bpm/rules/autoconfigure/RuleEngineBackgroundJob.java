package io.github.dhluong90.bpm.rules.autoconfigure;

import java.time.Instant;

/**
 * A background loop of the engine whose state the {@code ruleEngine} health contributor reports: the time of its last
 * successful iteration, and whether it stopped after a JVM-fatal error. A loop never ends on any other
 * {@code Throwable}: it logs the iteration's failure and runs again at the next period.
 */
public interface RuleEngineBackgroundJob {

    /**
     * Returns the job's name in health details, for example {@code partitionMaintenance}.
     *
     * @return a camelCase name
     */
    String jobName();

    /**
     * Returns when the last iteration succeeded.
     *
     * @return the instant from the engine clock, or null before the first success
     */
    Instant lastSuccess();

    /**
     * Tells whether the loop stopped after a {@link VirtualMachineError}; the health contributor then reports
     * {@code DOWN} so the orchestrator restarts the host.
     *
     * @return true once stopped
     */
    boolean stopped();
}
