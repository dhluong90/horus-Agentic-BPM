package com.horus.bpm.rules.api;

import java.time.Instant;
import java.util.Objects;

/**
 * A rule set as a list shows it.
 *
 * @param ruleSet the rule set name
 * @param latestVersion the highest version number
 * @param latestStatus the status of that version
 * @param publishedVersion the version evaluation selects at the host clock, or null when none is in effect
 * @param lastChangedAt the latest creation, request, approval or rejection of any of its versions
 */
public record RuleSetSummary(String ruleSet, int latestVersion, VersionStatus latestStatus, Integer publishedVersion,
        Instant lastChangedAt) {

    public RuleSetSummary {
        Objects.requireNonNull(ruleSet, "ruleSet must not be null");
        Objects.requireNonNull(latestStatus, "latestStatus must not be null");
        Objects.requireNonNull(lastChangedAt, "lastChangedAt must not be null");
    }
}
