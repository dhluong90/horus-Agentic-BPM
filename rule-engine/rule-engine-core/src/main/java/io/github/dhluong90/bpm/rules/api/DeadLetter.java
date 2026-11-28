package io.github.dhluong90.bpm.rules.api;

import java.time.Instant;
import java.util.Objects;

/**
 * A decision the asynchronous decision log could not write after its attempts (RLE-231). The row is kept whole in the
 * queue, so the decision can still be read and replayed by its id.
 *
 * @param decisionId the decision id
 * @param ruleSet the rule set it was made with
 * @param version the version it was made with
 * @param queuedAt when it was queued
 * @param attempts the attempts the relay made
 * @param lastError why the last attempt failed: the database's first message line, without a value
 * @param state where it stands
 * @param requestedBy the operator who asked for a retry or a resolve, or null in state {@code DEAD}
 * @param requestComment the reason that operator gave, or null in state {@code DEAD}
 * @param requestedAt when it was asked, or null in state {@code DEAD}
 */
public record DeadLetter(String decisionId, String ruleSet, int version, Instant queuedAt, int attempts,
        String lastError, DeadLetterState state, String requestedBy, String requestComment, Instant requestedAt) {

    public DeadLetter {
        Objects.requireNonNull(decisionId, "decisionId must not be null");
        Objects.requireNonNull(state, "state must not be null");
    }
}
