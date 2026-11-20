package io.github.dhluong90.bpm.rules.autoconfigure;

import java.time.Period;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.core.env.Environment;

/**
 * The start-up check of {@code horus.bpm.rules.decision-log.retention} (RLE-017, PD-050). Deleting audit evidence is
 * the bank's explicit choice, never a product default: the engine ships no retention, so nothing is dropped until the
 * host sets one, and outside the {@code dev} profile the host does not start until it has. The profile check matches
 * profiles the way {@code @Profile} does, as the development tenant check does.
 */
final class DecisionLogRetention {

    static final String PROPERTY = "horus.bpm.rules.decision-log.retention";
    static final String DEV_PROFILE = "dev";

    private static final Log LOG = LogFactory.getLog(DecisionLogRetention.class);

    private DecisionLogRetention() {
    }

    /**
     * Returns the retention in force, refusing start-up outside {@code dev} when none is set.
     *
     * @param retention the bound property, already checked against the floor, or null
     * @param environment the host environment
     * @return the retention, or null on the {@code dev} profile without one, where nothing is dropped
     * @throws IllegalStateException outside the {@code dev} profile when no retention is set
     */
    static Period required(Period retention, Environment environment) {
        if (retention != null) {
            LOG.info("Rule Engine keeps decisions for " + retention + " (" + PROPERTY + "); older monthly partitions "
                    + "are dropped and each drop is recorded in rle_decision_log_drop");
            return retention;
        }
        if (!environment.matchesProfiles(DEV_PROFILE)) {
            throw new IllegalStateException(PROPERTY + " is not set. The decision log is an audit trail, and how long "
                    + "it is kept is the host's decision: set it in whole months, at least "
                    + "horus.bpm.rules.decision-log.retention-floor (P12M), for example " + PROPERTY + "=P120M. "
                    + "Only the dev profile starts without it.");
        }
        LOG.info("Rule Engine keeps every decision: " + PROPERTY + " is not set (dev profile), so no partition is "
                + "dropped");
        return null;
    }
}
