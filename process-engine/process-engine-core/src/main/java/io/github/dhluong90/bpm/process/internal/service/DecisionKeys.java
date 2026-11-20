package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.internal.json.Hashes;
import java.util.Objects;

/**
 * The idempotency key of a decision step (ADR-0027). Attempt ids are deterministic children of the instance id and
 * its sequence, so a step retried from the same state gets the same key and the decision engine replays the logged
 * decision instead of writing a second row.
 */
public final class DecisionKeys {

    /** Longest key a decision engine accepts. */
    static final int MAX_LENGTH = 200;

    private DecisionKeys() {
    }

    /**
     * @param instanceId the instance id
     * @param nodeId the step's node id
     * @param attemptId the step's attempt id
     * @return {@code pe:<instance>:<node>:<attempt>}; the node id is replaced by its SHA-256 when the key would be
     *     longer than {@value #MAX_LENGTH} characters
     */
    public static String forStep(String instanceId, String nodeId, String attemptId) {
        Objects.requireNonNull(instanceId, "instanceId must not be null");
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        String key = "pe:" + instanceId + ":" + nodeId + ":" + attemptId;
        return key.length() <= MAX_LENGTH ? key : "pe:" + instanceId + ":" + Hashes.sha256(nodeId) + ":" + attemptId;
    }
}
