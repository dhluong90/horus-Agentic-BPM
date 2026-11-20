package io.github.dhluong90.bpm.rules.internal.store;

import java.util.List;

/**
 * A tenant's {@code PUBLISHED} versions whose latest {@code APPROVED} history row lists inputs masked only by the
 * tenant's {@code decision-log.mask-default} (V15, PD-104), read at start-up when that default is turned off.
 *
 * @param versions the first versions, by rule set name and version number
 * @param total how many versions match in all
 */
public record MaskedByDefaultVersions(List<Version> versions, int total) {

    public MaskedByDefaultVersions {
        versions = List.copyOf(versions);
    }

    /**
     * One version and the inputs only the default masked when it was approved.
     *
     * @param ruleSet the rule set name
     * @param version the version number
     * @param inputs the inputs, as recorded
     */
    public record Version(String ruleSet, int version, List<String> inputs) {
        public Version {
            inputs = List.copyOf(inputs);
        }
    }
}
