package io.github.dhluong90.bpm.process.internal.store;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Storage port of the jobs in {@code pre_timer} (DESIGN sections 3.1, 4 and 7). Methods join the caller's transaction;
 * none opens its own. Every method except {@link #claimDue} filters by the tenant (TR-TEN-01); {@code claimDue} is the
 * engine sweep, which crosses tenants and never returns rows to a caller. Implementations must be thread-safe.
 */
public interface JobStore {

    /** Job kinds, as stored in {@code pre_timer.job_kind}. */
    String TIMER = "TIMER";
    /** Job kind of a definition version that becomes effective at a later date. */
    String DEFINITION_EFFECTIVE = "DEFINITION_EFFECTIVE";

    /**
     * One job row.
     *
     * @param tenantId tenant
     * @param id job id (ULID)
     * @param instanceId instance, or null for a definition job
     * @param definitionId definition version
     * @param tokenId the waiting token, or null for a definition job
     * @param jobKind kind, for example {@link #TIMER}
     * @param nodeId node, or null
     * @param status {@code DUE}, {@code CLAIMED}, {@code DONE}, {@code FAILED} or {@code CANCELLED}
     * @param nextFireAt when the job is due
     * @param leaseDuration how long a claim holds the job
     * @param lockedBy the replica holding the claim, or null
     * @param lockedUntil end of the lease, or null
     * @param attempt claims so far; a claim increments it, so it fences a replica whose lease was taken over
     * @param maxAttempts claims after which the job is failed instead of fired
     * @param payloadJson job payload
     */
    record JobRow(String tenantId, String id, String instanceId, String definitionId, String tokenId, String jobKind,
            String nodeId, String status, Instant nextFireAt, Duration leaseDuration, String lockedBy,
            Instant lockedUntil, int attempt, int maxAttempts, String payloadJson) {

        public JobRow {
            Objects.requireNonNull(tenantId, "tenantId must not be null");
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(jobKind, "jobKind must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(nextFireAt, "nextFireAt must not be null");
            Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
            payloadJson = payloadJson == null ? "{}" : payloadJson;
        }

        /**
         * A new job, {@code DUE} at the given time.
         *
         * @param tenantId tenant
         * @param id job id
         * @param instanceId instance or null
         * @param definitionId definition version
         * @param tokenId token or null
         * @param jobKind kind
         * @param nodeId node or null
         * @param dueAt due time
         * @param lease lease of a claim
         * @param maxAttempts claims after which the job fails
         * @param payloadJson payload
         * @return the row
         */
        public static JobRow due(String tenantId, String id, String instanceId, String definitionId, String tokenId,
                String jobKind, String nodeId, Instant dueAt, Duration lease, int maxAttempts, String payloadJson) {
            return new JobRow(tenantId, id, instanceId, definitionId, tokenId, jobKind, nodeId, "DUE", dueAt, lease, null,
                    null, 0, maxAttempts, payloadJson);
        }
    }

    /**
     * @param job a new {@code DUE} job
     * @param now creation time
     */
    void insertJob(JobRow job, Instant now);

    /**
     * Claims due jobs of every tenant: rows {@code DUE} with {@code next_fire_at <= now}, and rows {@code CLAIMED}
     * whose lease has expired. Rows locked by a concurrent claim are skipped ({@code FOR UPDATE SKIP LOCKED}), never
     * waited for. Each claimed row gets {@code locked_by = replicaId}, {@code locked_until = now + lease_duration} and
     * {@code attempt + 1}. Run in its own short transaction, committed before any job fires.
     *
     * @param replicaId the claiming replica
     * @param now the claim time
     * @param limit at most this many rows
     * @return the claimed rows, oldest due first
     */
    List<JobRow> claimDue(String replicaId, Instant now, int limit);

    /**
     * Locks a claimed job ({@code FOR UPDATE}) and returns it only while this replica still holds the claim: status
     * {@code CLAIMED}, the same {@code locked_by} and {@code attempt}, and a lease that has not expired. A replica whose
     * lease was taken over, or a job cancelled meanwhile, gets empty and must not fire.
     *
     * @param tenant tenant
     * @param id job id
     * @param replicaId the replica that claimed it
     * @param attempt the attempt of that claim
     * @param now current time
     * @return the job while the claim holds
     */
    Optional<JobRow> lockClaim(String tenant, String id, String replicaId, int attempt, Instant now);

    /**
     * Marks a held claim {@code DONE}, in the transaction that fired it.
     *
     * @param tenant tenant
     * @param id job id
     * @param replicaId the holding replica
     * @param attempt the attempt of that claim
     * @param now completion time
     * @return true when the claim was still held and the row is now {@code DONE}
     */
    boolean markDone(String tenant, String id, String replicaId, int attempt, Instant now);

    /**
     * Gives a held claim back after a failed firing: {@code DUE} again at {@code retryAt}, or {@code FAILED} when
     * {@code failed} is set.
     *
     * @param tenant tenant
     * @param id job id
     * @param replicaId the holding replica
     * @param attempt the attempt of that claim
     * @param retryAt next due time
     * @param failed whether the job has used its attempts
     * @param now current time
     * @return true when the claim was still held
     */
    boolean release(String tenant, String id, String replicaId, int attempt, Instant retryAt, boolean failed,
            Instant now);

    /**
     * Cancels an open job ({@code DUE} or {@code CLAIMED}); a fire transaction that holds the claim then finds it
     * gone and does not fire.
     *
     * @param tenant tenant
     * @param id job id
     * @param now current time
     * @return rows changed: 1, or 0 when the job is not open in the tenant
     */
    int cancelJob(String tenant, String id, Instant now);

    /**
     * @param tenant tenant
     * @param id job id
     * @return the job in the tenant
     */
    Optional<JobRow> findJob(String tenant, String id);

    /** A store without jobs: refuses every timer. For hosts and tests that build the services without one. */
    JobStore NONE = new JobStore() {
        @Override
        public void insertJob(JobRow job, Instant now) {
            throw new UnsupportedOperationException("This engine was built without a job store; timers need one");
        }

        @Override
        public List<JobRow> claimDue(String replicaId, Instant now, int limit) {
            return List.of();
        }

        @Override
        public Optional<JobRow> lockClaim(String tenant, String id, String replicaId, int attempt, Instant now) {
            return Optional.empty();
        }

        @Override
        public boolean markDone(String tenant, String id, String replicaId, int attempt, Instant now) {
            return false;
        }

        @Override
        public boolean release(String tenant, String id, String replicaId, int attempt, Instant retryAt, boolean failed,
                Instant now) {
            return false;
        }

        @Override
        public int cancelJob(String tenant, String id, Instant now) {
            return 0;
        }

        @Override
        public Optional<JobRow> findJob(String tenant, String id) {
            return Optional.empty();
        }
    };
}
