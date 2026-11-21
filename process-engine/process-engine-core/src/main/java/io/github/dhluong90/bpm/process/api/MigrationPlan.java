package io.github.dhluong90.bpm.process.api;

import java.time.Instant;
import java.util.Objects;

/**
 * A stored migration plan (#74, PRE-174). Creating it moves nothing. It records the approval its execution must
 * meet, fixed when it is created: {@link Approval#FOUR_EYES} unless the host runs the {@code dev} profile alone with
 * {@code migration.approval=single} (PD-114 as amended by PD-131).
 *
 * @param id the plan id (ULID)
 * @param definitionKey the definition key
 * @param sourceVersion the version the instances run on
 * @param targetVersion the version they would move to
 * @param status {@code PLANNED}; execution states follow with the execution feature
 * @param planHash SHA-256 over the key and the definition ids and hashes of both versions; a dry run reports it, so an
 *     execution can be bound to the plan that was checked
 * @param approval the approval an execution of this plan needs
 * @param createdBy who created the plan
 * @param createdAt when
 */
public record MigrationPlan(String id, String definitionKey, int sourceVersion, int targetVersion, String status,
        String planHash, Approval approval, String createdBy, Instant createdAt) {

    public MigrationPlan {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(definitionKey, "definitionKey");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(planHash, "planHash");
        Objects.requireNonNull(approval, "approval");
    }

    /** The approval an execution needs. */
    public enum Approval {
        /** A second holder of {@code processes:migration:manage}, never the proposer, approves (the default). */
        FOUR_EYES("four-eyes"),
        /** One holder executes alone; accepted only under the {@code dev} profile. */
        SINGLE("single");

        private final String value;

        Approval(String value) {
            this.value = value;
        }

        /** @return the value as configured and as the API writes it */
        public String value() {
            return value;
        }

        /** @return true when an execution needs a second person */
        public boolean needsSecondPerson() {
            return this == FOUR_EYES;
        }

        /**
         * @param value {@code four-eyes} or {@code single}
         * @return the approval
         */
        public static Approval of(String value) {
            for (Approval a : values()) {
                if (a.value.equals(value)) {
                    return a;
                }
            }
            throw new IllegalArgumentException("migration.approval must be four-eyes or single, was " + value);
        }
    }
}
