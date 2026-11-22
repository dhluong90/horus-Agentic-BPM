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
 * @param requireContentHash {@code approval.require-content-hash}: an approval must name the hash of the content the
 *     approver reviewed (PD-139, PRE-177); {@code false} is accepted on the {@code dev} profile only
 * @param resolveApproval {@code incidents.resolve-approval}: whether resolving an incident with an operator's output
 *     needs a second person (PD-126, PRE-078); {@code SINGLE} is accepted on the {@code dev} profile only
 * @param warnUnclassified {@code classification.warn-unclassified}: list the agent step fields that declare no
 *     {@code mask} (PD-146, #438); a reminder that never blocks. Default {@code true}
 */
public record EngineSettings(boolean definitionsAutoApprove, int maxNodes, int maxBytes, Duration idempotencyRetention,
        Duration slowCallWarning, int maxPageSize, Duration maxRange, int reasonMaxLength, boolean makerChecker,
        ApproverRule approverRule, RetireLastVersionRule retireLastVersion, boolean requireContentHash,
        ResolveApproval resolveApproval, boolean warnUnclassified) {

    /** Who resolves an incident with an operator's output (PD-126, {@code incidents.resolve-approval}). */
    public enum ResolveApproval {
        /** A proposal by one holder of {@code processes:incident:manage}, approved by another. The default. */
        FOUR_EYES,
        /** The proposer's resolution applies at once. A relaxation accepted on the {@code dev} profile only. */
        SINGLE;

        /** @return the setting value, {@code four-eyes} or {@code single} */
        public String value() {
            return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        }
    }

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
        if (resolveApproval == null) {
            resolveApproval = ResolveApproval.FOUR_EYES;
        }
    }

    /**
     * Settings that warn about unclassified fields, the default of PD-146.
     *
     * @param definitionsAutoApprove see the record
     * @param maxNodes see the record
     * @param maxBytes see the record
     * @param idempotencyRetention see the record
     * @param slowCallWarning see the record
     * @param maxPageSize see the record
     * @param maxRange see the record
     * @param reasonMaxLength see the record
     * @param makerChecker see the record
     * @param approverRule see the record
     * @param retireLastVersion see the record
     * @param requireContentHash see the record
     * @param resolveApproval see the record
     */
    public EngineSettings(boolean definitionsAutoApprove, int maxNodes, int maxBytes, Duration idempotencyRetention,
            Duration slowCallWarning, int maxPageSize, Duration maxRange, int reasonMaxLength, boolean makerChecker,
            ApproverRule approverRule, RetireLastVersionRule retireLastVersion, boolean requireContentHash,
            ResolveApproval resolveApproval) {
        this(definitionsAutoApprove, maxNodes, maxBytes, idempotencyRetention, slowCallWarning, maxPageSize, maxRange,
                reasonMaxLength, makerChecker, approverRule, retireLastVersion, requireContentHash, resolveApproval,
                true);
    }

    /**
     * Settings with the strict defaults of PD-126 and PD-139: approvals name the reviewed hash, and resolving an
     * incident needs a second person.
     *
     * @param definitionsAutoApprove see the record
     * @param maxNodes see the record
     * @param maxBytes see the record
     * @param idempotencyRetention see the record
     * @param slowCallWarning see the record
     * @param maxPageSize see the record
     * @param maxRange see the record
     * @param reasonMaxLength see the record
     * @param makerChecker see the record
     * @param approverRule see the record
     * @param retireLastVersion see the record
     */
    public EngineSettings(boolean definitionsAutoApprove, int maxNodes, int maxBytes, Duration idempotencyRetention,
            Duration slowCallWarning, int maxPageSize, Duration maxRange, int reasonMaxLength, boolean makerChecker,
            ApproverRule approverRule, RetireLastVersionRule retireLastVersion) {
        this(definitionsAutoApprove, maxNodes, maxBytes, idempotencyRetention, slowCallWarning, maxPageSize, maxRange,
                reasonMaxLength, makerChecker, approverRule, retireLastVersion, true, ResolveApproval.FOUR_EYES);
    }

    /**
     * @param require see {@link #requireContentHash()}
     * @return a copy with the content-hash rule set
     */
    public EngineSettings withRequireContentHash(boolean require) {
        return new EngineSettings(definitionsAutoApprove, maxNodes, maxBytes, idempotencyRetention, slowCallWarning,
                maxPageSize, maxRange, reasonMaxLength, makerChecker, approverRule, retireLastVersion, require,
                resolveApproval, warnUnclassified);
    }

    /**
     * @param warn see {@link #warnUnclassified()}
     * @return a copy with the unclassified-field reminder on or off
     */
    public EngineSettings withWarnUnclassified(boolean warn) {
        return new EngineSettings(definitionsAutoApprove, maxNodes, maxBytes, idempotencyRetention, slowCallWarning,
                maxPageSize, maxRange, reasonMaxLength, makerChecker, approverRule, retireLastVersion,
                requireContentHash, resolveApproval, warn);
    }

    /**
     * @param approval see {@link #resolveApproval()}
     * @return a copy with the incident resolution rule set
     */
    public EngineSettings withResolveApproval(ResolveApproval approval) {
        return new EngineSettings(definitionsAutoApprove, maxNodes, maxBytes, idempotencyRetention, slowCallWarning,
                maxPageSize, maxRange, reasonMaxLength, makerChecker, approverRule, retireLastVersion,
                requireContentHash, approval, warnUnclassified);
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
