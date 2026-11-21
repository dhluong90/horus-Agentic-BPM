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
     * Returns the retention in force. Unset logs a WARN, on every start-up, that everything is kept; {@code keep-forever}
     * is the explicit choice and logs an INFO instead.
     *
     * @param decisionLog the bound decision log settings
     * @return the retention, or null when unset or {@code keep-forever}: then nothing is ever dropped
     */
    static Period resolve(RuleEngineProperties.DecisionLog decisionLog) {
        Period retention = decisionLog.retentionPeriod();
        if (retention != null) {
            LOG.info("Rule Engine keeps decisions for " + retention + " (" + PROPERTY + "); older monthly partitions "
                    + "are dropped and each drop is recorded in rle_decision_log_drop");
        } else if (decisionLog.keepForeverChosen()) {
            LOG.info("Rule Engine keeps every decision: " + PROPERTY + " is keep-forever, so no partition is dropped");
        } else {
            LOG.warn("Rule Engine decision log retention is unset (" + PROPERTY + "), so every decision is kept and "
                    + "none is dropped. The log grows by one row per decision, roughly 1 to 3 KB each depending on "
                    + "inputs and outputs. Set " + PROPERTY + " to keep-forever or to whole months of at least 12, "
                    + "such as P12M; a later release will require it");
        }
        return retention;
    }
}
