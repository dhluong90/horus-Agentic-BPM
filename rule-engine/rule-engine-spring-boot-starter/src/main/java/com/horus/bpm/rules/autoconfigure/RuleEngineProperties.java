package com.horus.bpm.rules.autoconfigure;

import com.horus.bpm.rules.internal.service.EngineDefaults;
import com.horus.bpm.rules.internal.service.EngineSettings;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration of the Rule Engine under {@code horus.bpm.rules}. Names follow the wiki page
 * "Rule Engine Configuration"; properties are added with the features that use them. Numeric defaults
 * come from {@link EngineDefaults} only (TR-EXT-04).
 *
 * @param enabled whether the Rule Engine is auto-configured at all
 * @param migrateOnStart whether the engine applies its own {@code rle_*} migrations at start-up on the
 *     host {@code DataSource}; {@code false} for hosts that run migrations in a separate job
 * @param cache the compiled-version cache
 * @param idempotency idempotency records
 * @param evaluation limits on evaluation inputs
 */
@ConfigurationProperties("horus.bpm.rules")
public record RuleEngineProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("true") boolean migrateOnStart,
        @DefaultValue Cache cache,
        @DefaultValue Idempotency idempotency,
        @DefaultValue Evaluation evaluation) {

    /**
     * The compiled-version cache.
     *
     * @param maxEntries compiled versions kept per replica, 1 to 100000; default 1000
     */
    public record Cache(Integer maxEntries) {
    }

    /**
     * Idempotency records (ADR-0006).
     *
     * @param retention how long a key is remembered, PT1M to P30D; default PT24H
     */
    public record Idempotency(Duration retention) {
    }

    /**
     * Limits on evaluation inputs.
     *
     * @param maxInputScale the largest absolute scale of a number input, 0 to 34; default 34. May be lowered,
     *     never raised
     */
    public record Evaluation(Integer maxInputScale) {
    }

    /**
     * Returns the validated settings; an out-of-range value fails start-up naming the property.
     *
     * @return the engine settings
     */
    public EngineSettings toSettings() {
        return new EngineSettings(
                cache.maxEntries() == null ? EngineDefaults.CACHE_MAX_ENTRIES : cache.maxEntries(),
                idempotency.retention() == null ? EngineDefaults.IDEMPOTENCY_RETENTION : idempotency.retention(),
                evaluation.maxInputScale() == null ? EngineDefaults.INPUT_MAX_SCALE : evaluation.maxInputScale());
    }
}
