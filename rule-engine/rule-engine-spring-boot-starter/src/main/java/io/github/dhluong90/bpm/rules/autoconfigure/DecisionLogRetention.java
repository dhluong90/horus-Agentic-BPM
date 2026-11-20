package io.github.dhluong90.bpm.rules.autoconfigure;

import java.time.Period;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * The start-up handling of {@code horus.bpm.rules.decision-log.retention} (RLE-017, PD-050). Deleting audit evidence is
 * the bank's explicit choice, never a product default: retention is off unless the host sets an ISO period such as
 * {@code P12M}. Unset keeps every decision forever, drops nothing and never stops start-up, so a host that upgrades
 * without configuring it behaves as before.
 */
final class DecisionLogRetention {

    static final String PROPERTY = "horus.bpm.rules.decision-log.retention";

    private static final Log LOG = LogFactory.getLog(DecisionLogRetention.class);

    private DecisionLogRetention() {
    }

    /**
     * Returns the retention in force and logs one INFO line saying whether retention is on.
     *
     * @param retention the bound property, already checked against the floor, or null
     * @return the retention, or null when none is set: then nothing is ever dropped
     */
    static Period resolve(Period retention) {
        if (retention != null) {
            LOG.info("Rule Engine keeps decisions for " + retention + " (" + PROPERTY + "); older monthly partitions "
                    + "are dropped and each drop is recorded in rle_decision_log_drop");
            return retention;
        }
        LOG.info("Rule Engine decision log retention is off: " + PROPERTY + " is not set, so every decision is kept "
                + "and no partition is dropped. Set an ISO period such as P12M to enable drops");
        return null;
    }
}
