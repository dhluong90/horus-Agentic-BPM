package io.github.dhluong90.bpm.process.api;

import java.util.Objects;

/**
 * One version of a definition key in the caller's tenant.
 *
 * @param key the definition key (kebab-case)
 * @param version the version number, from 1
 */
public record DefinitionRef(String key, int version) {

    public DefinitionRef {
        Objects.requireNonNull(key, "key must not be null");
        if (version < 1) {
            throw new InputInvalidException("version must be 1 or more, was " + version);
        }
    }

    /**
     * @param key the definition key
     * @param version the version number
     * @return the reference
     */
    public static DefinitionRef of(String key, int version) {
        return new DefinitionRef(key, version);
    }
}
