package io.github.dhluong90.bpm.rules.api;

import java.util.List;

/**
 * Optional fields of a lifecycle command.
 *
 * @param idempotencyKey optional key; a replay returns the original result
 * @param expectedLockVersion optional optimistic lock ({@code If-Match}); a stale value is {@code VERSION_CONFLICT}
 * @param comment the comment stored with an approval (optional), the reason of a reject (required) or of a
 *     withdrawal (optional)
 * @param correlationId optional correlation id carried by the event the command writes; not part of the idempotent
 *     request, so a replay with another correlation id still returns the original result
 * @param expectedCanonicalHash on {@code approve} and {@code reject}: the {@code canonicalHash} of the version the
 *     caller reviewed; optional (null: not checked). When it differs from the stored version's hash the command is
 *     {@code VERSION_CONFLICT}, even with the current lock version (RLE-084, #275)
 * @param acknowledgedWarnings on {@code approve} only: the warnings the approver was shown and accepts, as
 *     {@code { code, input }} pairs, for example {@code UNMASKED_PERSONAL_DATA} on {@code nationalId} (RLE-053, PD-104);
 *     empty when none, never null. The approval is accepted only when this set equals the set the engine recomputes
 * @param simulationReportId on {@code request-publish} only: the id of a simulation report of this version to attach to
 *     the request (PD-115, RLE-201); optional, but required for a rule set the host lists in
 *     {@code lifecycle.require-simulation-report}
 */
public record WriteOptions(String idempotencyKey, Long expectedLockVersion, String comment, String correlationId,
        String expectedCanonicalHash, List<AcknowledgedWarning> acknowledgedWarnings,
        String simulationReportId) {

    private static final WriteOptions NONE = new WriteOptions(null, null, null);

    public WriteOptions {
        acknowledgedWarnings = acknowledgedWarnings == null ? List.of() : List.copyOf(acknowledgedWarnings);
    }

    /**
     * Creates options without a simulation report.
     *
     * @param idempotencyKey see the record
     * @param expectedLockVersion see the record
     * @param comment see the record
     * @param correlationId see the record
     * @param expectedCanonicalHash see the record
     * @param acknowledgedWarnings see the record
     */
    public WriteOptions(String idempotencyKey, Long expectedLockVersion, String comment, String correlationId,
            String expectedCanonicalHash, List<AcknowledgedWarning> acknowledgedWarnings) {
        this(idempotencyKey, expectedLockVersion, comment, correlationId, expectedCanonicalHash, acknowledgedWarnings,
                null);
    }

    /**
     * Creates options without an expected content hash.
     *
     * @param idempotencyKey see the record
     * @param expectedLockVersion see the record
     * @param comment see the record
     * @param correlationId see the record
     * @param acknowledgedWarnings see the record
     */
    public WriteOptions(String idempotencyKey, Long expectedLockVersion, String comment, String correlationId,
            List<AcknowledgedWarning> acknowledgedWarnings) {
        this(idempotencyKey, expectedLockVersion, comment, correlationId, null, acknowledgedWarnings);
    }

    /**
     * Creates options without acknowledged warnings.
     *
     * @param idempotencyKey see the record
     * @param expectedLockVersion see the record
     * @param comment see the record
     * @param correlationId see the record
     */
    public WriteOptions(String idempotencyKey, Long expectedLockVersion, String comment, String correlationId) {
        this(idempotencyKey, expectedLockVersion, comment, correlationId, null, List.of());
    }

    /**
     * Creates options without correlation id or acknowledged warnings.
     *
     * @param idempotencyKey see the record
     * @param expectedLockVersion see the record
     * @param comment see the record
     */
    public WriteOptions(String idempotencyKey, Long expectedLockVersion, String comment) {
        this(idempotencyKey, expectedLockVersion, comment, (String) null);
    }

    /**
     * Returns options with no field set.
     *
     * @return empty options
     */
    public static WriteOptions none() {
        return NONE;
    }

    /**
     * Returns options with only an idempotency key.
     *
     * @param key the key
     * @return the options
     */
    public static WriteOptions idempotencyKey(String key) {
        return new WriteOptions(key, null, null);
    }

    /**
     * Returns a copy with a correlation id for the event the command writes.
     *
     * @param id the correlation id, 1 to 128 characters of {@code A-Z a-z 0-9 . _ : -}
     * @return the options
     */
    public WriteOptions withCorrelationId(String id) {
        return new WriteOptions(idempotencyKey, expectedLockVersion, comment, id, expectedCanonicalHash,
                acknowledgedWarnings, simulationReportId);
    }

    /**
     * Returns a copy that acknowledges these warnings.
     *
     * @param warnings the pairs the approver was shown
     * @return the options
     */
    public WriteOptions acknowledging(List<AcknowledgedWarning> warnings) {
        return new WriteOptions(idempotencyKey, expectedLockVersion, comment, correlationId, expectedCanonicalHash,
                warnings, simulationReportId);
    }

    /**
     * Returns a copy that approves or rejects only the content with this hash (RLE-084).
     *
     * @param canonicalHash the {@code canonicalHash} of the version the caller reviewed
     * @return the options
     */
    public WriteOptions expectingContent(String canonicalHash) {
        return new WriteOptions(idempotencyKey, expectedLockVersion, comment, correlationId, canonicalHash,
                acknowledgedWarnings, simulationReportId);
    }

    /**
     * Returns a copy that attaches a simulation report to a publication request (PD-115, RLE-201).
     *
     * @param reportId the id of a report run on this version as it is now
     * @return the options
     */
    public WriteOptions withSimulationReport(String reportId) {
        return new WriteOptions(idempotencyKey, expectedLockVersion, comment, correlationId, expectedCanonicalHash,
                acknowledgedWarnings, reportId);
    }
}
