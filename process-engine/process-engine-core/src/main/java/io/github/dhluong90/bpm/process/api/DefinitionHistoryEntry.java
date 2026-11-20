package io.github.dhluong90.bpm.process.api;

import java.time.Instant;
import java.util.Objects;

/**
 * One lifecycle transition of a definition version, from the append-only {@code pre_definition_history}.
 *
 * @param action {@code IMPORTED}, {@code REQUESTED}, {@code APPROVED}, {@code REJECTED} or {@code RETIRED}
 * @param fromStatus the status before, or null for the import
 * @param toStatus the status after
 * @param actor who made the change; {@code system:effective-date} for a retirement by the effective-date job
 * @param comment the approval comment or rejection reason, or null
 * @param occurredAt when
 */
public record DefinitionHistoryEntry(String action, String fromStatus, String toStatus, String actor, String comment,
        Instant occurredAt) {

    public DefinitionHistoryEntry {
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(toStatus, "toStatus must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
