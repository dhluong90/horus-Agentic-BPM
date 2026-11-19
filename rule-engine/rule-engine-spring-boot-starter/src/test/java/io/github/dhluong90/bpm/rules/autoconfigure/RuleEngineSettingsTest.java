package io.github.dhluong90.bpm.rules.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.rules.internal.service.EngineDefaults;
import io.github.dhluong90.bpm.rules.internal.service.EngineSettings;
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
                .extracting(EngineSettings::cacheMaxEntries, EngineSettings::idempotencyRetention,
                        EngineSettings::maxInputScale)
                .containsExactly(EngineDefaults.CACHE_MAX_ENTRIES, EngineDefaults.IDEMPOTENCY_RETENTION,
                        EngineDefaults.INPUT_MAX_SCALE));
    }

    @ParameterizedTest
    @CsvSource({"1, PT1M, 0", "100000, P30D, 34", "250, PT2H, 10"})
    void TR_EXT_03_values_within_the_bounds_are_accepted(int maxEntries, String retention, int maxScale) {
        runner.withPropertyValues("horus.bpm.rules.cache.max-entries=" + maxEntries,
                        "horus.bpm.rules.idempotency.retention=" + retention,
                        "horus.bpm.rules.evaluation.max-input-scale=" + maxScale)
                .run(context -> assertThat(context.getBean(EngineSettings.class))
                        .isEqualTo(new EngineSettings(maxEntries, Duration.parse(retention), maxScale, true)));
    }

    @ParameterizedTest
    @CsvSource({"1, 1, 1", "1048576, 65536, 5000", "4096, 256, 200"})
    void TR_EXT_03_size_limits_within_the_bounds_are_accepted(int outputBytes, int stringLength, int rows) {
        runner.withPropertyValues("horus.bpm.rules.evaluation.max-output-bytes=" + outputBytes,
                        "horus.bpm.rules.evaluation.max-string-length=" + stringLength,
                        "horus.bpm.rules.evaluation.max-rows=" + rows)
                .run(context -> assertThat(context.getBean(EngineSettings.class))
                        .isEqualTo(EngineSettings.defaults().withSizeLimits(outputBytes, stringLength, rows)));
    }

    @Test
    void TR_EXT_03_page_size_and_reason_length_may_be_lowered() {
        runner.withPropertyValues("horus.bpm.rules.query.max-page-size=50",
                        "horus.bpm.rules.lifecycle.reason-max-length=500")
                .run(context -> assertThat(context.getBean(EngineSettings.class))
                        .extracting(EngineSettings::queryMaxPageSize, EngineSettings::reasonMaxLength)
                        .containsExactly(50, 500));
        runner.run(context -> assertThat(context.getBean(EngineSettings.class))
                .extracting(EngineSettings::queryMaxPageSize, EngineSettings::reasonMaxLength)
                .containsExactly(EngineDefaults.QUERY_MAX_PAGE_SIZE, EngineDefaults.REASON_MAX_LENGTH));
    }

    @org.junit.jupiter.api.Test
    void TR_EXT_01_undeclared_inputs_can_be_ignored_instead_of_refused() {
        runner.withPropertyValues("horus.bpm.rules.evaluation.reject-undeclared-inputs=false")
                .run(context -> assertThat(context.getBean(EngineSettings.class).rejectUndeclaredInputs()).isFalse());
    }

    @ParameterizedTest
    @CsvSource({
        "horus.bpm.rules.cache.max-entries=0, horus.bpm.rules.cache.max-entries",
        "horus.bpm.rules.cache.max-entries=100001, horus.bpm.rules.cache.max-entries",
        "horus.bpm.rules.idempotency.retention=PT30S, horus.bpm.rules.idempotency.retention",
        "horus.bpm.rules.idempotency.retention=P31D, horus.bpm.rules.idempotency.retention",
        "horus.bpm.rules.evaluation.max-input-scale=35, horus.bpm.rules.evaluation.max-input-scale",
        "horus.bpm.rules.evaluation.max-input-scale=-1, horus.bpm.rules.evaluation.max-input-scale",
        "horus.bpm.rules.evaluation.max-output-bytes=1048577, horus.bpm.rules.evaluation.max-output-bytes",
        "horus.bpm.rules.evaluation.max-output-bytes=0, horus.bpm.rules.evaluation.max-output-bytes",
        "horus.bpm.rules.evaluation.max-string-length=65537, horus.bpm.rules.evaluation.max-string-length",
        "horus.bpm.rules.evaluation.max-string-length=0, horus.bpm.rules.evaluation.max-string-length",
        "horus.bpm.rules.evaluation.max-rows=5001, horus.bpm.rules.evaluation.max-rows",
        "horus.bpm.rules.evaluation.max-rows=0, horus.bpm.rules.evaluation.max-rows",
        "horus.bpm.rules.query.max-page-size=0, horus.bpm.rules.query.max-page-size",
        "horus.bpm.rules.query.max-page-size=201, horus.bpm.rules.query.max-page-size",
        "horus.bpm.rules.lifecycle.reason-max-length=0, horus.bpm.rules.lifecycle.reason-max-length",
        "horus.bpm.rules.lifecycle.reason-max-length=2001, horus.bpm.rules.lifecycle.reason-max-length"
    })
    void TR_EXT_03_an_invalid_value_fails_start_up_naming_the_property(String property, String name) {
        runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining(name));
    }
}
