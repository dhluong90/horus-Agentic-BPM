package com.horus.bpm.rules.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.horus.bpm.rules.internal.service.EngineDefaults;
import com.horus.bpm.rules.internal.service.EngineSettings;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Engine settings bound from {@code horus.bpm.rules.*}: defaults, bounds, refusal of invalid values (ADR-0024). */
class RuleEngineSettingsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesOnly.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RuleEngineProperties.class)
    static class PropertiesOnly {
        @Bean
        EngineSettings engineSettings(RuleEngineProperties properties) {
            return properties.toSettings();
        }
    }

    @Test
    void TR_EXT_04_defaults_come_from_engine_defaults() {
        runner.run(context -> assertThat(context.getBean(EngineSettings.class)).isEqualTo(EngineSettings.defaults())
                .extracting(EngineSettings::cacheMaxEntries, EngineSettings::idempotencyRetention)
                .containsExactly(EngineDefaults.CACHE_MAX_ENTRIES, EngineDefaults.IDEMPOTENCY_RETENTION));
    }

    @ParameterizedTest
    @CsvSource({"1, PT1M", "100000, P30D", "250, PT2H"})
    void TR_EXT_03_values_within_the_bounds_are_accepted(int maxEntries, String retention) {
        runner.withPropertyValues("horus.bpm.rules.cache.max-entries=" + maxEntries,
                        "horus.bpm.rules.idempotency.retention=" + retention)
                .run(context -> assertThat(context.getBean(EngineSettings.class))
                        .isEqualTo(new EngineSettings(maxEntries, Duration.parse(retention))));
    }

    @ParameterizedTest
    @CsvSource({
        "horus.bpm.rules.cache.max-entries=0, horus.bpm.rules.cache.max-entries",
        "horus.bpm.rules.cache.max-entries=100001, horus.bpm.rules.cache.max-entries",
        "horus.bpm.rules.idempotency.retention=PT30S, horus.bpm.rules.idempotency.retention",
        "horus.bpm.rules.idempotency.retention=P31D, horus.bpm.rules.idempotency.retention"
    })
    void TR_EXT_03_an_invalid_value_fails_start_up_naming_the_property(String property, String name) {
        runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining(name));
    }
}
