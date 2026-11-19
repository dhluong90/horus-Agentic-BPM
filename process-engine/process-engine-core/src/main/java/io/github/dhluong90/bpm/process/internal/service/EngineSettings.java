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
 * @param makerChecker {@code maker-checker.enabled}: when false (dev profile only, PRE-022) a requester may approve
 *     their own definition version, recorded as approver {@code system:dev-auto-approve}
 * @param reasonMaxLength {@code lifecycle.reason-max-length}: longest rejection reason or approval comment
 * @param approverRule {@code horus.bpm.lifecycle.approver-must-differ-from}, the shared host setting (PD-096): {@code MAKERS}, the default, or the
 *     relaxation {@code REQUESTER}
 */
public record EngineSettings(boolean definitionsAutoApprove, int maxNodes, int maxBytes, Duration idempotencyRetention,
        Duration slowCallWarning, boolean makerChecker, int reasonMaxLength, ApproverRule approverRule) {

    /** Whom the approver of a definition version must differ from (PD-096). */
    public enum ApproverRule {
        /** Every maker: the importer or creator, every editor, and the requester. The default. */
        MAKERS,
        /** The requester only. A relaxation, never the default; recorded on every approval. */
        REQUESTER;

        /** @return the setting value, {@code makers} or {@code requester} */
        public String value() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * Settings with the strict approver rule.
     *
     * @param definitionsAutoApprove dev auto-approval
     * @param maxNodes most nodes
     * @param maxBytes largest document
     * @param idempotencyRetention idempotency retention
     * @param slowCallWarning slow call threshold
     * @param makerChecker maker-checker on
     * @param reasonMaxLength longest reason
     */
    public EngineSettings(boolean definitionsAutoApprove, int maxNodes, int maxBytes, Duration idempotencyRetention,
            Duration slowCallWarning, boolean makerChecker, int reasonMaxLength) {
        this(definitionsAutoApprove, maxNodes, maxBytes, idempotencyRetention, slowCallWarning, makerChecker,
                reasonMaxLength, ApproverRule.MAKERS);
    }

    /** Default longest rejection reason or approval comment. */
    public static final int DEFAULT_REASON_MAX_LENGTH = 1_000;

    /**
     * Settings with maker-checker on and the default reason length.
     *
     * @param definitionsAutoApprove dev auto-approval
     * @param maxNodes most nodes
     * @param maxBytes largest document
     * @param idempotencyRetention idempotency retention
     * @param slowCallWarning slow call threshold
     */
    public EngineSettings(boolean definitionsAutoApprove, int maxNodes, int maxBytes, Duration idempotencyRetention,
            Duration slowCallWarning) {
        this(definitionsAutoApprove, maxNodes, maxBytes, idempotencyRetention, slowCallWarning, true,
                DEFAULT_REASON_MAX_LENGTH);
    }

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
        if (approverRule == null) {
            approverRule = ApproverRule.MAKERS;
        }
        if (reasonMaxLength < 1 || reasonMaxLength > 10_000) {
            throw new IllegalArgumentException("lifecycle.reason-max-length must be 1 to 10000");
        }
    }

    /** @return the engine defaults, auto-approve off */
    public static EngineSettings defaults() {
        return new EngineSettings(false, EngineDefaults.MAX_NODES, EngineDefaults.MAX_BYTES,
                EngineDefaults.IDEMPOTENCY_RETENTION, EngineDefaults.SLOW_CALL_WARNING);
    }
}
