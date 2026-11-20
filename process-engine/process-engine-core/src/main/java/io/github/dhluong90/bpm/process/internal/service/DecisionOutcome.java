package io.github.dhluong90.bpm.process.internal.service;

import java.util.Map;

/** The result of one decision step through the {@link DecisionPort}: matched, no match, or failed. */
public sealed interface DecisionOutcome {

    /**
     * A row matched.
     *
     * @param decisionId the logged decision
     * @param outputs the outputs
     */
    record Matched(String decisionId, Map<String, Object> outputs) implements DecisionOutcome {
        public Matched {
            // Outputs may hold null values, which Map.copyOf refuses.
            outputs = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(outputs));
        }
    }

    /**
     * No row matched.
     *
     * @param decisionId the logged decision
     */
    record NoMatch(String decisionId) implements DecisionOutcome {
    }

    /**
     * The decision could not be made: rule set missing, no effective version, wrong input, refused tenant or caller.
     * The detail may carry input values, so it goes to the log only.
     *
     * @param code the error code
     * @param detail the detail
     */
    record Failed(String code, String detail) implements DecisionOutcome {
    }
}
