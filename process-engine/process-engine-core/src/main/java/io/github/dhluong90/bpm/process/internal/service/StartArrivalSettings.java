package io.github.dhluong90.bpm.process.internal.service;

import java.time.Duration;

/**
 * Bounds of the held arrivals of a process whose start event waits for several messages or signals (PD-149, AUD-10):
 * an arrival is dropped after {@code ttl}, and one definition holds at most {@code maxPerDefinition} of them, so a
 * caller that sends endless distinct correlation keys cannot grow the table without limit.
 *
 * @param ttl how long an arrival waits for the others, 1 minute to 366 days
 * @param maxPerDefinition most arrivals held for one definition of one tenant, 1 to 1,000,000
 */
public record StartArrivalSettings(Duration ttl, int maxPerDefinition) {

    /** Used when the host sets nothing: 24 hours and 10,000 arrivals per definition. */
    public static final StartArrivalSettings DEFAULTS = new StartArrivalSettings(Duration.ofHours(24), 10_000);

    /** Checks the bounds. */
    public StartArrivalSettings {
        if (ttl == null || ttl.compareTo(Duration.ofMinutes(1)) < 0 || ttl.compareTo(Duration.ofDays(366)) > 0) {
            throw new IllegalArgumentException("parallel-start.arrival-ttl must be 1 minute to 366 days");
        }
        if (maxPerDefinition < 1 || maxPerDefinition > 1_000_000) {
            throw new IllegalArgumentException("parallel-start.max-arrivals-per-definition must be 1 to 1,000,000");
        }
    }
}
