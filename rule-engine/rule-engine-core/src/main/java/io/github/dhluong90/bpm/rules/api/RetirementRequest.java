package io.github.dhluong90.bpm.rules.api;

import java.time.Instant;
import java.util.Objects;

/**
 * A pending retirement of a published version, waiting for a second approver (PD-080, RLE-038). It exists in the
 * {@code four-eyes} mode of {@code horus.bpm.lifecycle.retire-approval}, or for the last version in effect with
 * {@code horus.bpm.lifecycle.retire-last-version: four-eyes}. The version keeps serving until a holder of
 * {@code rules:approve} other than the requester confirms it; evaluation never reads the request.
 *
 * @param requestedBy who asked for the retirement; never the one who confirms it
 * @param requestedAt when
 * @param reason the reason given with the request, recorded again on the retirement
 * @param confirmNoVersionInEffect whether the requester accepted that no version may remain in effect (PD-101), so a
 *     confirmation of the last version in effect needs no confirmation of its own
 */
public record RetirementRequest(String requestedBy, Instant requestedAt, String reason,
        boolean confirmNoVersionInEffect) {

    public RetirementRequest {
        Objects.requireNonNull(requestedBy, "requestedBy must not be null");
        Objects.requireNonNull(requestedAt, "requestedAt must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
    }
}
