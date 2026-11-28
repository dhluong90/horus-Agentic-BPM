package io.github.dhluong90.bpm.rules.internal.store;

import io.github.dhluong90.bpm.rules.api.DeadLetter;
import io.github.dhluong90.bpm.rules.api.DeadLetterState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What an operator may do to the dead letters of an asynchronous decision log (RLE-231). A decision log that has a
 * queue implements it next to {@link DecisionLog}. Every call is for one tenant; nothing deletes or changes a queued
 * decision, a step is a new event row.
 */
public interface DecisionQueueAdmin {

    /** Event kind of a retry an operator asks for. */
    String RETRY_REQUESTED = "RETRY_REQUESTED";
    /** Event kind of a resolve an operator asks for. */
    String RESOLVE_REQUESTED = "RESOLVE_REQUESTED";
    /** Event kind of a request a second person refuses. */
    String REQUEST_REJECTED = "REQUEST_REJECTED";
    /** Event kind of a retry a second person approves. */
    String RETRY_APPROVED = "RETRY_APPROVED";
    /** Event kind of a resolve a second person approves. */
    String RESOLVE_APPROVED = "RESOLVE_APPROVED";

    /**
     * Lists the tenant's dead letters, oldest first.
     *
     * @param tenantId the tenant
     * @param limit the most rows to return
     * @return the dead letters, including those with a request waiting for a second person
     */
    List<DeadLetter> deadLetters(String tenantId, int limit);

    /**
     * Reads one dead letter.
     *
     * @param tenantId the tenant
     * @param decisionId the decision id
     * @return the dead letter, or empty when the decision is not queued or is not dead
     */
    Optional<DeadLetter> deadLetter(String tenantId, String decisionId);

    /**
     * Appends an event to a dead letter when it is in one of the given states, with the row locked so that two people
     * cannot both act on it.
     *
     * @param tenantId the tenant
     * @param decisionId the decision id
     * @param from the states the row must be in
     * @param kind the event kind, one of the constants of this interface
     * @param actor who acts
     * @param comment the reason, or null when the kind takes none
     * @param at the time of the event
     * @return false when the row was not in one of the states, so nothing was appended
     */
    boolean transition(String tenantId, String decisionId, Set<DeadLetterState> from, String kind, String actor,
            String comment, Instant at);
}
