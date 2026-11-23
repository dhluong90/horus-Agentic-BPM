package io.github.dhluong90.bpm.rules.api;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * A rule set as a list shows it.
 *
 * @param ruleSet the rule set name
 * @param displayName the human name of the latest version's document, or the rule set name made readable
 *     ({@code card-fee} reads {@code Card fee}) when the document names none; never null
 * @param latestVersion the highest version number
 * @param latestStatus the status of that version
 * @param publishedVersion the version evaluation selects at the host clock, or null when none is in effect
 * @param lastChangedAt the latest creation, request, approval or rejection of any of its versions
 */
public record RuleSetSummary(String ruleSet, String displayName, int latestVersion, VersionStatus latestStatus,
        Integer publishedVersion, Instant lastChangedAt) {

    public RuleSetSummary {
        Objects.requireNonNull(ruleSet, "ruleSet must not be null");
        Objects.requireNonNull(latestStatus, "latestStatus must not be null");
        Objects.requireNonNull(lastChangedAt, "lastChangedAt must not be null");
        displayName = displayName == null || displayName.isBlank() ? humanise(ruleSet) : displayName;
    }

    /** A summary whose document names no display name: the rule set name made readable. */
    public RuleSetSummary(String ruleSet, int latestVersion, VersionStatus latestStatus, Integer publishedVersion,
            Instant lastChangedAt) {
        this(ruleSet, null, latestVersion, latestStatus, publishedVersion, lastChangedAt);
    }

    /**
     * Makes a kebab-case key readable: hyphens become spaces and the first letter is capitalised.
     *
     * @param key a rule set name such as {@code card-fee}
     * @return {@code Card fee}
     */
    public static String humanise(String key) {
        String spaced = key.replace('-', ' ').strip();
        return spaced.isEmpty() ? key : spaced.substring(0, 1).toUpperCase(Locale.ROOT) + spaced.substring(1);
    }
}
