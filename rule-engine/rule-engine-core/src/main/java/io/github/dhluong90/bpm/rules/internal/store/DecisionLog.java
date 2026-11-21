package io.github.dhluong90.bpm.rules.internal.store;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Appends decision rows in the current transaction: one insert per decision, never an update (RLE-013), and reads them
 * back: one by id for replay and the log read, by a logged input, and by rule set and period (RLE-029). Reads are
 * always inside one tenant.
 */
public interface DecisionLog {

    /**
     * Appends one decision.
     *
     * @param record the row
     */
    void append(DecisionRecord record);

    /**
     * Reads one decision of a tenant.
     *
     * @param tenantId the tenant
     * @param decisionId the decision id (ULID)
     * @return the row, or empty when the tenant has no such decision
     */
    Optional<DecisionRecord> find(String tenantId, String decisionId);

    /**
     * Reads the decisions of one rule set in a period whose logged input has exactly one of the given texts, newest
     * first.
     *
     * @param tenantId the tenant
     * @param ruleSet the rule set name
     * @param input the input name
     * @param texts the logged texts, for a masked input its fingerprint under each key the tenant holds; not empty
     * @param from start of the period, inclusive
     * @param to end of the period, exclusive
     * @param limit most rows returned
     * @return the rows
     */
    List<DecisionRecord> findByLoggedInput(String tenantId, String ruleSet, String input, List<String> texts, Instant from,
            Instant to, int limit);

    /**
     * Returns the decisions of one rule set and tenant with {@code from <= createdAt < to}, newest first by
     * {@code (createdAt, id)}, strictly after the keyset position when one is given.
     *
     * @param tenantId the tenant
     * @param ruleSet the rule set name
     * @param from the first instant, inclusive
     * @param to the end, exclusive
     * @param after the last row of the previous page, or null for the first page
     * @param limit the most rows to return
     * @param statementTimeout the longest the read may run
     * @return the rows, at most {@code limit}
     */
    List<DecisionRecord> search(String tenantId, String ruleSet, Instant from, Instant to, Position after, int limit,
            Duration statementTimeout);

    /**
     * Returns the decisions of a tenant with one correlation id and {@code from <= createdAt < to}, newest first by
     * {@code (createdAt, id)}, strictly after the keyset position when one is given, from at most the monthly
     * partitions of the period (RLE-070).
     *
     * @param tenantId the tenant
     * @param correlationId the correlation id
     * @param ruleSet the rule set name, or null for every rule set of the tenant
     * @param from the first instant, inclusive
     * @param to the end, exclusive
     * @param after the last row of the previous page, or null for the first page
     * @param limit the most rows to return
     * @param statementTimeout the longest the read may run
     * @return the rows, at most {@code limit}
     */
    List<DecisionRecord> searchByCorrelationId(String tenantId, String correlationId, String ruleSet, Instant from,
            Instant to, Position after, int limit, Duration statementTimeout);

    /**
     * Returns the decisions of one rule set and tenant in a period whose logged input has exactly a value, newest
     * first, keyset-paged as {@link #search}. The input must not be masked: the caller checked that, and a masked
     * input holds a fingerprint here, never the value (RLE-071).
     *
     * @param tenantId the tenant
     * @param ruleSet the rule set name
     * @param input the input name
     * @param valueJson the value as logged, JSON text; compared as JSON, so {@code 30000} equals {@code 30000.00}
     * @param from the first instant, inclusive
     * @param to the end, exclusive
     * @param after the last row of the previous page, or null for the first page
     * @param limit the most rows to return
     * @param statementTimeout the longest the read may run
     * @return the rows, at most {@code limit}
     */
    List<DecisionRecord> searchByPlainInput(String tenantId, String ruleSet, String input, String valueJson,
            Instant from, Instant to, Position after, int limit, Duration statementTimeout);

    /**
     * Counts the decisions of one rule set and tenant with {@code from <= createdAt < to}, stopping at
     * {@code atMost}, from at most the monthly partitions of the period. The count is bounded, so a period of a very
     * busy table never makes the database read it all (RLE-077).
     *
     * @param tenantId the tenant
     * @param ruleSet the rule set name
     * @param from the first instant, inclusive
     * @param to the end, exclusive
     * @param atMost the count at which to stop
     * @param statementTimeout the longest the read may run
     * @return the number of decisions, at most {@code atMost}
     */
    long countInPeriod(String tenantId, String ruleSet, Instant from, Instant to, int atMost, Duration statementTimeout);

    /**
     * A keyset position in the decision log.
     *
     * @param createdAt the row time
     * @param id the decision id
     */
    record Position(Instant createdAt, String id) {
    }
}
