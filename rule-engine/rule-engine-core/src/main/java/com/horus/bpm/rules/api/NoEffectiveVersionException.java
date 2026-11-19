package com.horus.bpm.rules.api;

/** {@code 404 NO_EFFECTIVE_VERSION}: no published version is effective at the evaluation clock. */
public final class NoEffectiveVersionException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param ruleSet the rule set name
     */
    public NoEffectiveVersionException(String ruleSet) {
        super("NO_EFFECTIVE_VERSION", "Rule set " + ruleSet + " has no effective version");
    }
}
