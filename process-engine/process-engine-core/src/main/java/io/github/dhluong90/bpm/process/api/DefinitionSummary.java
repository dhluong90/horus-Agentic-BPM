package io.github.dhluong90.bpm.process.api;

import java.time.Instant;
import java.util.Objects;

/**
 * One definition key of the tenant, as listed.
 *
 * @param key the definition key
 * @param name the name of the latest version, or null
 * @param latestVersion the highest version number
 * @param latestStatus the status of that version
 * @param effectiveVersion the version a start uses now, or null when the key has no active version
 * @param lastChangedAt when a version of the key was last created or approved
 */
public record DefinitionSummary(String key, String name, int latestVersion, String latestStatus,
        Integer effectiveVersion, Instant lastChangedAt) {

    public DefinitionSummary {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(latestStatus, "latestStatus must not be null");
        Objects.requireNonNull(lastChangedAt, "lastChangedAt must not be null");
    }
}
