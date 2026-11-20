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
 * @param reasonMaxLength {@code lifecycle.reason-max-length}: longest cancellation reason, rejection or retirement
 *     reason, or approval comment, 1 to {@link EngineDefaults#MAX_REASON_LENGTH} (PD-103)
 * @param makerChecker {@code maker-checker.enabled}: when false (dev profile only, PRE-022) a requester may approve
 *     their own definition version, recorded as approver {@code system:dev-auto-approve}
 * @param approverRule {@code horus.bpm.lifecycle.approver-must-differ-from}, the shared host setting (PD-096):
 *     {@code MAKERS}, the default, or the relaxation {@code REQUESTER}
 * @param retireLastVersion {@code horus.bpm.lifecycle.retire-last-version} (PD-101)
 */
public record EngineSettings(boolean definitionsAutoApprove, int maxNodes, int maxBytes, Duration idempotencyRetention,
        Duration slowCallWarning, int maxPageSize, Duration maxRange, int reasonMaxLength, boolean makerChecker,
        ApproverRule approverRule, RetireLastVersionRule retireLastVersion) {

    /**
     * What retiring the last version in effect of a key needs (PD-101, {@code horus.bpm.lifecycle.retire-last-version}).
     * The {@code four-eyes} value is refused at start-up until the four-eyes retirement mode exists.
     */
    public enum RetireLastVersionRule {
        /** An explicit {@code confirmNoVersionInEffect}. The default. */
        CONFIRM,
        /** Never. */
        REFUSE
    }

    /** Approval mode of a retirement in this release ({@code horus.bpm.lifecycle.retire-approval: single}, PD-102). */
    public static final String RETIRE_APPROVAL_MODE = "single";

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
        if (approverRule == null) {
            approverRule = ApproverRule.MAKERS;
        }
        if (retireLastVersion == null) {
            retireLastVersion = RetireLastVersionRule.CONFIRM;
        }
    }

    /**
     * Settings with maker-checker on, the strict approver rule and the default retirement rule.
     *
     * @param definitionsAutoApprove see the record
     * @param maxNodes see the record
     * @param maxBytes see the record
     * @param idempotencyRetention see the record
     * @param slowCallWarning see the record
     * @param maxPageSize see the record
     * @param maxRange see the record
     * @param reasonMaxLength see the record
     */
    public EngineSettings(boolean definitionsAutoApprove, int maxNodes, int maxBytes, Duration idempotencyRetention,
            Duration slowCallWarning, int maxPageSize, Duration maxRange, int reasonMaxLength) {
        this(definitionsAutoApprove, maxNodes, maxBytes, idempotencyRetention, slowCallWarning, maxPageSize, maxRange,
                reasonMaxLength, true, ApproverRule.MAKERS, RetireLastVersionRule.CONFIRM);
    }

    /**
     * Settings with the default query limits, reason length and lifecycle rules.
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
