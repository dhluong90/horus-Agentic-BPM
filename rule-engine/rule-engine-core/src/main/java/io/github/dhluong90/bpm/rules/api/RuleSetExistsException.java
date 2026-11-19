package io.github.dhluong90.bpm.rules.api;

/** {@code 409 RULE_SET_EXISTS}: a rule set of that name already exists in the tenant. */
public final class RuleSetExistsException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param ruleSet the rule set name
     */
    public RuleSetExistsException(String ruleSet) {
        super("RULE_SET_EXISTS", "Rule set " + ruleSet + " already exists");
    }
}
