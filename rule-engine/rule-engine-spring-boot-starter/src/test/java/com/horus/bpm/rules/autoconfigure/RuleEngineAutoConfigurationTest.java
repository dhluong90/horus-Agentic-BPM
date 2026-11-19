package com.horus.bpm.rules.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.horus.bpm.rules.postgres.DecisionLogPartitions;
import com.horus.bpm.rules.postgres.RuleEngineSchemaMigrator;
import com.horus.bpm.rules.postgres.RuleEngineTransactions;
import com.horus.bpm.rules.postgres.SchemaMigrationResult;
import com.horus.bpm.rules.postgres.SchemaStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Wiring of the starter without a database. The migrator and the partition step are replaced by
 * stand-ins; they run against PostgreSQL in the storage module and example host integration tests.
 */
@ExtendWith(OutputCaptureExtension.class)
class RuleEngineAutoConfigurationTest {

    /** Tests of the health contributor, which exists on Spring Boot 4 only (ADR-0011); the 3.5 leg skips them. */
    static final String SPRING_BOOT_4_ONLY = "spring-boot-4-only";

    private static final Instant JAN_26_2027 = Instant.parse("2027-01-26T00:00:00Z");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    RuleEngineAutoConfiguration.class, RuleEngineInactiveAutoConfiguration.class));

    @Test
    void TR_EMB_04_engine_binds_to_the_host_datasource_and_transaction_manager() {
        runner.withUserConfiguration(HostWithEngineStandIns.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DataSource.class);
            assertThat(context.getBean(RuleEngineTransactions.class).transactionManager())
                    .isSameAs(context.getBean(PlatformTransactionManager.class));
        });
    }

    @Test
    void TR_EMB_04_engine_registers_no_bean_types_the_host_owns() {
        runner.withUserConfiguration(HostWithEngineStandIns.class).run(context -> {
            assertThat(context).doesNotHaveBean(Flyway.class);
            assertThat(context).doesNotHaveBean(TransactionOperations.class);
            assertThat(context).doesNotHaveBean(Clock.class);
            assertThat(context.getBeanNamesForType(DataSource.class)).containsExactly("dataSource");
        });
    }

    @Test
    void TR_EMB_04_start_up_fails_when_the_transaction_manager_manages_another_datasource() {
        runner.withUserConfiguration(HostWithForeignTransactionManager.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("Engine writes would not join the caller's transaction");
        });
    }

    @Test
    void TR_EMB_04_a_proxied_datasource_still_counts_as_the_same_resource() {
        runner.withUserConfiguration(HostWithProxiedTransactionManager.class)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(RuleEngineTransactions.class));
    }

    @Test
    void engine_fails_fast_when_the_host_has_no_transaction_manager() {
        runner.withUserConfiguration(HostDataSourceOnly.class, EngineStandIns.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("needs exactly one PlatformTransactionManager");
        });
    }

    @Test
    void starter_backs_off_without_a_host_datasource_and_says_so(CapturedOutput output) {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(RuleEngineSchemaMigrator.class);
            assertThat(context).doesNotHaveBean(RuleEngineTransactions.class);
        });
        assertThat(output).contains("The Rule Engine starter is on the classpath but inactive");
    }

    @Test
    void starter_backs_off_with_two_datasources_and_no_primary_and_says_so(CapturedOutput output) {
        runner.withUserConfiguration(HostWithTwoDataSources.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(RuleEngineSchemaMigrator.class);
        });
        assertThat(output).contains("no single DataSource bean");
    }

    @Test
    void starter_backs_off_quietly_when_disabled(CapturedOutput output) {
        runner.withUserConfiguration(HostWithEngineStandIns.class)
                .withPropertyValues("horus.bpm.rules.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(RuleEngineTransactions.class);
                    assertThat(context).doesNotHaveBean(RuleEngineSchemaInitializer.class);
                    assertThat(context).doesNotHaveBean("ruleEngineHealthIndicator");
                });
        assertThat(output).doesNotContain("inactive");
    }

    @Test
    void TR_OPS_04_start_up_migrates_then_creates_partitions_for_the_host_clock_month() {
        runner.withUserConfiguration(HostWithEngineStandIns.class, FixedClock.class).run(context -> {
            RuleEngineSchemaMigrator migrator = context.getBean(RuleEngineSchemaMigrator.class);
            DecisionLogPartitions partitions = context.getBean(DecisionLogPartitions.class);
            InOrder order = inOrder(migrator, partitions);
            order.verify(migrator).migrate();
            order.verify(partitions).ensureCurrentAndNextMonth(JAN_26_2027);
        });
    }

    @Test
    void start_up_with_migration_off_creates_partitions_when_the_schema_is_current() {
        runner.withUserConfiguration(HostWithEngineStandIns.class, FixedClock.class)
                .withPropertyValues("horus.bpm.rules.migrate-on-start=false")
                .run(context -> {
                    verify(context.getBean(RuleEngineSchemaMigrator.class), never()).migrate();
                    verify(context.getBean(DecisionLogPartitions.class)).ensureCurrentAndNextMonth(JAN_26_2027);
                });
    }

    @Test
    void start_up_with_migration_off_and_a_pending_schema_skips_partitions(CapturedOutput output) {
        runner.withUserConfiguration(HostWithPendingSchema.class)
                .withPropertyValues("horus.bpm.rules.migrate-on-start=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    verify(context.getBean(DecisionLogPartitions.class), never()).ensureCurrentAndNextMonth(
                            org.mockito.ArgumentMatchers.any());
                });
        assertThat(output).contains("Rule Engine schema is not current");
    }

    @Test
    @Tag(SPRING_BOOT_4_ONLY)
    void health_is_down_while_migrations_are_pending() {
        RuleEngineSchemaMigrator migrator = mock(RuleEngineSchemaMigrator.class);
        when(migrator.status()).thenReturn(new SchemaStatus("1", 2));

        Health health = new RuleEngineHealthIndicator(migrator, mock(DecisionLogPartitions.class), fixedClock()).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("pendingMigrations", 2).containsEntry("schemaVersion", "1");
    }

    @Test
    @Tag(SPRING_BOOT_4_ONLY)
    void health_is_down_when_no_engine_migration_is_applied() {
        RuleEngineSchemaMigrator migrator = mock(RuleEngineSchemaMigrator.class);
        when(migrator.status()).thenReturn(new SchemaStatus(null, 1));

        Health health = new RuleEngineHealthIndicator(migrator, mock(DecisionLogPartitions.class), fixedClock()).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("pendingMigrations", 1);
    }

    @Test
    @Tag(SPRING_BOOT_4_ONLY)
    void health_is_up_and_reports_a_missing_next_month_partition() {
        RuleEngineSchemaMigrator migrator = mock(RuleEngineSchemaMigrator.class);
        when(migrator.status()).thenReturn(new SchemaStatus("1", 0));
        DecisionLogPartitions partitions = mock(DecisionLogPartitions.class);
        when(partitions.exists(YearMonth.of(2027, 2))).thenReturn(false);

        Health health = new RuleEngineHealthIndicator(migrator, partitions, fixedClock()).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("schemaVersion", "1")
                .containsEntry("nextMonthPartition", "rle_decision_2027_02")
                .containsEntry("nextMonthPartitionPresent", false);
    }

    @Test
    @Tag(SPRING_BOOT_4_ONLY)
    void health_state_is_computed_at_most_once_per_ttl() {
        RuleEngineSchemaMigrator migrator = mock(RuleEngineSchemaMigrator.class);
        when(migrator.status()).thenReturn(new SchemaStatus("1", 0));
        AtomicLong nanos = new AtomicLong();
        RuleEngineHealthIndicator indicator =
                new RuleEngineHealthIndicator(migrator, mock(DecisionLogPartitions.class), fixedClock(), nanos::get);

        indicator.health();
        nanos.addAndGet(RuleEngineHealthIndicator.CACHE_TTL.toNanos() - 1);
        indicator.health();
        verify(migrator, times(1)).status();

        nanos.addAndGet(1);
        indicator.health();
        verify(migrator, times(2)).status();
    }

    @Test
    @Tag(SPRING_BOOT_4_ONLY)
    void health_contributor_is_registered_as_rule_engine() {
        runner.withUserConfiguration(HostWithEngineStandIns.class).run(context -> {
            assertThat(context).hasBean("ruleEngineHealthIndicator");
            assertThat(context.getBean("ruleEngineHealthIndicator")).isInstanceOf(HealthIndicator.class);
        });
    }

    private static Clock fixedClock() {
        return Clock.fixed(JAN_26_2027, ZoneOffset.UTC);
    }

    @Configuration(proxyBeanMethods = false)
    static class HostDataSourceOnly {
        @Bean
        DataSource dataSource() {
            // Never connected: the engine's storage beans are stand-ins in these tests.
            return new DriverManagerDataSource("jdbc:postgresql://localhost:1/unused");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class HostJdbc extends HostDataSourceOnly {
        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class EngineStandIns {
        @Bean
        RuleEngineSchemaMigrator customMigrator() {
            RuleEngineSchemaMigrator migrator = mock(RuleEngineSchemaMigrator.class);
            when(migrator.migrate()).thenReturn(new SchemaMigrationResult(null, "1", 1));
            when(migrator.status()).thenReturn(new SchemaStatus("1", 0));
            return migrator;
        }

        @Bean
        DecisionLogPartitions customPartitions() {
            return mock(DecisionLogPartitions.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({HostJdbc.class, EngineStandIns.class})
    static class HostWithEngineStandIns {
    }

    @Configuration(proxyBeanMethods = false)
    @Import(HostJdbc.class)
    static class HostWithPendingSchema {
        @Bean
        RuleEngineSchemaMigrator customMigrator() {
            RuleEngineSchemaMigrator migrator = mock(RuleEngineSchemaMigrator.class);
            when(migrator.status()).thenReturn(new SchemaStatus(null, 1));
            return migrator;
        }

        @Bean
        DecisionLogPartitions customPartitions() {
            return mock(DecisionLogPartitions.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({HostDataSourceOnly.class, EngineStandIns.class})
    static class HostWithForeignTransactionManager {
        @Bean
        PlatformTransactionManager transactionManager() {
            return new DataSourceTransactionManager(new DriverManagerDataSource("jdbc:postgresql://localhost:1/other"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({HostDataSourceOnly.class, EngineStandIns.class})
    static class HostWithProxiedTransactionManager {
        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(new TransactionAwareDataSourceProxy(dataSource));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class HostWithTwoDataSources {
        @Bean
        DataSource loansDataSource() {
            return new DriverManagerDataSource("jdbc:postgresql://localhost:1/loans");
        }

        @Bean
        DataSource cardsDataSource() {
            return new DriverManagerDataSource("jdbc:postgresql://localhost:1/cards");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(JAN_26_2027, ZoneOffset.UTC);
        }
    }
}
