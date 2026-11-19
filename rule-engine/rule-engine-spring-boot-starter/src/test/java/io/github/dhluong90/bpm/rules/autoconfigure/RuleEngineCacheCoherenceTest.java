package io.github.dhluong90.bpm.rules.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.dhluong90.bpm.rules.internal.service.EngineSettings;
import io.github.dhluong90.bpm.rules.internal.service.RuleEngines;
import io.github.dhluong90.bpm.rules.postgres.CacheCoherence;
import io.github.dhluong90.bpm.rules.postgres.PostgresRuleEngines;
import io.github.dhluong90.bpm.rules.postgres.RuleEngineTransactions;
import io.github.dhluong90.bpm.rules.spi.CallerResolver;
import io.github.dhluong90.bpm.rules.spi.TenantResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** The starter's side of ADR-0009: the three cache settings, the lifecycle bean and the cache meters. */
class RuleEngineCacheCoherenceTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RuleEngineProperties.class)
    static class PropertiesOnly {
    }

    private final ApplicationContextRunner properties = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesOnly.class);

    @Test
    void TR_ASY_04_cache_settings_default_to_five_seconds_and_listening_and_are_bounded() {
        properties.run(context -> {
            RuleEngineProperties.Cache cache = context.getBean(RuleEngineProperties.class).cache();
            assertThat(cache.epochCheckIntervalOrDefault()).isEqualTo(Duration.ofSeconds(5));
            assertThat(cache.listenNotifyOrDefault()).isTrue();
        });
        properties.withPropertyValues("horus.bpm.rules.cache.epoch-check-interval=PT1S",
                        "horus.bpm.rules.cache.listen-notify=false")
                .run(context -> {
                    RuleEngineProperties.Cache cache = context.getBean(RuleEngineProperties.class).cache();
                    assertThat(cache.epochCheckIntervalOrDefault()).isEqualTo(Duration.ofSeconds(1));
                    assertThat(cache.listenNotifyOrDefault()).isFalse();
                });
        properties.withPropertyValues("horus.bpm.rules.cache.epoch-check-interval=PT10M")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("horus.bpm.rules.cache.epoch-check-interval"));
        properties.withPropertyValues("horus.bpm.rules.cache.epoch-check-interval=PT0.05S")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void TR_ASY_04_with_listen_notify_off_the_lifecycle_runs_the_epoch_check_without_a_listener() {
        RuleEngines engines = engines(50);
        RuleEngineCacheCoherence lifecycle = new RuleEngineCacheCoherence(
                new CacheCoherence(mock(DataSource.class), engines.cache(), Duration.ofSeconds(5), false));

        lifecycle.start();
        try {
            assertThat(lifecycle.isRunning()).isTrue();
            assertThat(lifecycle.listening()).isFalse();
        } finally {
            lifecycle.stop();
        }
        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    void RLE_034_cache_size_gauge_and_eviction_counters_by_cause_are_bound() {
        RuleEngines engines = engines(50);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new RuleEngineCacheMetrics(engines.cache()).bindTo(registry);

        assertThat(registry.get("horus.rle.cache.size").gauge().value()).isZero();
        for (String cause : new String[] {"size", "epoch", "notify"}) {
            assertThat(registry.get("horus.rle.cache.evictions").tag("cause", cause).functionCounter().count())
                    .isZero();
        }
        engines.cache().evict("bank-a", "card-fee", 2);
        assertThat(registry.get("horus.rle.cache.evictions").tag("cause", "notify").functionCounter().count())
                .isZero();
    }

    private static RuleEngines engines(int maxEntries) {
        TenantResolver tenants = () -> "bank-a";
        CallerResolver callers = new CallerResolver() {
            @Override
            public String currentCallerId() {
                return "teller-01";
            }

            @Override
            public java.util.Set<String> currentPermissions() {
                return java.util.Set.of("rules:evaluate");
            }
        };
        return PostgresRuleEngines.create(mock(DataSource.class),
                new RuleEngineTransactions(mock(PlatformTransactionManager.class)), tenants, callers,
                Clock.systemUTC(), new EngineSettings(maxEntries, Duration.ofHours(24), 34, true));
    }
}
