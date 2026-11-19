package io.github.dhluong90.bpm.rules.internal.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A DMN rule: a row of the decision table.
 *
 * @param id the stable rule id, for example {@code R1}
 * @param when the condition
 * @param then output cells by output name, in document order
 */
public record Rule(String id, When when, Map<String, Cell> then) {

    public Rule {
        then = Collections.unmodifiableMap(new LinkedHashMap<>(then));
    }
}
