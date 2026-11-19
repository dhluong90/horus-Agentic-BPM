package com.horus.bpm.rules.api;

/** {@code 404 RULE_SET_NOT_FOUND}: no rule set of that name in the caller's tenant. */
public final class RuleSetNotFoundException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param ruleSet the rule set name
     */
    public RuleSetNotFoundException(String ruleSet) {
        super("RULE_SET_NOT_FOUND", "Rule set " + ruleSet + " not found");
    }
}
