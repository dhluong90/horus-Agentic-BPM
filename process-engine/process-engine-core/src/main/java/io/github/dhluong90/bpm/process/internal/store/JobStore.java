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
    /** Job kind of a remote service step attempt (Async Wait-State, ADR-0008). */
    String ASYNC_SERVICE = "ASYNC_SERVICE";
    /** Job kind of an agent step attempt (Async Wait-State, ADR-0008). */
    String ASYNC_AGENT = "ASYNC_AGENT";

    /**
     * @param jobKind a job kind
     * @return whether jobs of that kind call outside the JVM and are run by the async executor
     */
    static boolean isAsync(String jobKind) {
        return ASYNC_SERVICE.equals(jobKind) || ASYNC_AGENT.equals(jobKind);
    }

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
     * @param job a new {@code DUE} job, or a {@code FAILED} one for an agent step whose memory failed before any call
     *     (#101): it is never claimed, and its incident is opened in the same transaction
     * @param now creation time
     */
    void insertJob(JobRow job, Instant now);

    /**
     * Claims due jobs of every tenant except async step jobs: rows {@code DUE} with {@code next_fire_at <= now}, and rows {@code CLAIMED}
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
     * Claims due async step jobs ({@link #isAsync}) of every tenant, the same way as {@link #claimDue}; that method
     * never returns them. The caller passes as limit the adapter slots it has free, so a claimed job never waits in
     * the JVM while its lease runs (ADR-0008).
     *
     * @param replicaId the claiming replica
     * @param now the claim time
     * @param limit at most this many rows
     * @return the claimed rows, oldest due first
     */
    List<JobRow> claimDueAsync(String replicaId, Instant now, int limit);

    /**
     * Claims due async step jobs of one kind, the same way as {@link #claimDueAsync(String, Instant, int)}. The async
     * executor claims each kind separately, up to the free slots of that kind's runner, so a backlog of one kind
     * never takes the claims or the slots of another (#363).
     *
     * @param replicaId the claiming replica
     * @param now the claim time
     * @param jobKind an async kind, {@link #ASYNC_SERVICE} or {@link #ASYNC_AGENT}
     * @param limit at most this many rows
     * @return the claimed rows, oldest due first
     * @throws IllegalArgumentException when the kind is not an async kind
     */
    List<JobRow> claimDueAsync(String replicaId, Instant now, String jobKind, int limit);

    /**
     * Locks a job row ({@code FOR UPDATE}) whatever its status, for the result transaction of an async step, which
     * decides from the status, attempt and lease whether the result is current.
     *
     * @param tenant tenant
     * @param id job id
     * @return the job in the tenant
     */
    Optional<JobRow> lockJob(String tenant, String id);

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

    /** Payload key that counts the schema retries a job has had ({@link #retrySchema}). */
    String SCHEMA_RETRIES = "schemaRetries";

    /**
     * Gives a held claim back after a result that broke its output schema (#363): {@code DUE} again at
     * {@code retryAt}, one more allowed attempt, so a schema retry does not use up an attempt meant for technical
     * failures, and the payload's {@value #SCHEMA_RETRIES} counter one higher.
     *
     * @param tenant tenant
     * @param id job id
     * @param replicaId the holding replica
     * @param attempt the attempt of that claim
     * @param retryAt next due time
     * @return true when the claim was still held
     */
    boolean retrySchema(String tenant, String id, String replicaId, int attempt, Instant retryAt);

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
     * Stores the SHA-256 of the completion token issued to the current claim of an async job, in the claim
     * transaction (ADR-0008). Only the hash is stored; the token itself goes to the adapter and nowhere else.
     *
     * @param tenant tenant
     * @param id job id
     * @param attempt the attempt of that claim
     * @param tokenHash SHA-256 of the token, 32 bytes
     * @return true when the job is still claimed under that attempt
     */
    boolean issueCompletionToken(String tenant, String id, int attempt, byte[] tokenHash);

    /**
     * @param tenant tenant
     * @param id job id
     * @return the hash of the completion token of the job's latest claim, or empty when none was issued
     */
    Optional<byte[]> completionTokenHash(String tenant, String id);

    /**
     * Marks that a late result for a cancelled job has been recorded, once per job, so repeated late callbacks write
     * one history row in total.
     *
     * @param tenant tenant
     * @param id job id
     * @param now current time
     * @return true for the first late result of the job; false when one was already recorded
     */
    boolean markLateResult(String tenant, String id, Instant now);

    /**
     * @param tenant tenant
     * @param id job id
     * @return the job in the tenant
     */
    Optional<JobRow> findJob(String tenant, String id);

    /**
     * Cancels the open {@code DEFINITION_EFFECTIVE} jobs of a definition version (PRE-053).
     *
     * @param tenant tenant
     * @param definitionId the version
     * @param now current time
     * @return jobs cancelled
     */
    int cancelDefinitionJobs(String tenant, String definitionId, Instant now);

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
        public List<JobRow> claimDueAsync(String replicaId, Instant now, int limit) {
            return List.of();
        }

        @Override
        public List<JobRow> claimDueAsync(String replicaId, Instant now, String jobKind, int limit) {
            return List.of();
        }

        @Override
        public Optional<JobRow> lockJob(String tenant, String id) {
            return Optional.empty();
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
        public boolean retrySchema(String tenant, String id, String replicaId, int attempt, Instant retryAt) {
            return false;
        }

        @Override
        public int cancelJob(String tenant, String id, Instant now) {
            return 0;
        }

        @Override
        public boolean issueCompletionToken(String tenant, String id, int attempt, byte[] tokenHash) {
            return false;
        }

        @Override
        public Optional<byte[]> completionTokenHash(String tenant, String id) {
            return Optional.empty();
        }

        @Override
        public boolean markLateResult(String tenant, String id, Instant now) {
            return false;
        }

        @Override
        public Optional<JobRow> findJob(String tenant, String id) {
            return Optional.empty();
        }

        @Override
        public int cancelDefinitionJobs(String tenant, String definitionId, Instant now) {
            return 0;
        }
    };
}
