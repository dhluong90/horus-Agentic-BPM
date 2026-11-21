package io.github.dhluong90.bpm.process.internal.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What the decision engine says about a rule set's effective version, read before a definition that relies on it is
 * accepted (PRE-016): its hit policy and declared inputs and outputs, or why it cannot say.
 */
public sealed interface DecisionSignature permits DecisionSignature.Found, DecisionSignature.Unavailable {

    /**
     * The effective version's contract.
     *
     * @param ruleSet the rule set name
     * @param version the effective version
     * @param hitPolicy {@code FIRST}, {@code UNIQUE} or {@code COLLECT}
     * @param inputs each declared input and its rule set type, for example {@code integer} or {@code list<string>}
     * @param outputs each declared output and its rule set type
     * @param rejectsUndeclaredInputs whether an evaluation that passes an undeclared input is refused
     * @param readInputs the declared inputs that a rule condition or output reads; an offline replay needs the value of
     *     a masked field only when it is one of them
     */
    record Found(String ruleSet, int version, String hitPolicy, Map<String, String> inputs,
            Map<String, String> outputs, boolean rejectsUndeclaredInputs, Set<String> readInputs)
            implements DecisionSignature {
        public Found {
            Objects.requireNonNull(ruleSet, "ruleSet must not be null");
            Objects.requireNonNull(hitPolicy, "hitPolicy must not be null");
            inputs = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(inputs, "inputs")));
            outputs = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(outputs, "outputs")));
            readInputs = Collections.unmodifiableSet(new LinkedHashSet<>(Objects.requireNonNull(readInputs,
                    "readInputs")));
        }

        /** A signature that does not say which inputs are read: every declared input counts as read (fail closed). */
        public Found(String ruleSet, int version, String hitPolicy, Map<String, String> inputs,
                Map<String, String> outputs, boolean rejectsUndeclaredInputs) {
            this(ruleSet, version, hitPolicy, inputs, outputs, rejectsUndeclaredInputs, inputs.keySet());
        }
    }

    /**
     * No contract can be read: the rule set is missing in the tenant, has no version in effect, or the decision
     * engine cannot describe it. A definition that relies on it is refused.
     *
     * @param code {@code RULE_SET_NOT_FOUND}, {@code NO_EFFECTIVE_VERSION} or another refusal code
     * @param detail a message safe to return to the caller
     */
    record Unavailable(String code, String detail) implements DecisionSignature {
        public Unavailable {
            Objects.requireNonNull(code, "code must not be null");
        }
    }
}
