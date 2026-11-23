package io.github.dhluong90.bpm.rules.api;

import java.time.Instant;

/**
 * {@code 422 EFFECTIVE_DATE_IN_PAST}: a publication request asks for a version to take effect before the request
 * itself (RLE-210, #470). The draft stays a draft.
 */
public final class EffectiveDateInPastException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param ruleSet the rule set name
     * @param version the version number
     * @param requested the instant asked for
     * @param now the request time
     */
    public EffectiveDateInPastException(String ruleSet, int version, Instant requested, Instant now) {
        super("EFFECTIVE_DATE_IN_PAST", "Version " + version + " of " + ruleSet + " cannot take effect at " + requested
                + ", which is before now (" + now + "): choose the current time or a later one");
    }
}
