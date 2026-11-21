package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.expr.internal.EvaluationLimits;
import io.github.dhluong90.bpm.expr.internal.RegexLimits;
import java.time.Duration;
import java.util.List;

/**
 * Validated engine settings. A value outside its range fails construction with a message naming the
 * property, so an invalid host configuration stops start-up (TR-EXT-03).
 *
 * @param cacheMaxEntries {@code horus.bpm.rules.cache.max-entries}, 1 to {@value EngineDefaults#CACHE_MAX_ENTRIES_LIMIT}
 * @param idempotencyRetention {@code horus.bpm.rules.idempotency.retention}, 1 minute to 30 days
 * @param maxInputScale {@code horus.bpm.rules.evaluation.max-input-scale}, 0 to {@value EngineDefaults#INPUT_MAX_SCALE}
 * @param rejectUndeclaredInputs {@code horus.bpm.rules.evaluation.reject-undeclared-inputs}: refuse an input name the
 *     rule set does not declare ({@code INPUT_INVALID}); when false, such inputs are ignored and never logged
 * @param queryMaxPageSize {@code horus.bpm.rules.query.max-page-size}, 1 to {@value EngineDefaults#QUERY_MAX_PAGE_SIZE}:
 *     the largest {@code limit} a list read accepts
 * @param reasonMaxLength {@code horus.bpm.rules.lifecycle.reason-max-length}, 1 to
 *     {@value EngineDefaults#REASON_MAX_LENGTH}: the longest reject reason, in characters
 * @param maxOutputBytes {@code horus.bpm.rules.evaluation.max-output-bytes}, 1 to {@value EngineDefaults#OUTPUT_MAX_BYTES}
 * @param maxStringLength {@code horus.bpm.rules.evaluation.max-string-length}, 1 to {@value EngineDefaults#STRING_MAX_LENGTH}
 * @param maxRows {@code horus.bpm.rules.evaluation.max-rows}, 1 to {@value EngineDefaults#RULES_MAX}
 * @param regexLimits {@code horus.bpm.rules.evaluation.regex-timeout-ms}, {@code regex-max-input-length},
 *     {@code regex-max-reads}, {@code regex-max-reads-per-decision} and {@code regex-max-reads-per-request} (TR-SEC-02); the length is at most
 *     {@code max-string-length}
 * @param draftAuthoring {@code horus.bpm.rules.authoring.cockpit-drafts}: whether drafts may be replaced in place
 *     through {@code VersionService.replaceDraft}; false (the 0.1.0 default, PD-094) refuses it with
 *     {@code DRAFT_AUTHORING_DISABLED}
 * @param pinRetired {@code horus.bpm.rules.evaluation.pin-retired}: whether a pinned evaluation may name a
 *     {@code RETIRED} version; default false, so a retired rule never produces a new logged decision
 * @param systemActor {@code horus.bpm.rules.lifecycle.system-actor}: the actor recorded when a version is superseded, in the reserved
 *     {@code system:} namespace (default {@code system:supersession}),
 *     1 to {@value EngineDefaults#SYSTEM_ACTOR_MAX_LENGTH} characters, not blank
 * @param retireApproval {@code horus.bpm.lifecycle.retire-approval} (PD-080): {@code single} (default) or
 *     {@code four-eyes}, where a retirement is a request a second approver confirms (RLE-038); recorded on every
 *     retirement
 * @param retireLastVersion {@code horus.bpm.lifecycle.retire-last-version} (PD-101, shared with the Process Engine):
 *     {@code confirm} (default) lets a retirement leave no version in effect only when the request confirms it with
 *     {@code confirmNoVersionInEffect}; {@code refuse} never lets it; {@code four-eyes} makes it a request that a
 *     second approver confirms
 * @param simulationMaxInputs {@code horus.bpm.rules.simulation.max-inputs}, 1 to
 *     {@value EngineDefaults#SIMULATION_MAX_SAMPLES}: the most sample input sets one {@code :simulate} accepts
 * @param acknowledgeWarnings {@code horus.bpm.rules.lifecycle.acknowledge-warnings}: the warning codes an approval
 *     must acknowledge (RLE-053, PD-104), at most {@value EngineDefaults#ACKNOWLEDGE_WARNINGS_MAX}, each a code this
 *     release raises ({@code VersionWarning.CODES}); default {@code [UNMASKED_PERSONAL_DATA]}; may be empty
 * @param simulationMaxHistoryDecisions {@code horus.bpm.rules.simulation.max-history-decisions}, 1 to
 *     {@value EngineDefaults#SIMULATION_MAX_HISTORY_DECISIONS_CEILING}, default
 *     {@value EngineDefaults#SIMULATION_MAX_HISTORY_DECISIONS}: the most logged decisions one comparison over a past
 *     period reads; a larger period is refused, never sampled (PD-124, RLE-077)
 * @param maxBatch {@code horus.bpm.rules.evaluation.max-batch}, 1 to {@value EngineDefaults#BATCH_MAX_ITEMS}: the most items
 *     one {@code :evaluate-batch} accepts (RLE-068); may be lowered, never raised
 * @param requireContentHash {@code horus.bpm.rules.approval.require-content-hash}: whether {@code :approve} and
 *     {@code :reject} must name the {@code expectedCanonicalHash} of the content the reviewer saw (RLE-084); default
 *     true, a missing hash is refused with {@code CONTENT_HASH_REQUIRED}; false lets a client leave it out and logs a
 *     relaxation at start-up
 * @param evaluationLimits the step budget and time backstop of one decision (TR-SEC-01); null means the defaults. The
 *     same limits apply at evaluation and to the worst-case check of import and request-publish
 */
public record EngineSettings(int cacheMaxEntries, Duration idempotencyRetention, int maxInputScale,
        boolean rejectUndeclaredInputs, int queryMaxPageSize, int reasonMaxLength, int maxOutputBytes,
        int maxStringLength, int maxRows, RegexLimits regexLimits, boolean draftAuthoring, boolean pinRetired,
        String systemActor, String retireApproval, String retireLastVersion, int simulationMaxInputs,
        List<String> acknowledgeWarnings, EvaluationLimits evaluationLimits, int maxBatch,
        int simulationMaxHistoryDecisions, boolean requireContentHash) {

    /**
     * Creates settings with approval bound to reviewed content required (the default).
     *
     * @param cacheMaxEntries see the record
     * @param idempotencyRetention see the record
     * @param maxInputScale see the record
     * @param rejectUndeclaredInputs see the record
     * @param queryMaxPageSize see the record
     * @param reasonMaxLength see the record
     * @param maxOutputBytes see the record
     * @param maxStringLength see the record
     * @param maxRows see the record
     * @param regexLimits see the record
     * @param draftAuthoring see the record
     * @param pinRetired see the record
     * @param systemActor see the record
     * @param retireApproval see the record
     * @param retireLastVersion see the record
     * @param simulationMaxInputs see the record
     * @param acknowledgeWarnings see the record
     * @param evaluationLimits see the record
     * @param maxBatch see the record
     * @param simulationMaxHistoryDecisions see the record
     */
    public EngineSettings(int cacheMaxEntries, Duration idempotencyRetention, int maxInputScale,
            boolean rejectUndeclaredInputs, int queryMaxPageSize, int reasonMaxLength, int maxOutputBytes,
            int maxStringLength, int maxRows, RegexLimits regexLimits, boolean draftAuthoring, boolean pinRetired,
            String systemActor, String retireApproval, String retireLastVersion, int simulationMaxInputs,
            List<String> acknowledgeWarnings, EvaluationLimits evaluationLimits, int maxBatch,
            int simulationMaxHistoryDecisions) {
        this(cacheMaxEntries, idempotencyRetention, maxInputScale, rejectUndeclaredInputs, queryMaxPageSize,
                reasonMaxLength, maxOutputBytes, maxStringLength, maxRows, regexLimits, draftAuthoring, pinRetired,
                systemActor, retireApproval, retireLastVersion, simulationMaxInputs, acknowledgeWarnings,
                evaluationLimits, maxBatch, simulationMaxHistoryDecisions, EngineDefaults.REQUIRE_CONTENT_HASH);
    }

    /**
     * Creates settings with the default {@code simulation.max-history-decisions}.
     *
     * @param cacheMaxEntries see the record
     * @param idempotencyRetention see the record
     * @param maxInputScale see the record
     * @param rejectUndeclaredInputs see the record
     * @param queryMaxPageSize see the record
     * @param reasonMaxLength see the record
     * @param maxOutputBytes see the record
     * @param maxStringLength see the record
     * @param maxRows see the record
     * @param regexLimits see the record
     * @param draftAuthoring see the record
     * @param pinRetired see the record
     * @param systemActor see the record
     * @param retireApproval see the record
     * @param retireLastVersion see the record
     * @param simulationMaxInputs see the record
     * @param acknowledgeWarnings see the record
     * @param evaluationLimits see the record
     * @param maxBatch see the record
     */
    public EngineSettings(int cacheMaxEntries, Duration idempotencyRetention, int maxInputScale,
            boolean rejectUndeclaredInputs, int queryMaxPageSize, int reasonMaxLength, int maxOutputBytes,
            int maxStringLength, int maxRows, RegexLimits regexLimits, boolean draftAuthoring, boolean pinRetired,
            String systemActor, String retireApproval, String retireLastVersion, int simulationMaxInputs,
            List<String> acknowledgeWarnings, EvaluationLimits evaluationLimits, int maxBatch) {
        this(cacheMaxEntries, idempotencyRetention, maxInputScale, rejectUndeclaredInputs, queryMaxPageSize,
                reasonMaxLength, maxOutputBytes, maxStringLength, maxRows, regexLimits, draftAuthoring, pinRetired,
                systemActor, retireApproval, retireLastVersion, simulationMaxInputs, acknowledgeWarnings,
                evaluationLimits, maxBatch, EngineDefaults.SIMULATION_MAX_HISTORY_DECISIONS);
    }

    public EngineSettings {
        requireRange("horus.bpm.rules.simulation.max-history-decisions", simulationMaxHistoryDecisions, 1,
                EngineDefaults.SIMULATION_MAX_HISTORY_DECISIONS_CEILING);
        requireLowerOnly("horus.bpm.rules.evaluation.max-batch", maxBatch, EngineDefaults.BATCH_MAX_ITEMS);
        if (regexLimits == null) {
            throw new IllegalArgumentException("regexLimits must not be null");
        }
        if (evaluationLimits == null) {
            evaluationLimits = EvaluationLimits.defaults();
        }
        acknowledgeWarnings = warningCodes(acknowledgeWarnings == null ? EngineDefaults.ACKNOWLEDGE_WARNINGS
                : acknowledgeWarnings, "horus.bpm.rules.lifecycle.acknowledge-warnings");
        for (String code : acknowledgeWarnings) {
            if (!io.github.dhluong90.bpm.rules.api.VersionWarning.CODES.contains(code)) {
                throw new IllegalArgumentException("horus.bpm.rules.lifecycle.acknowledge-warnings lists " + code
                        + ", which is not a warning this release raises; known: "
                        + new java.util.TreeSet<>(io.github.dhluong90.bpm.rules.api.VersionWarning.CODES));
            }
        }
        if (cacheMaxEntries < 1 || cacheMaxEntries > EngineDefaults.CACHE_MAX_ENTRIES_LIMIT) {
            throw new IllegalArgumentException("horus.bpm.rules.cache.max-entries must be 1 to "
                    + EngineDefaults.CACHE_MAX_ENTRIES_LIMIT + ", was " + cacheMaxEntries);
        }
        if (idempotencyRetention == null
                || idempotencyRetention.compareTo(EngineDefaults.IDEMPOTENCY_RETENTION_MIN) < 0
                || idempotencyRetention.compareTo(EngineDefaults.IDEMPOTENCY_RETENTION_MAX) > 0) {
            throw new IllegalArgumentException("horus.bpm.rules.idempotency.retention must be PT1M to P30D, was "
                    + idempotencyRetention);
        }
        if (maxInputScale < 0 || maxInputScale > EngineDefaults.INPUT_MAX_SCALE) {
            throw new IllegalArgumentException("horus.bpm.rules.evaluation.max-input-scale must be 0 to "
                    + EngineDefaults.INPUT_MAX_SCALE + " (it may be lowered, never raised), was " + maxInputScale);
        }
        requireLowerOnly("horus.bpm.rules.query.max-page-size", queryMaxPageSize, EngineDefaults.QUERY_MAX_PAGE_SIZE);
        requireLowerOnly("horus.bpm.rules.lifecycle.reason-max-length", reasonMaxLength, EngineDefaults.REASON_MAX_LENGTH);
        requireLowerOnly("horus.bpm.rules.evaluation.max-output-bytes", maxOutputBytes, EngineDefaults.OUTPUT_MAX_BYTES);
        requireLowerOnly("horus.bpm.rules.evaluation.max-string-length", maxStringLength, EngineDefaults.STRING_MAX_LENGTH);
        requireLowerOnly("horus.bpm.rules.evaluation.max-rows", maxRows, EngineDefaults.RULES_MAX);
        requireLowerOnly("horus.bpm.rules.simulation.max-inputs", simulationMaxInputs,
                EngineDefaults.SIMULATION_MAX_SAMPLES);
        if (systemActor == null || !systemActor.startsWith(EngineDefaults.RESERVED_ACTOR_PREFIX)
                || systemActor.length() == EngineDefaults.RESERVED_ACTOR_PREFIX.length()
                || systemActor.length() > EngineDefaults.SYSTEM_ACTOR_MAX_LENGTH) {
            throw new IllegalArgumentException("horus.bpm.rules.lifecycle.system-actor must start with "
                    + EngineDefaults.RESERVED_ACTOR_PREFIX + ", have a name after it and be at most "
                    + EngineDefaults.SYSTEM_ACTOR_MAX_LENGTH + " characters, was '" + systemActor + "'");
        }
        if (!EngineDefaults.RETIRE_LAST_CONFIRM.equals(retireLastVersion)
                && !EngineDefaults.RETIRE_LAST_REFUSE.equals(retireLastVersion)
                && !EngineDefaults.RETIRE_LAST_FOUR_EYES.equals(retireLastVersion)) {
            throw new IllegalArgumentException("horus.bpm.lifecycle.retire-last-version must be confirm, refuse or"
                    + " four-eyes, was '" + retireLastVersion + "'");
        }
        if (!EngineDefaults.RETIRE_APPROVAL.equals(retireApproval)
                && !EngineDefaults.RETIRE_APPROVAL_FOUR_EYES.equals(retireApproval)) {
            throw new IllegalArgumentException("horus.bpm.lifecycle.retire-approval must be single or four-eyes, was '"
                    + retireApproval + "'");
        }
        if (regexLimits.maxInputLength() > maxStringLength) {
            throw new IllegalArgumentException("horus.bpm.rules.evaluation.regex-max-input-length must be at most "
                    + "horus.bpm.rules.evaluation.max-string-length (" + maxStringLength + "), was "
                    + regexLimits.maxInputLength());
        }
    }

    /**
     * Creates settings with the default evaluation limits.
     *
     * @param cacheMaxEntries see the record
     * @param idempotencyRetention see the record
     * @param maxInputScale see the record
     * @param rejectUndeclaredInputs see the record
     * @param queryMaxPageSize see the record
     * @param reasonMaxLength see the record
     * @param maxOutputBytes see the record
     * @param maxStringLength see the record
     * @param maxRows see the record
     * @param regexLimits see the record
     * @param draftAuthoring see the record
     * @param pinRetired see the record
     * @param systemActor see the record
     * @param retireApproval see the record
     * @param retireLastVersion see the record
     * @param simulationMaxInputs see the record
     * @param acknowledgeWarnings see the record
     */
    public EngineSettings(int cacheMaxEntries, Duration idempotencyRetention, int maxInputScale,
            boolean rejectUndeclaredInputs, int queryMaxPageSize, int reasonMaxLength, int maxOutputBytes,
            int maxStringLength, int maxRows, RegexLimits regexLimits, boolean draftAuthoring, boolean pinRetired,
            String systemActor, String retireApproval, String retireLastVersion, int simulationMaxInputs,
            List<String> acknowledgeWarnings) {
        this(cacheMaxEntries, idempotencyRetention, maxInputScale, rejectUndeclaredInputs, queryMaxPageSize,
                reasonMaxLength, maxOutputBytes, maxStringLength, maxRows, regexLimits, draftAuthoring, pinRetired,
                systemActor, retireApproval, retireLastVersion, simulationMaxInputs, acknowledgeWarnings,
                EvaluationLimits.defaults(), EngineDefaults.BATCH_MAX_ITEMS);
    }

    /**
     * Creates settings with the default acknowledged warnings.
     *
     * @param cacheMaxEntries see the record
     * @param idempotencyRetention see the record
     * @param maxInputScale see the record
     * @param rejectUndeclaredInputs see the record
     * @param queryMaxPageSize see the record
     * @param reasonMaxLength see the record
     * @param maxOutputBytes see the record
     * @param maxStringLength see the record
     * @param maxRows see the record
     * @param regexLimits see the record
     * @param draftAuthoring see the record
     * @param pinRetired see the record
     * @param systemActor see the record
     * @param retireApproval see the record
     * @param retireLastVersion see the record
     * @param simulationMaxInputs see the record
     */
    public EngineSettings(int cacheMaxEntries, Duration idempotencyRetention, int maxInputScale,
            boolean rejectUndeclaredInputs, int queryMaxPageSize, int reasonMaxLength, int maxOutputBytes,
            int maxStringLength, int maxRows, RegexLimits regexLimits, boolean draftAuthoring, boolean pinRetired,
            String systemActor, String retireApproval, String retireLastVersion, int simulationMaxInputs) {
        this(cacheMaxEntries, idempotencyRetention, maxInputScale, rejectUndeclaredInputs, queryMaxPageSize,
                reasonMaxLength, maxOutputBytes, maxStringLength, maxRows, regexLimits, draftAuthoring, pinRetired,
                systemActor, retireApproval, retireLastVersion, simulationMaxInputs, EngineDefaults.ACKNOWLEDGE_WARNINGS);
    }

    /**
     * Validates a list of warning codes; a malformed, repeated or surplus code fails naming the property.
     *
     * @param codes the codes
     * @param property the property name for the message
     * @return an immutable copy
     */
    static List<String> warningCodes(List<String> codes, String property) {
        if (codes.size() > EngineDefaults.ACKNOWLEDGE_WARNINGS_MAX) {
            throw new IllegalArgumentException(property + " holds at most " + EngineDefaults.ACKNOWLEDGE_WARNINGS_MAX
                    + " codes, was " + codes.size());
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String code : codes) {
            if (code == null || !WARNING_CODE.matcher(code).matches()) {
                throw new IllegalArgumentException(property + " codes are 1 to 64 upper-case letters, digits or _,"
                        + " starting with a letter, was " + (code == null ? "null" : "'" + code + "'"));
            }
            if (!seen.add(code)) {
                throw new IllegalArgumentException(property + " lists " + code + " twice");
            }
        }
        return List.copyOf(codes);
    }

    private static void requireRange(String property, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(property + " must be " + min + " to " + max + ", was " + value);
        }
    }

    private static final java.util.regex.Pattern WARNING_CODE = java.util.regex.Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    /**
     * Returns a copy with {@code simulation.max-history-decisions} set.
     *
     * @param decisions the most logged decisions one comparison over a past period reads, 1 to 200,000
     * @return the settings
     */
    public EngineSettings withSimulationMaxHistoryDecisions(int decisions) {
        return new EngineSettings(cacheMaxEntries, idempotencyRetention, maxInputScale, rejectUndeclaredInputs,
                queryMaxPageSize, reasonMaxLength, maxOutputBytes, maxStringLength, maxRows, regexLimits, draftAuthoring,
                pinRetired, systemActor, retireApproval, retireLastVersion, simulationMaxInputs,
                acknowledgeWarnings, evaluationLimits, maxBatch, decisions, requireContentHash);
    }

    /**
     * Returns a copy with {@code lifecycle.acknowledge-warnings} set.
     *
     * @param codes the warning codes an approval must acknowledge; may be empty
     * @return the settings
     */
    public EngineSettings withAcknowledgeWarnings(List<String> codes) {
        return new EngineSettings(cacheMaxEntries, idempotencyRetention, maxInputScale, rejectUndeclaredInputs,
                queryMaxPageSize, reasonMaxLength, maxOutputBytes, maxStringLength, maxRows, regexLimits, draftAuthoring,
                pinRetired, systemActor, retireApproval, retireLastVersion, simulationMaxInputs,
                codes, evaluationLimits, maxBatch, simulationMaxHistoryDecisions, requireContentHash);
    }

    /**
     * Creates settings with the default simulation limit.
     *
     * @param cacheMaxEntries see the record
     * @param idempotencyRetention see the record
     * @param maxInputScale see the record
     * @param rejectUndeclaredInputs see the record
     * @param queryMaxPageSize see the record
     * @param reasonMaxLength see the record
     * @param maxOutputBytes see the record
     * @param maxStringLength see the record
     * @param maxRows see the record
     * @param regexLimits see the record
     * @param draftAuthoring see the record
     * @param pinRetired see the record
     * @param systemActor see the record
     * @param retireApproval see the record
     * @param retireLastVersion see the record
     */
    public EngineSettings(int cacheMaxEntries, Duration idempotencyRetention, int maxInputScale,
            boolean rejectUndeclaredInputs, int queryMaxPageSize, int reasonMaxLength, int maxOutputBytes,
            int maxStringLength, int maxRows, RegexLimits regexLimits, boolean draftAuthoring, boolean pinRetired,
            String systemActor, String retireApproval, String retireLastVersion) {
        this(cacheMaxEntries, idempotencyRetention, maxInputScale, rejectUndeclaredInputs, queryMaxPageSize,
                reasonMaxLength, maxOutputBytes, maxStringLength, maxRows, regexLimits, draftAuthoring, pinRetired,
                systemActor, retireApproval, retireLastVersion, EngineDefaults.SIMULATION_MAX_SAMPLES);
    }

    /**
     * Creates settings with the default regular expression bounds.
     *
     * @param cacheMaxEntries see the record
     * @param idempotencyRetention see the record
     * @param maxInputScale see the record
     * @param rejectUndeclaredInputs see the record
     * @param queryMaxPageSize see the record
     * @param reasonMaxLength see the record
     * @param maxOutputBytes see the record
     * @param maxStringLength see the record
     * @param maxRows see the record
     */
    public EngineSettings(int cacheMaxEntries, Duration idempotencyRetention, int maxInputScale,
            boolean rejectUndeclaredInputs, int queryMaxPageSize, int reasonMaxLength, int maxOutputBytes,
            int maxStringLength, int maxRows) {
        this(cacheMaxEntries, idempotencyRetention, maxInputScale, rejectUndeclaredInputs, queryMaxPageSize,
                reasonMaxLength, maxOutputBytes, maxStringLength, maxRows,
                new RegexLimits(RegexLimits.TIMEOUT_MILLIS, Math.min(RegexLimits.MAX_INPUT_LENGTH, maxStringLength)),
                false);
    }

    /**
     * Creates settings with draft authoring off.
     *
     * @param cacheMaxEntries see the record
     * @param idempotencyRetention see the record
     * @param maxInputScale see the record
     * @param rejectUndeclaredInputs see the record
     * @param queryMaxPageSize see the record
     * @param reasonMaxLength see the record
     * @param maxOutputBytes see the record
     * @param maxStringLength see the record
     * @param maxRows see the record
     * @param regexLimits see the record
     */
    public EngineSettings(int cacheMaxEntries, Duration idempotencyRetention, int maxInputScale,
            boolean rejectUndeclaredInputs, int queryMaxPageSize, int reasonMaxLength, int maxOutputBytes,
            int maxStringLength, int maxRows, RegexLimits regexLimits) {
        this(cacheMaxEntries, idempotencyRetention, maxInputScale, rejectUndeclaredInputs, queryMaxPageSize,
                reasonMaxLength, maxOutputBytes, maxStringLength, maxRows, regexLimits, false);
    }

    /**
     * Creates settings that refuse pinning a retired version.
     *
     * @param cacheMaxEntries see the record
     * @param idempotencyRetention see the record
     * @param maxInputScale see the record
     * @param rejectUndeclaredInputs see the record
     * @param queryMaxPageSize see the record
     * @param reasonMaxLength see the record
     * @param maxOutputBytes see the record
     * @param maxStringLength see the record
     * @param maxRows see the record
     * @param regexLimits see the record
     * @param draftAuthoring see the record
     */
    public EngineSettings(int cacheMaxEntries, Duration idempotencyRetention, int maxInputScale,
            boolean rejectUndeclaredInputs, int queryMaxPageSize, int reasonMaxLength, int maxOutputBytes,
            int maxStringLength, int maxRows, RegexLimits regexLimits, boolean draftAuthoring) {
        this(cacheMaxEntries, idempotencyRetention, maxInputScale, rejectUndeclaredInputs, queryMaxPageSize,
                reasonMaxLength, maxOutputBytes, maxStringLength, maxRows, regexLimits, draftAuthoring,
                EngineDefaults.PIN_RETIRED);
    }

    /**
     * Creates settings with the default lifecycle actor, retirement approval and last-version rule.
     *
     * @param cacheMaxEntries see the record
     * @param idempotencyRetention see the record
     * @param maxInputScale see the record
     * @param rejectUndeclaredInputs see the record
     * @param queryMaxPageSize see the record
     * @param reasonMaxLength see the record
     * @param maxOutputBytes see the record
     * @param maxStringLength see the record
     * @param maxRows see the record
     * @param regexLimits see the record
     * @param draftAuthoring see the record
     * @param pinRetired see the record
     */
    public EngineSettings(int cacheMaxEntries, Duration idempotencyRetention, int maxInputScale,
            boolean rejectUndeclaredInputs, int queryMaxPageSize, int reasonMaxLength, int maxOutputBytes,
            int maxStringLength, int maxRows, RegexLimits regexLimits, boolean draftAuthoring, boolean pinRetired) {
        this(cacheMaxEntries, idempotencyRetention, maxInputScale, rejectUndeclaredInputs, queryMaxPageSize,
                reasonMaxLength, maxOutputBytes, maxStringLength, maxRows, regexLimits, draftAuthoring, pinRetired,
                EngineDefaults.SYSTEM_ACTOR, EngineDefaults.RETIRE_APPROVAL, EngineDefaults.RETIRE_LAST_CONFIRM);
    }

    private static void requireLowerOnly(String property, int value, int ceiling) {
        if (value < 1 || value > ceiling) {
            throw new IllegalArgumentException(property + " must be 1 to " + ceiling
                    + " (it may be lowered, never raised), was " + value);
        }
    }

    /**
     * Creates settings with the default page size, reason length and evaluation size limits.
     *
     * @param cacheMaxEntries see the record
     * @param idempotencyRetention see the record
     * @param maxInputScale see the record
     * @param rejectUndeclaredInputs see the record
     */
    public EngineSettings(int cacheMaxEntries, Duration idempotencyRetention, int maxInputScale,
            boolean rejectUndeclaredInputs) {
        this(cacheMaxEntries, idempotencyRetention, maxInputScale, rejectUndeclaredInputs,
                EngineDefaults.QUERY_MAX_PAGE_SIZE, EngineDefaults.REASON_MAX_LENGTH, EngineDefaults.OUTPUT_MAX_BYTES,
                EngineDefaults.STRING_MAX_LENGTH, EngineDefaults.RULES_MAX);
    }

    /**
     * Creates settings with the default evaluation size limits.
     *
     * @param cacheMaxEntries see the record
     * @param idempotencyRetention see the record
     * @param maxInputScale see the record
     * @param rejectUndeclaredInputs see the record
     * @param queryMaxPageSize see the record
     * @param reasonMaxLength see the record
     */
    public EngineSettings(int cacheMaxEntries, Duration idempotencyRetention, int maxInputScale,
            boolean rejectUndeclaredInputs, int queryMaxPageSize, int reasonMaxLength) {
        this(cacheMaxEntries, idempotencyRetention, maxInputScale, rejectUndeclaredInputs, queryMaxPageSize,
                reasonMaxLength, EngineDefaults.OUTPUT_MAX_BYTES, EngineDefaults.STRING_MAX_LENGTH,
                EngineDefaults.RULES_MAX);
    }

    /**
     * Returns the defaults.
     *
     * @return settings with every default of {@link EngineDefaults}
     */
    public static EngineSettings defaults() {
        return new EngineSettings(EngineDefaults.CACHE_MAX_ENTRIES, EngineDefaults.IDEMPOTENCY_RETENTION,
                EngineDefaults.INPUT_MAX_SCALE, EngineDefaults.REJECT_UNDECLARED_INPUTS);
    }

    /**
     * Returns a copy with {@code horus.bpm.rules.approval.require-content-hash} set.
     *
     * @param required whether {@code :approve} and {@code :reject} must name the reviewed content hash
     * @return the settings
     */
    public EngineSettings withRequireContentHash(boolean required) {
        return new EngineSettings(cacheMaxEntries, idempotencyRetention, maxInputScale, rejectUndeclaredInputs,
                queryMaxPageSize, reasonMaxLength, maxOutputBytes, maxStringLength, maxRows, regexLimits, draftAuthoring,
                pinRetired, systemActor, retireApproval, retireLastVersion, simulationMaxInputs,
                acknowledgeWarnings, evaluationLimits, maxBatch, simulationMaxHistoryDecisions, required);
    }

    /**
     * Returns a copy with {@code reject-undeclared-inputs} set.
     *
     * @param reject the new value
     * @return the settings
     */
    public EngineSettings withRejectUndeclaredInputs(boolean reject) {
        return new EngineSettings(cacheMaxEntries, idempotencyRetention, maxInputScale, reject, queryMaxPageSize,
                reasonMaxLength, maxOutputBytes, maxStringLength, maxRows, regexLimits, draftAuthoring, pinRetired,
                systemActor, retireApproval, retireLastVersion, simulationMaxInputs,
                acknowledgeWarnings, evaluationLimits, maxBatch, simulationMaxHistoryDecisions, requireContentHash);
    }

    /**
     * Returns a copy with the evaluation size limits set.
     *
     * @param outputBytes {@code max-output-bytes}
     * @param stringLength {@code max-string-length}
     * @param rows {@code max-rows}
     * @return the settings
     */
    public EngineSettings withSizeLimits(int outputBytes, int stringLength, int rows) {
        return new EngineSettings(cacheMaxEntries, idempotencyRetention, maxInputScale, rejectUndeclaredInputs,
                queryMaxPageSize, reasonMaxLength, outputBytes, stringLength, rows,
                regexLimits.maxInputLength() <= stringLength ? regexLimits
                        : new RegexLimits(regexLimits.timeoutMillis(), stringLength, regexLimits.maxReads(),
                                regexLimits.maxReadsPerDecision(), regexLimits.maxReadsPerRequest()),
                draftAuthoring, pinRetired, systemActor, retireApproval, retireLastVersion, simulationMaxInputs,
                acknowledgeWarnings, evaluationLimits, maxBatch, simulationMaxHistoryDecisions, requireContentHash);
    }

    /**
     * Returns a copy with the regular expression bounds set.
     *
     * @param timeoutMillis {@code evaluation.regex-timeout-ms}
     * @param maxInputLength {@code evaluation.regex-max-input-length}
     * @return the settings
     */
    public EngineSettings withRegexLimits(int timeoutMillis, int maxInputLength) {
        return withRegexLimits(new RegexLimits(timeoutMillis, maxInputLength, regexLimits.maxReads(),
                regexLimits.maxReadsPerDecision(), regexLimits.maxReadsPerRequest()));
    }

    /**
     * Returns a copy with the regular expression bounds set.
     *
     * @param limits the bounds
     * @return the settings
     */
    public EngineSettings withRegexLimits(RegexLimits limits) {
        return new EngineSettings(cacheMaxEntries, idempotencyRetention, maxInputScale, rejectUndeclaredInputs,
                queryMaxPageSize, reasonMaxLength, maxOutputBytes, maxStringLength, maxRows, limits, draftAuthoring,
                pinRetired, systemActor, retireApproval, retireLastVersion, simulationMaxInputs,
                acknowledgeWarnings, evaluationLimits, maxBatch, simulationMaxHistoryDecisions, requireContentHash);
    }

    /**
     * Returns a copy with {@code authoring.cockpit-drafts} set.
     *
     * @param enabled whether drafts may be replaced in place
     * @return the settings
     */
    public EngineSettings withDraftAuthoring(boolean enabled) {
        return new EngineSettings(cacheMaxEntries, idempotencyRetention, maxInputScale, rejectUndeclaredInputs,
                queryMaxPageSize, reasonMaxLength, maxOutputBytes, maxStringLength, maxRows, regexLimits, enabled,
                pinRetired, systemActor, retireApproval, retireLastVersion, simulationMaxInputs,
                acknowledgeWarnings, evaluationLimits, maxBatch, simulationMaxHistoryDecisions, requireContentHash);
    }

    /**
     * Returns a copy with {@code evaluation.pin-retired} set.
     *
     * @param allowed whether a pinned evaluation may name a retired version
     * @return the settings
     */
    public EngineSettings withPinRetired(boolean allowed) {
        return new EngineSettings(cacheMaxEntries, idempotencyRetention, maxInputScale, rejectUndeclaredInputs,
                queryMaxPageSize, reasonMaxLength, maxOutputBytes, maxStringLength, maxRows, regexLimits,
                draftAuthoring, allowed, systemActor, retireApproval, retireLastVersion, simulationMaxInputs,
                acknowledgeWarnings, evaluationLimits, maxBatch, simulationMaxHistoryDecisions, requireContentHash);
    }

    /**
     * Returns a copy with the lifecycle settings set.
     *
     * @param actor {@code lifecycle.system-actor}
     * @param approval {@code lifecycle.retire-approval}
     * @return the settings
     */
    public EngineSettings withLifecycle(String actor, String approval) {
        return new EngineSettings(cacheMaxEntries, idempotencyRetention, maxInputScale, rejectUndeclaredInputs,
                queryMaxPageSize, reasonMaxLength, maxOutputBytes, maxStringLength, maxRows, regexLimits, draftAuthoring,
                pinRetired, actor, approval, retireLastVersion, simulationMaxInputs,
                acknowledgeWarnings, evaluationLimits, maxBatch, simulationMaxHistoryDecisions, requireContentHash);
    }

    /**
     * Returns a copy with {@code horus.bpm.lifecycle.retire-last-version} set.
     *
     * @param mode {@code confirm} or {@code refuse}
     * @return the settings
     */
    public EngineSettings withRetireLastVersion(String mode) {
        return new EngineSettings(cacheMaxEntries, idempotencyRetention, maxInputScale, rejectUndeclaredInputs,
                queryMaxPageSize, reasonMaxLength, maxOutputBytes, maxStringLength, maxRows, regexLimits, draftAuthoring,
                pinRetired, systemActor, retireApproval, mode, simulationMaxInputs,
                acknowledgeWarnings, evaluationLimits, maxBatch, simulationMaxHistoryDecisions, requireContentHash);
    }

    /**
     * Returns a copy with {@code simulation.max-inputs} set.
     *
     * @param maxInputs the most sample input sets of one simulation
     * @return the settings
     */
    public EngineSettings withSimulationMaxInputs(int maxInputs) {
        return new EngineSettings(cacheMaxEntries, idempotencyRetention, maxInputScale, rejectUndeclaredInputs,
                queryMaxPageSize, reasonMaxLength, maxOutputBytes, maxStringLength, maxRows, regexLimits, draftAuthoring,
                pinRetired, systemActor, retireApproval, retireLastVersion, maxInputs,
                acknowledgeWarnings, evaluationLimits, maxBatch, simulationMaxHistoryDecisions, requireContentHash);
    }

    /**
     * Returns a copy with {@code evaluation.max-batch} set.
     *
     * @param items the most items of one batch evaluation, 1 to {@value EngineDefaults#BATCH_MAX_ITEMS}
     * @return the settings
     */
    public EngineSettings withMaxBatch(int items) {
        return new EngineSettings(cacheMaxEntries, idempotencyRetention, maxInputScale, rejectUndeclaredInputs,
                queryMaxPageSize, reasonMaxLength, maxOutputBytes, maxStringLength, maxRows, regexLimits, draftAuthoring,
                pinRetired, systemActor, retireApproval, retireLastVersion, simulationMaxInputs,
                acknowledgeWarnings, evaluationLimits, items, simulationMaxHistoryDecisions, requireContentHash);
    }

    /**
     * Returns a copy with the step budget and time backstop of one decision set.
     *
     * @param limits the limits
     * @return the settings
     */
    public EngineSettings withEvaluationLimits(EvaluationLimits limits) {
        return new EngineSettings(cacheMaxEntries, idempotencyRetention, maxInputScale, rejectUndeclaredInputs,
                queryMaxPageSize, reasonMaxLength, maxOutputBytes, maxStringLength, maxRows, regexLimits, draftAuthoring,
                pinRetired, systemActor, retireApproval, retireLastVersion, simulationMaxInputs, acknowledgeWarnings,
                limits, maxBatch, simulationMaxHistoryDecisions, requireContentHash);
    }
}
