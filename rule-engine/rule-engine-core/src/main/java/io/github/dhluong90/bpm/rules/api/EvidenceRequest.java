package io.github.dhluong90.bpm.rules.api;

import java.util.List;
import java.util.Objects;

/**
 * What an evidence export holds (PD-116).
 *
 * @param format the file format
 * @param versions version numbers of the rule set, at most 100, no repeats; each contributes its history events
 * @param decisionIds logged decisions of the rule set, at most 100, no repeats, in the order the file lists them
 * @param idempotencyKey the idempotency key, or null
 * @param correlationId the caller's correlation id for the event, or null
 */
public record EvidenceRequest(EvidenceFormat format, List<Integer> versions, List<String> decisionIds,
        String idempotencyKey, String correlationId) {

    /** Most versions one export may name. */
    public static final int MAX_VERSIONS = 100;

    /** Most decisions one export may name; a larger set is found with the decision search API. */
    public static final int MAX_DECISIONS = 100;

    public EvidenceRequest {
        Objects.requireNonNull(format, "format must not be null");
        versions = List.copyOf(versions == null ? List.of() : versions);
        decisionIds = List.copyOf(decisionIds == null ? List.of() : decisionIds);
    }
}
