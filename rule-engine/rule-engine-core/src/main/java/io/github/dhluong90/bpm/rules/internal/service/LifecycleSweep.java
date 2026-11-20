package io.github.dhluong90.bpm.rules.internal.service;

import java.util.Objects;

/**
 * The lifecycle sweep (DESIGN section 3.3.2, RLE-027): retires, across tenants, every published version a newer
 * version in effect has superseded, in one ordinary transaction with no advisory lock and no session state, so it runs
 * on every replica behind a transaction-mode pooler (TR-OPS-05) and still records each retirement exactly once. Status
 * only: selection already follows the effective date, so no decision waits for the sweep. The starter calls
 * {@link #run()} every {@code lifecycle.sweep-interval}. Thread-safe.
 */
public final class LifecycleSweep {

    private final EngineContext context;
    private final Supersession supersession;

    LifecycleSweep(EngineContext context, Supersession supersession) {
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.supersession = Objects.requireNonNull(supersession, "supersession must not be null");
    }

    /**
     * Runs one sweep at the engine clock.
     *
     * @return the number of versions this replica retired; another replica's retirements are not counted
     */
    public int run() {
        return context.transactions().inTransaction(() ->
                supersession.retire(null, null, context.clock().instant()));
    }
}
