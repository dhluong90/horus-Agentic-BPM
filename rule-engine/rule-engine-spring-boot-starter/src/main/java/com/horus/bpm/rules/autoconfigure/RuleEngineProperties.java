package com.horus.bpm.rules.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration of the Rule Engine under {@code horus.bpm.rules}.
 *
 * @param enabled whether the Rule Engine is auto-configured at all
 * @param schema how the engine's {@code rle_*} schema is managed
 */
@ConfigurationProperties("horus.bpm.rules")
public record RuleEngineProperties(@DefaultValue("true") boolean enabled, @DefaultValue Schema schema) {

    /**
     * Schema management.
     *
     * @param migrate whether the engine applies its own migrations at start-up; set to false when the
     *     {@code rle_*} migrations are applied by a separate deployment step
     */
    public record Schema(@DefaultValue("true") boolean migrate) {
    }
}
