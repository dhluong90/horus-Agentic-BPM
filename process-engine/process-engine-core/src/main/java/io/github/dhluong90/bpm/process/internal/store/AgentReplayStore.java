package io.github.dhluong90.bpm.process.internal.store;

import io.github.dhluong90.bpm.process.api.AgentReplayCase;
import io.github.dhluong90.bpm.process.api.AgentReplayRun;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Storage of offline replay runs ({@code pre_agent_replay}, {@code pre_agent_replay_case}) and the reads of recorded
 * agent history they replay (PRE-092). Every method takes the tenant, which leads every key and index, except the
 * worker's claim of the next run and the retention purge, which are engine work across tenants. Reads of history and
 * jobs only read: nothing here writes {@code pre_history}, {@code pre_timer} or any instance table.
 */
public interface AgentReplayStore {

    /**
     * One recorded answer of an agent step: an {@code ASYNC_RESULT_ACCEPTED} or {@code ASYNC_RESULT_REFUSED} history
     * row and the payload of its job.
     *
     * @param occurredAt when the answer was checked (the history row's time)
     * @param historyId the history row id, the second part of the keyset
     * @param instanceId the instance
     * @param eventType the history event type
     * @param attemptId the attempt
     * @param details the history row's details
     * @param jobPayload the job's payload with its guard spec, or null when the job row is gone
     */
    record RecordedAnswer(Instant occurredAt, String historyId, String instanceId, String eventType, String attemptId,
            Map<String, Object> details, Map<String, Object> jobPayload) {
    }

    /**
     * A stored run.
     *
     * @param tenantId the tenant
     * @param view the run as the API shows it
     * @param candidateSchema the candidate output schema, or null
     * @param idempotencyKey the start request's key, or null
     * @param requestHash the start request's hash, or null without a key
     * @param cursorAt the keyset position: time of the last replayed answer, or null before the first page
     * @param cursorId the keyset position: id of the last replayed history row, or null
     */
    record StoredRun(String tenantId, AgentReplayRun view, Map<String, Object> candidateSchema, String idempotencyKey,
            String requestHash, Instant cursorAt, String cursorId) {
    }

    /**
     * A report line with its position.
     *
     * @param seq the position in the report, from 0
     * @param line the line
     */
    record CaseRow(int seq, AgentReplayCase line) {
    }

    /** @return recorded answers of the step in {@code [from, to)} */
    long countAnswers(String tenant, String definitionKey, String nodeId, Instant from, Instant to);

    /** @return the output schema of the latest recorded answer of the step in {@code [from, to)} */
    Optional<Map<String, Object>> latestRecordedSchema(String tenant, String definitionKey, String nodeId, Instant from,
            Instant to);

    /** @return up to {@code limit} recorded answers after the keyset position, in {@code (occurredAt, id)} order */
    List<RecordedAnswer> answers(String tenant, String definitionKey, String nodeId, Instant from, Instant to,
            Instant afterAt, String afterId, int limit);

    /**
     * @return the attempt ids among {@code attemptIds} whose answer a reviewer changed ({@code HUMAN_OVERRIDE}) in a
     *     field whose recorded value was kept; a change from a masked value cannot be compared and does not count
     */
    Set<String> overriddenAttempts(String tenant, Collection<String> instanceIds, Collection<String> attemptIds);

    /** @return the history details of one recorded answer */
    Optional<Map<String, Object>> recordedDetails(String tenant, String instanceId, String attemptId);

    void insertRun(StoredRun run);

    Optional<StoredRun> findRun(String tenant, String runId);

    Optional<StoredRun> findRunByIdempotencyKey(String tenant, String idempotencyKey);

    /** @return the oldest queued or running run of any tenant, locked for this transaction, skipping locked ones */
    Optional<StoredRun> lockNextRun();

    void insertCases(String tenant, String runId, int firstSeq, List<AgentReplayCase> lines);

    void advance(String tenant, String runId, String status, Instant cursorAt, String cursorId,
            AgentReplayRun.Summary summary, Instant completedAt);

    void fail(String tenant, String runId, String failureCode, Instant at);

    List<CaseRow> cases(String tenant, String runId, int afterSeq, int limit);

    Optional<AgentReplayCase> findCase(String tenant, String runId, String attemptId);

    /** @return runs deleted, with their cases: those whose {@code expires_at} is at or before {@code now} */
    int deleteExpired(Instant now, int limit);
}
