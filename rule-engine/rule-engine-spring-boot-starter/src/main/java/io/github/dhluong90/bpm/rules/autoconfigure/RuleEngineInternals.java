package io.github.dhluong90.bpm.rules.autoconfigure;

import io.github.dhluong90.bpm.rules.internal.service.RuleEngines;
import java.util.Objects;

/**
 * The engine's internals as one bean, of a type host code cannot name (#336). The starter's own beans take what they
 * need from it; hosts get {@code RuleEngine}, {@code VersionService} and the other API beans, never
 * {@link RuleEngines}, so nothing a host can inject leads to the tenant-scoped entry point.
 *
 * @param engines the entry points built over the host's data source
 */
record RuleEngineInternals(RuleEngines engines) {

    RuleEngineInternals {
        Objects.requireNonNull(engines, "engines must not be null");
    }
}
