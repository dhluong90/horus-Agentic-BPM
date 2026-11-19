package com.horus.bpm.rules.internal.model;

/**
 * A DMN input clause: one declared input of the decision table.
 *
 * @param name the input name
 * @param type the declared type
 * @param mask {@code mask} as declared: true or false, or null when the rule set does not say, in which case the
 *     host's or tenant's default applies ({@code decision-log.mask-default})
 */
public record InputClause(String name, ValueType type, Boolean mask) {

    /**
     * Creates a clause that declares {@code mask}.
     *
     * @param name the input name
     * @param type the declared type
     * @param masked the declared {@code mask}
     */
    public InputClause(String name, ValueType type, boolean masked) {
        this(name, type, Boolean.valueOf(masked));
    }

    /**
     * Tells whether the rule set declares {@code mask: true}.
     *
     * @return true when masked by declaration
     */
    public boolean masked() {
        return Boolean.TRUE.equals(mask);
    }

    /**
     * Tells whether the value is kept out of the decision log in plain text.
     *
     * @param maskByDefault the default for an input that declares nothing
     * @return the declared {@code mask}, or the default when nothing is declared
     */
    public boolean masked(boolean maskByDefault) {
        return mask == null ? maskByDefault : mask;
    }
}
