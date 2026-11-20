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
 * @param slowCallWarning {@code handlers.slow-call-warning}: local steps and event listeners slower than this are logged
 * @param maxPageSize {@code query.max-page-size}, 1 to {@link EngineDefaults#MAX_PAGE_SIZE}
 * @param maxRange {@code query.max-range}: longest start-time range of a list, at most {@link EngineDefaults#MAX_RANGE}
 * @param reasonMaxLength {@code lifecycle.reason-max-length}: longest cancellation reason, 1 to
 *     {@link EngineDefaults#MAX_REASON_LENGTH}
 */
public record EngineSettings(boolean definitionsAutoApprove, int maxNodes, int maxBytes, Duration idempotencyRetention,
        Duration slowCallWarning, int maxPageSize, Duration maxRange, int reasonMaxLength) {

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
        if (slowCallWarning == null) {
            slowCallWarning = EngineDefaults.SLOW_CALL_WARNING;
        }
        if (maxPageSize < 1 || maxPageSize > EngineDefaults.MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("query.max-page-size must be 1 to " + EngineDefaults.MAX_PAGE_SIZE);
        }
        Objects.requireNonNull(maxRange, "maxRange must not be null");
        if (maxRange.isNegative() || maxRange.isZero() || maxRange.compareTo(EngineDefaults.MAX_RANGE) > 0) {
            throw new IllegalArgumentException("query.max-range must be positive and at most " + EngineDefaults.MAX_RANGE);
        }
        if (reasonMaxLength < 1 || reasonMaxLength > EngineDefaults.MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("lifecycle.reason-max-length must be 1 to " + EngineDefaults.MAX_REASON_LENGTH);
        }
    }

    /**
     * Settings with the default query limits.
     *
     * @param definitionsAutoApprove see the record
     * @param maxNodes see the record
     * @param maxBytes see the record
     * @param idempotencyRetention see the record
     * @param slowCallWarning see the record
     */
    public EngineSettings(boolean definitionsAutoApprove, int maxNodes, int maxBytes, Duration idempotencyRetention,
            Duration slowCallWarning) {
        this(definitionsAutoApprove, maxNodes, maxBytes, idempotencyRetention, slowCallWarning,
                EngineDefaults.MAX_PAGE_SIZE, EngineDefaults.MAX_RANGE, EngineDefaults.MAX_REASON_LENGTH);
    }

    /** @return the engine defaults, auto-approve off */
    public static EngineSettings defaults() {
        return new EngineSettings(false, EngineDefaults.MAX_NODES, EngineDefaults.MAX_BYTES,
                EngineDefaults.IDEMPOTENCY_RETENTION, EngineDefaults.SLOW_CALL_WARNING);
    }
}
