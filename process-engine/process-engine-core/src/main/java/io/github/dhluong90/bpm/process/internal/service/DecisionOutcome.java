package io.github.dhluong90.bpm.process.internal.service;

import java.util.Map;

/** The result of one decision step through the {@link DecisionPort}: matched, no match, or failed. */
public sealed interface DecisionOutcome {

    /**
     * A row matched.
     *
     * @param decisionId the logged decision
     * @param outputs the outputs
     * @param ruleIds the ids of the matched rules, in rule order
     */
    record Matched(String decisionId, Map<String, Object> outputs, java.util.List<String> ruleIds)
            implements DecisionOutcome {
        public Matched {
            // Outputs may hold null values, which Map.copyOf refuses.
            outputs = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(outputs));
            ruleIds = ruleIds == null ? java.util.List.of() : java.util.List.copyOf(ruleIds);
        }

        /**
         * A match whose rule ids are not known.
         *
         * @param decisionId the logged decision
         * @param outputs the outputs
         */
        public Matched(String decisionId, Map<String, Object> outputs) {
            this(decisionId, outputs, java.util.List.of());
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
