package io.github.dhluong90.bpm.process.internal.service;

import java.time.Duration;
import java.util.Objects;

/**
 * Validated settings of the engine services, bound from {@code horus.bpm.processes.*} by the starter.
 *
 * @param definitionsAutoApprove import as {@code ACTIVE} with approver {@code system:dev-auto-approve}; the starter
 *     accepts it on the {@code dev} profile only (PRE-022)
 * @param maxNodes {@code definitions.max-nodes}, at most {@link EngineDefaults#MAX_NODES}
 * @param maxBytes {@code definitions.max-bytes}, at most {@link EngineDefaults#MAX_BYTES}
 * @param idempotencyRetention {@code idempotency.retention}
 */
public record EngineSettings(boolean definitionsAutoApprove, int maxNodes, int maxBytes, Duration idempotencyRetention) {

    public EngineSettings {
        if (maxNodes < 2 || maxNodes > EngineDefaults.MAX_NODES) {
            throw new IllegalArgumentException("definitions.max-nodes must be 2 to " + EngineDefaults.MAX_NODES);
        }
        if (maxBytes < 1_024 || maxBytes > EngineDefaults.MAX_BYTES) {
            throw new IllegalArgumentException("definitions.max-bytes must be 1 KB to " + EngineDefaults.MAX_BYTES);
        }
        Objects.requireNonNull(idempotencyRetention, "idempotencyRetention must not be null");
        if (idempotencyRetention.isNegative() || idempotencyRetention.isZero()) {
            throw new IllegalArgumentException("idempotency.retention must be positive");
        }
    }

    /** @return the engine defaults, auto-approve off */
    public static EngineSettings defaults() {
        return new EngineSettings(false, EngineDefaults.MAX_NODES, EngineDefaults.MAX_BYTES,
                EngineDefaults.IDEMPOTENCY_RETENTION);
    }
}
