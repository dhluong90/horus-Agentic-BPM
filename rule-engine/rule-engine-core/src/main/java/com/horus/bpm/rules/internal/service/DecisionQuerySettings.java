package com.horus.bpm.rules.internal.service;

import java.time.Duration;

/**
 * Validated bounds of decision log searches (ADR-0015). A value outside its range fails construction with a
 * message naming the property, so an invalid host configuration stops start-up (TR-EXT-03).
 *
 * @param maxRange {@code horus.bpm.rules.query.max-range}, 1 hour to {@code P31D}: the longest period of one search;
 *     may be lowered, never raised
 * @param statementTimeout {@code horus.bpm.rules.query.statement-timeout}, 100 ms to 30 s: the longest one search
 *     may run in the database
 */
public record DecisionQuerySettings(Duration maxRange, Duration statementTimeout) {

    public DecisionQuerySettings {
        if (maxRange == null || maxRange.compareTo(Duration.ofHours(1)) < 0
                || maxRange.compareTo(EngineDefaults.QUERY_MAX_RANGE) > 0) {
            throw new IllegalArgumentException("horus.bpm.rules.query.max-range must be PT1H to P31D "
                    + "(it may be lowered, never raised), was " + maxRange);
        }
        if (statementTimeout == null || statementTimeout.compareTo(EngineDefaults.QUERY_STATEMENT_TIMEOUT_MIN) < 0
                || statementTimeout.compareTo(EngineDefaults.QUERY_STATEMENT_TIMEOUT_MAX) > 0) {
            throw new IllegalArgumentException("horus.bpm.rules.query.statement-timeout must be PT0.1S to PT30S, was "
                    + statementTimeout);
        }
    }

    /**
     * Returns the defaults: 31 days and 2 seconds.
     *
     * @return the settings
     */
    public static DecisionQuerySettings defaults() {
        return new DecisionQuerySettings(EngineDefaults.QUERY_MAX_RANGE, EngineDefaults.QUERY_STATEMENT_TIMEOUT);
    }
}
