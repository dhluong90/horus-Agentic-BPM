package io.github.dhluong90.bpm.rules.embedded;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * What an embedded engine may know of a rule set's effective version before it relies on it: the hit policy and the
 * declared inputs and outputs with their types, never the rules. The Process Engine reads it to check a guardrail rule
 * set's contract when a process definition is imported or approved (PRE-016).
 *
 * @param ruleSet the rule set name
 * @param version the effective version number
 * @param hitPolicy {@code FIRST}, {@code UNIQUE} or {@code COLLECT}
 * @param inputs each declared input and its type as written in a rule set, for example {@code integer} or
 *     {@code list<string>}, in declaration order
 * @param outputs each declared output and its type, in declaration order
 * @param rejectsUndeclaredInputs whether this Rule Engine refuses an evaluation that passes an undeclared input
 *     ({@code evaluation.reject-undeclared-inputs})
 * @param readInputs the declared inputs that a rule condition or an output cell reads, never the expressions; a
 *     declared input no rule reads cannot change an outcome
 */
public record RuleSetSignature(String ruleSet, int version, String hitPolicy, Map<String, String> inputs,
        Map<String, String> outputs, boolean rejectsUndeclaredInputs, Set<String> readInputs) {

    public RuleSetSignature {
        Objects.requireNonNull(ruleSet, "ruleSet must not be null");
        Objects.requireNonNull(hitPolicy, "hitPolicy must not be null");
        inputs = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(inputs, "inputs")));
        outputs = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(outputs, "outputs")));
        readInputs = Collections.unmodifiableSet(new LinkedHashSet<>(Objects.requireNonNull(readInputs, "readInputs")));
    }

    /** A signature that does not say which inputs are read: every declared input counts as read (fail closed). */
    public RuleSetSignature(String ruleSet, int version, String hitPolicy, Map<String, String> inputs,
            Map<String, String> outputs, boolean rejectsUndeclaredInputs) {
        this(ruleSet, version, hitPolicy, inputs, outputs, rejectsUndeclaredInputs, inputs.keySet());
    }
}
