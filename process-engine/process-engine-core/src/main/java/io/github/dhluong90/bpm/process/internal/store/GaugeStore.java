package io.github.dhluong90.bpm.process.internal.store;

import java.time.Instant;
import java.util.Map;

/**
 * Reads the engine-wide figures behind the gauges (PRE-084) with a few bounded queries over partial indexes. The
 * figures carry no tenant: they count every tenant's work together, so a shared registry leaks no tenant's volume.
 * Called once per {@code metrics.gauge-refresh} on each replica, never per scrape.
 */
public interface GaugeStore {

    /**
     * @param activeInstances instances with status {@code RUNNING}
     * @param openIncidents incidents with status {@code OPEN}
     * @param oldestDue job kind to the due time of its oldest {@code DUE} job; a kind with none is absent
     */
    record Snapshot(long activeInstances, long openIncidents, Map<String, Instant> oldestDue) {
    }

    /** @return the current figures */
    Snapshot read();
}
