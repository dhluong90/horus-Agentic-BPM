package io.github.dhluong90.bpm.process.api;

import java.time.Instant;
import java.util.Objects;

/**
 * What the runner did with one instance of a migration (#90). Recorded once per instance and never changed.
 *
 * @param instanceId the instance
 * @param outcome {@code MIGRATED}, {@code FAILED} or {@code SKIPPED}
 * @param code {@code MOVED}; for a failure {@code STEP_NOT_IN_TARGET}, {@code STEP_TYPE_CHANGED} or
 *     {@code LOCK_CONFLICT}; for a skip {@code NOT_RUNNING}, {@code NOT_ON_SOURCE} or {@code INSTANCE_GONE}
 * @param detail built from codes and step ids only, at most 500 characters
 * @param incidentId the incident opened for a failure, otherwise null
 * @param occurredAt when
 */
public record MigrationOutcome(String instanceId, String outcome, String code, String detail, String incidentId,
        Instant occurredAt) {

    public MigrationOutcome {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
