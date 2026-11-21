package io.github.dhluong90.bpm.rules.autoconfigure;

import java.time.Period;
import java.util.List;
import org.springframework.core.env.Environment;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * The start-up handling of {@code horus.bpm.rules.decision-log.retention} (RLE-017, PD-050). Deleting audit evidence is
 * the bank's explicit choice, never a product default: retention is off unless the host sets an ISO period such as
 * {@code P12M}. Unset keeps every decision forever, drops nothing and never stops start-up, so a host that upgrades
 * without configuring it behaves as before. From 1.0 the setting is required (#373, PD-130): an unset value refuses
 * start-up naming the property, except on the {@code dev} profile on its own (optionally with {@code test}).
 */
final class DecisionLogRetention {

    static final String PROPERTY = "horus.bpm.rules.decision-log.retention";

    private static final Log LOG = LogFactory.getLog(DecisionLogRetention.class);

    /** The first engine version that requires the setting (PD-130). */
    static final int REQUIRED_FROM_MAJOR = 1;

    private DecisionLogRetention() {
    }

    /**
     * Returns the retention in force, refusing start-up when the setting is required and missing.
     *
     * @param decisionLog the bound decision log settings
     * @param environment the environment, for the active profiles
     * @param engineVersion the engine's {@code Implementation-Version}, or null when it runs from a class directory
     *     (then the pre-1.0 behaviour applies)
     * @return the retention, or null when unset or {@code keep-forever}: then nothing is ever dropped
     * @throws IllegalStateException when the engine is at 1.0 or later, the profile is not {@code dev}, and retention
     *     is unset
     */
    static Period resolve(RuleEngineProperties.DecisionLog decisionLog, Environment environment, String engineVersion) {
        if (required(engineVersion) && decisionLog.retentionUnset() && !devOnly(environment)) {
            throw new IllegalStateException(PROPERTY + " is required from Rule Engine 1.0 and is not set. Set it to "
                    + RuleEngineProperties.DecisionLog.KEEP_FOREVER + " to keep every decision, or to whole months of "
                    + "at least 12 such as P12M. See the upgrade note in the wiki page Rule Engine Decision Log and "
                    + "Audit (PD-050, PD-130).");
        }
        return resolve(decisionLog);
    }

    /**
     * Tells whether this engine version requires the setting.
     *
     * @param engineVersion a version such as {@code 1.0.0} or {@code 0.3.0-SNAPSHOT}; null or unreadable is pre-1.0
     * @return true from major version 1
     */
    static boolean required(String engineVersion) {
        if (engineVersion == null) {
            return false;
        }
        int dot = engineVersion.indexOf('.');
        String major = dot < 0 ? engineVersion : engineVersion.substring(0, dot);
        try {
            return Integer.parseInt(major.trim()) >= REQUIRED_FROM_MAJOR;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean devOnly(Environment environment) {
        List<String> active = List.of(environment.getActiveProfiles());
        return active.contains("dev") && active.stream().allMatch(p -> p.equals("dev") || p.equals("test"));
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
                    + "such as P12M; Rule Engine 1.0 will refuse to start without it");
        }
        return retention;
    }
}
