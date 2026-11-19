package com.horus.bpm.rules.internal.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The condition of a rule: cells by input name combined with AND, plus optional {@code any} (OR)
 * and {@code all} (AND) groups. An empty {@code When} matches everything.
 *
 * @param cells condition text by input name, in document order
 * @param any nested conditions of which at least one must match; empty when absent
 * @param all nested conditions that must all match; empty when absent
 */
public record When(Map<String, String> cells, List<When> any, List<When> all) {

    public When {
        cells = Collections.unmodifiableMap(new LinkedHashMap<>(cells));
        any = List.copyOf(any);
        all = List.copyOf(all);
    }
}
