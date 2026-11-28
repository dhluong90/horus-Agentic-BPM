package io.github.dhluong90.bpm.expr.feel;

import java.util.Set;

/**
 * The names the parser must know to read multi-word names (DMN 1.5 section 10.3.1.2): names of inputs, decisions,
 * business knowledge models, item definition members and context entries in scope. Supplied by the caller, so the
 * parser has no DMN dependency.
 */
public interface FeelNameScope {

    /** @return every name in scope, with single spaces; empty for an expression over plain identifiers */
    Set<String> names();

    /** @return a scope with no multi-word name */
    static FeelNameScope empty() {
        return Set::of;
    }
}
