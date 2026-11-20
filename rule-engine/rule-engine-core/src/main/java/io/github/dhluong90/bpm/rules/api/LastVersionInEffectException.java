package io.github.dhluong90.bpm.rules.api;

import java.time.Instant;

/**
 * {@code 409 LAST_VERSION_IN_EFFECT} (PD-101): the retirement would leave the rule set with no version in effect, so
 * every evaluation would fail until a new version is approved. Under {@code horus.bpm.lifecycle.retire-last-version:
 * confirm} the request goes ahead with {@code confirmNoVersionInEffect: true}; under {@code refuse} it never does. The
 * problem body carries the rule set, the version, the setting and {@code nextEffectiveFrom}.
 */
public final class LastVersionInEffectException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    private final String ruleSet;
    private final int version;
    private final String setting;
    private final Instant nextEffectiveFrom;

    /**
     * Creates the exception.
     *
     * @param ruleSet the rule set name
     * @param version the version number
     * @param setting the value of {@code lifecycle.retire-last-version}, {@code confirm} or {@code refuse}
     * @param nextVersion the next approved version that starts later, or null
     * @param nextEffectiveFrom its start, or null
     */
    public LastVersionInEffectException(String ruleSet, int version, String setting, Integer nextVersion,
            Instant nextEffectiveFrom) {
        super("LAST_VERSION_IN_EFFECT", ruleSet + " v" + version + " is the last version in effect of rule set "
                + ruleSet + "; retiring it leaves no version in effect and evaluations fail with NO_EFFECTIVE_VERSION"
                + (nextVersion == null ? " until a new version is approved."
                        : " until v" + nextVersion + " starts at " + nextEffectiveFrom + ".")
                + ("refuse".equals(setting)
                        ? " This host refuses it (lifecycle.retire-last-version: refuse); approve a replacement first."
                        : " Send confirmNoVersionInEffect: true with the reason to retire it anyway."));
        this.ruleSet = ruleSet;
        this.version = version;
        this.setting = setting;
        this.nextEffectiveFrom = nextEffectiveFrom;
    }

    /** @return the rule set name */
    public String ruleSet() {
        return ruleSet;
    }

    /** @return the version number */
    public int version() {
        return version;
    }

    /** @return the value of {@code lifecycle.retire-last-version} */
    public String setting() {
        return setting;
    }

    /** @return the start of the next approved version, or null when none is scheduled */
    public Instant nextEffectiveFrom() {
        return nextEffectiveFrom;
    }
}
