package io.github.dhluong90.bpm.process.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * A resolution proposed for an incident (#68, PD-126): the output the waiting step completes with, the reason and the
 * proposer. A second holder of {@code processes:incident:manage}, never the proposer, approves or rejects it; only
 * the approval moves the token.
 *
 * @param proposedBy who proposed it
 * @param proposedAt when
 * @param reason why, at most {@code lifecycle.reason-max-length} characters
 * @param output the output, as history keeps it: masked by the step's output schema where the schema marks a field
 *     {@code mask: true}
 * @param approvalMode {@code four-eyes}, or {@code single} on the {@code dev} profile only
 */
public record PendingResolution(String proposedBy, Instant proposedAt, String reason, Map<String, Object> output,
        String approvalMode) {

    public PendingResolution {
        Objects.requireNonNull(proposedBy, "proposedBy must not be null");
        Objects.requireNonNull(proposedAt, "proposedAt must not be null");
        output = output == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(output));
    }
}
