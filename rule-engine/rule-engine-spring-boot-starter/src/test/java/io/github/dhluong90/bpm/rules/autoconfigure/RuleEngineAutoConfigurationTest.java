package io.github.dhluong90.bpm.rules.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.dhluong90.bpm.rules.postgres.DecisionLogPartitions;
import io.github.dhluong90.bpm.rules.postgres.RuleEngineSchemaMigrator;
import io.github.dhluong90.bpm.rules.postgres.RuleEngineTransactions;
import io.github.dhluong90.bpm.rules.postgres.SchemaMigrationResult;
import io.github.dhluong90.bpm.rules.postgres.SchemaStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.util.ClassUtils;

/**
 * Wiring of the starter without a database. The migrator and the partition step are replaced by
 * stand-ins; they run against PostgreSQL in the storage module and example host integration tests.
 */
@ExtendWith(OutputCaptureExtension.class)
class RuleEngineAutoConfigurationTest {

    private static final String PACKAGE = RuleEngineAutoConfiguration.class.getPackageName();

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
    void TR_EXT_02_host_beans_replace_the_engine_defaults_of_every_spi() {
        io.github.dhluong90.bpm.rules.spi.TenantResolver tenants = () -> "bank-a";
        io.github.dhluong90.bpm.rules.spi.CallerResolver callers = new io.github.dhluong90.bpm.rules.spi.CallerResolver() {
            @Override
            public String currentCallerId() {
                return "alice";
            }

            @Override
            public java.util.Set<String> currentPermissions() {
                return java.util.Set.of();
            }
        };
        io.github.dhluong90.bpm.rules.spi.MaskingPolicy masking = io.github.dhluong90.bpm.rules.spi.MaskingPolicy.none();
        runner.withUserConfiguration(HostWithEngineStandIns.class)
                .withBean(io.github.dhluong90.bpm.rules.spi.TenantResolver.class, () -> tenants)
                .withBean(io.github.dhluong90.bpm.rules.spi.CallerResolver.class, () -> callers)
                .withBean(io.github.dhluong90.bpm.rules.spi.MaskingPolicy.class, () -> masking)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(io.github.dhluong90.bpm.rules.spi.TenantResolver.class)).isSameAs(tenants);
                    assertThat(context.getBean(io.github.dhluong90.bpm.rules.spi.CallerResolver.class)).isSameAs(callers);
                    assertThat(context.getBean(io.github.dhluong90.bpm.rules.spi.MaskingPolicy.class)).isSameAs(masking);
                    assertThat(context).hasSingleBean(io.github.dhluong90.bpm.rules.api.RuleEngine.class);
                });
    }

    @Test
    void TR_TEN_01_the_rule_engine_bean_is_not_the_tenant_scoped_entry_point() {
        runner.withUserConfiguration(HostWithEngineStandIns.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(io.github.dhluong90.bpm.rules.api.RuleEngine.class))
                    .isNotInstanceOf(io.github.dhluong90.bpm.rules.embedded.TenantScopedRuleEngine.class);
            assertThat(context).doesNotHaveBean(io.github.dhluong90.bpm.rules.embedded.TenantScopedRuleEngine.class);
        });
    }

    @Test
    void TR_TEN_01_no_bean_a_host_can_inject_leads_to_the_tenant_scoped_entry_point() {
        runner.withUserConfiguration(HostWithEngineStandIns.class).run(context -> {
            assertThat(context).hasNotFailed();
            // RuleEngines is not a bean; the internals sit in a bean of a type host code cannot name
            assertThat(context).doesNotHaveBean(io.github.dhluong90.bpm.rules.internal.service.RuleEngines.class);
            assertThat(java.lang.reflect.Modifier.isPublic(RuleEngineInternals.class.getModifiers())).isFalse();
            for (String name : context.getBeanFactory().getSingletonNames()) {
                Object bean = context.getBeanFactory().getSingleton(name);
                if (bean == null) {
                    continue;
                }
                assertThat(bean).as(name)
                        .isNotInstanceOf(io.github.dhluong90.bpm.rules.embedded.TenantScopedRuleEngine.class)
                        .isNotInstanceOf(io.github.dhluong90.bpm.rules.internal.service.RuleEngines.class);
            }
            // The grant is a bean while the Process Engine's claimant is on the class path, and a host that injects
            // it is refused
            var grant = context.getBean(io.github.dhluong90.bpm.rules.embedded.EmbeddedEngineGrant.class);
            Object hostController = new Object();
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> grant.claim("system:process-engine", hostController))
                    .isInstanceOf(SecurityException.class);
        });
    }

    @Test
    void TR_TEN_01_a_grant_nobody_claimed_during_start_up_is_closed_when_start_up_finishes() {
        runner.withUserConfiguration(HostWithEngineStandIns.class).run(context -> {
            assertThat(context).hasNotFailed();
            var grant = context.getBean(io.github.dhluong90.bpm.rules.embedded.EmbeddedEngineGrant.class);

            // Then no identity can be claimed for the rest of the run, not even by the registered claimant's class
            assertThat(grant.isClaimed("system:process-engine")).isFalse();
            assertThat(grant.isOpen("system:process-engine")).isFalse();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> grant.claim("system:process-engine",
                            new io.github.dhluong90.bpm.process.autoconfigure.RuleEngineDecisionPort()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
        });
    }

    @Test
    void TR_TEN_01_without_the_process_engine_there_is_no_grant() {
        runner.withUserConfiguration(HostWithEngineStandIns.class)
                .withClassLoader(new org.springframework.boot.test.context.FilteredClassLoader(
                        io.github.dhluong90.bpm.process.autoconfigure.RuleEngineDecisionPort.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(io.github.dhluong90.bpm.rules.embedded.EmbeddedEngineGrant.class);
                    assertThat(context).hasSingleBean(io.github.dhluong90.bpm.rules.api.RuleEngine.class);
                });
    }

    @Test
    void TR_EXT_03_an_out_of_range_setting_stops_start_up() {
        runner.withUserConfiguration(HostWithEngineStandIns.class)
                .withPropertyValues("horus.bpm.rules.cache.max-entries=0")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("horus.bpm.rules.cache.max-entries"));
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
            order.verify(partitions).ensureAhead(JAN_26_2027, java.time.Duration.ofDays(7));
        });
    }

    @Test
    void start_up_with_migration_off_creates_partitions_when_the_schema_is_current() {
        runner.withUserConfiguration(HostWithEngineStandIns.class, FixedClock.class)
                .withPropertyValues("horus.bpm.rules.migrate-on-start=false")
                .run(context -> {
                    verify(context.getBean(RuleEngineSchemaMigrator.class), never()).migrate();
                    verify(context.getBean(DecisionLogPartitions.class)).ensureAhead(JAN_26_2027, java.time.Duration.ofDays(7));
                });
    }

    @Test
    void start_up_with_migration_off_and_a_pending_schema_skips_partitions(CapturedOutput output) {
        runner.withUserConfiguration(HostWithPendingSchema.class)
                .withPropertyValues("horus.bpm.rules.migrate-on-start=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    verify(context.getBean(DecisionLogPartitions.class), never()).ensureAhead(
                            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
                });
        assertThat(output).contains("Rule Engine schema is not current");
    }

    // The shared health check, line-neutral (#116): the adapters only translate its result.

    @Test
    void health_is_down_while_migrations_are_pending() {
        RuleEngineSchemaMigrator migrator = mock(RuleEngineSchemaMigrator.class);
        when(migrator.status()).thenReturn(new SchemaStatus("1", 2));

        RuleEngineHealthCheck.Result result =
                new RuleEngineHealthCheck(migrator, mock(DecisionLogPartitions.class), fixedClock()).check();

        assertThat(result.up()).isFalse();
        assertThat(result.details()).containsEntry("pendingMigrations", 2).containsEntry("schemaVersion", "1");
    }

    @Test
    void health_is_down_when_no_engine_migration_is_applied() {
        RuleEngineSchemaMigrator migrator = mock(RuleEngineSchemaMigrator.class);
        when(migrator.status()).thenReturn(new SchemaStatus(null, 1));

        RuleEngineHealthCheck.Result result =
                new RuleEngineHealthCheck(migrator, mock(DecisionLogPartitions.class), fixedClock()).check();

        assertThat(result.up()).isFalse();
        assertThat(result.details()).containsEntry("pendingMigrations", 1);
    }

    @Test
    void health_is_up_and_reports_a_missing_next_month_partition() {
        RuleEngineSchemaMigrator migrator = mock(RuleEngineSchemaMigrator.class);
        when(migrator.status()).thenReturn(new SchemaStatus("1", 0));
        DecisionLogPartitions partitions = mock(DecisionLogPartitions.class);
        when(partitions.exists(YearMonth.of(2027, 2))).thenReturn(false);

        RuleEngineHealthCheck.Result result = new RuleEngineHealthCheck(migrator, partitions, fixedClock()).check();

        assertThat(result.up()).isTrue();
        assertThat(result.details())
                .containsEntry("schemaVersion", "1")
                .containsEntry("nextMonthPartition", "rle_decision_2027_02")
                .containsEntry("nextMonthPartitionPresent", false);
    }

    @Test
    void health_state_is_computed_at_most_once_per_ttl() {
        RuleEngineSchemaMigrator migrator = mock(RuleEngineSchemaMigrator.class);
        when(migrator.status()).thenReturn(new SchemaStatus("1", 0));
        AtomicLong nanos = new AtomicLong();
        RuleEngineHealthCheck check =
                new RuleEngineHealthCheck(migrator, mock(DecisionLogPartitions.class), fixedClock(), nanos::get);

        check.check();
        nanos.addAndGet(RuleEngineHealthCheck.CACHE_TTL.toNanos() - 1);
        check.check();
        verify(migrator, times(1)).status();

        nanos.addAndGet(1);
        check.check();
        verify(migrator, times(2)).status();
    }

    // The adapter of the running Spring Boot line, read through that line's own health API.

    @Test
    void TR_EMB_01_one_health_adapter_for_this_spring_boot_line_is_registered_as_rule_engine() {
        runner.withUserConfiguration(HostWithEngineStandIns.class).run(context -> {
            assertThat(context).hasBean(RuleEngineAutoConfiguration.HEALTH_BEAN);
            Object indicator = context.getBean(RuleEngineAutoConfiguration.HEALTH_BEAN);
            assertThat(indicator.getClass().getName()).isEqualTo(PACKAGE + (springBoot4Health()
                    ? ".RuleEngineHealthIndicator" : ".RuleEngineSpringBoot35HealthIndicator"));
            assertThat(context.getBeansOfType(Class.forName(activeHealthApi()))).hasSize(1);
            assertThat(health(indicator).status()).isEqualTo("UP");
        });
    }

    @Test
    void TR_EMB_01_health_is_down_with_pending_migrations_on_this_spring_boot_line() {
        runner.withUserConfiguration(HostWithPendingSchema.class)
                .withPropertyValues("horus.bpm.rules.migrate-on-start=false")
                .run(context -> {
                    HealthView health = health(context.getBean(RuleEngineAutoConfiguration.HEALTH_BEAN));
                    assertThat(health.status()).isEqualTo("DOWN");
                    assertThat(health.details()).containsEntry("pendingMigrations", 1);
                });
    }

    @Test
    void TR_EXT_02_a_host_bean_named_rule_engine_health_indicator_replaces_the_engine_adapter() {
        runner.withUserConfiguration(HostWithEngineStandIns.class, HostHealthIndicator.class).run(context -> {
            assertThat(context.getBean(RuleEngineAutoConfiguration.HEALTH_BEAN))
                    .isEqualTo(HostHealthIndicator.HOST_BEAN);
            // By class name: the adapter of the other line cannot be loaded on this one.
            assertThat(context.getBeansOfType(Object.class).values()).extracting(bean -> bean.getClass().getName())
                    .doesNotContain(PACKAGE + ".RuleEngineHealthIndicator", PACKAGE + ".RuleEngineSpringBoot35HealthIndicator");
        });
    }

    @Test
    void TR_EXT_02_management_health_rule_engine_enabled_false_reports_no_rule_engine_component() {
        runner.withUserConfiguration(HostWithEngineStandIns.class)
                .withPropertyValues("management.health.ruleEngine.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(RuleEngineAutoConfiguration.HEALTH_BEAN));
    }

    @Test
    void TR_EXT_02_management_health_defaults_enabled_false_applies_unless_rule_engine_is_enabled_itself() {
        runner.withUserConfiguration(HostWithEngineStandIns.class)
                .withPropertyValues("management.health.defaults.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(RuleEngineAutoConfiguration.HEALTH_BEAN));
        runner.withUserConfiguration(HostWithEngineStandIns.class)
                .withPropertyValues(
                        "management.health.defaults.enabled=false", "management.health.ruleEngine.enabled=true")
                .run(context -> assertThat(context).hasBean(RuleEngineAutoConfiguration.HEALTH_BEAN));
    }

    // The off switch reads booleans as Spring Boot does for its own indicators: never silently off.

    @ParameterizedTest
    @ValueSource(strings = {"on", "yes", "1", "TRUE"})
    void TR_EXT_02_switch_values_spring_boot_reads_as_true_keep_the_rule_engine_component(String value) {
        runner.withUserConfiguration(HostWithEngineStandIns.class)
                .withPropertyValues("management.health.ruleEngine.enabled=" + value)
                .run(context -> assertThat(context).hasNotFailed().hasBean(RuleEngineAutoConfiguration.HEALTH_BEAN));
    }

    @ParameterizedTest
    @ValueSource(strings = {"off", "no", "0"})
    void TR_EXT_02_switch_values_spring_boot_reads_as_false_remove_the_rule_engine_component(String value) {
        runner.withUserConfiguration(HostWithEngineStandIns.class)
                .withPropertyValues("management.health.ruleEngine.enabled=" + value)
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(RuleEngineAutoConfiguration.HEALTH_BEAN));
    }

    @Test
    void TR_EXT_02_an_empty_switch_counts_as_unset() {
        runner.withUserConfiguration(HostWithEngineStandIns.class)
                .withPropertyValues("management.health.ruleEngine.enabled=")
                .run(context -> assertThat(context).hasNotFailed().hasBean(RuleEngineAutoConfiguration.HEALTH_BEAN));
        // Unset, so the defaults switch decides.
        runner.withUserConfiguration(HostWithEngineStandIns.class)
                .withPropertyValues("management.health.ruleEngine.enabled=", "management.health.defaults.enabled=false")
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(RuleEngineAutoConfiguration.HEALTH_BEAN));
        runner.withUserConfiguration(HostWithEngineStandIns.class)
                .withPropertyValues("management.health.defaults.enabled=")
                .run(context -> assertThat(context).hasNotFailed().hasBean(RuleEngineAutoConfiguration.HEALTH_BEAN));
    }

    @ParameterizedTest
    @ValueSource(strings = {"management.health.ruleEngine.enabled", "management.health.defaults.enabled"})
    void TR_EXT_02_a_switch_value_that_is_not_a_boolean_fails_start_up(String property) {
        runner.withUserConfiguration(HostWithEngineStandIns.class)
                .withPropertyValues(property + "=ture")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("ture");
                });
    }

    @Test
    void TR_EXT_02_the_switch_is_read_from_the_environment_variable() {
        // MANAGEMENT_HEALTH_RULEENGINE_ENABLED, as a container sets it.
        runner.withUserConfiguration(HostWithEngineStandIns.class)
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("test-env",
                                Map.of("MANAGEMENT_HEALTH_RULEENGINE_ENABLED", "0"))))
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(RuleEngineAutoConfiguration.HEALTH_BEAN));
    }

    // Exactly one adapter per host, and none without a health API (TR-EMB-02).

    @Test
    void TR_EMB_02_no_health_adapter_when_the_host_has_neither_health_api() {
        // A host without Actuator; on Spring Boot 4 also a host with Actuator but without spring-boot-health,
        // whose spring-boot-actuator jar has no org.springframework.boot.actuate.health package.
        runner.withUserConfiguration(HostWithEngineStandIns.class)
                .withClassLoader(new FilteredClassLoader(
                        "org.springframework.boot.health", "org.springframework.boot.actuate.health"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RuleEngineSchemaMigrator.class);
                    assertThat(context).doesNotHaveBean(RuleEngineAutoConfiguration.HEALTH_BEAN);
                });
    }

    @Test
    void TR_EMB_02_the_spring_boot_3_5_adapter_backs_off_when_both_health_apis_are_present() {
        String springBoot35Health = RuleEngineAutoConfiguration.SPRING_BOOT_3_5_HEALTH;
        if (springBoot4Health()) {
            // A real check, not an assumption: the 3.5 API is on this leg's test classpath (provided scope).
            assertThat(ClassUtils.isPresent(springBoot35Health, null)).as(springBoot35Health + " present").isTrue();
        }
        runner.withUserConfiguration(HostWithEngineStandIns.class).run(context -> {
            if (springBoot4Health()) {
                assertThat(context.getBeansOfType(Class.forName(springBoot35Health))).isEmpty();
                assertThat(context.getBean(RuleEngineAutoConfiguration.HEALTH_BEAN).getClass().getName())
                        .isEqualTo(PACKAGE + ".RuleEngineHealthIndicator");
            } else {
                assertThat(context.getBeansOfType(Class.forName(springBoot35Health)))
                        .containsOnlyKeys(RuleEngineAutoConfiguration.HEALTH_BEAN);
            }
        });
    }

    /** True on Spring Boot 4 hosts (the health module is present), false on Spring Boot 3.5 hosts. */
    private static boolean springBoot4Health() {
        return ClassUtils.isPresent(RuleEngineAutoConfiguration.SPRING_BOOT_4_HEALTH, null);
    }

    private static String activeHealthApi() {
        return springBoot4Health()
                ? RuleEngineAutoConfiguration.SPRING_BOOT_4_HEALTH : RuleEngineAutoConfiguration.SPRING_BOOT_3_5_HEALTH;
    }

    private record HealthView(String status, Map<String, Object> details) {
    }

    /** Reads a health indicator through the running line's public health API, without compiling against it. */
    @SuppressWarnings("unchecked")
    private static HealthView health(Object indicator) throws ReflectiveOperationException {
        Object health = Class.forName(activeHealthApi()).getMethod("health").invoke(indicator);
        Object status = health.getClass().getMethod("getStatus").invoke(health);
        String code = (String) status.getClass().getMethod("getCode").invoke(status);
        return new HealthView(code, (Map<String, Object>) health.getClass().getMethod("getDetails").invoke(health));
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

        /** No database here: the cursor key load runs against PostgreSQL in the storage module and example host. */
        @Bean
        RuleEngineCursorKeyInitializer customCursorKeys() {
            return new RuleEngineCursorKeyInitializer(() -> { }, () -> { });
        }

        /** No database here: the masking start-up check runs against PostgreSQL in the example host (RLE-035). */
        @Bean
        RuleEngineMaskingCheck customMaskingCheck() {
            return new RuleEngineMaskingCheck(() -> { });
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({HostJdbc.class, EngineStandIns.class})
    static class HostWithEngineStandIns {
    }

    @Configuration(proxyBeanMethods = false)
    static class HostHealthIndicator {
        static final Object HOST_BEAN = "the host's own ruleEngine health indicator";

        @Bean(RuleEngineAutoConfiguration.HEALTH_BEAN)
        Object ruleEngineHealthIndicator() {
            return HOST_BEAN;
        }
    }

    @Test
    void RLE_039_a_pending_schema_without_migration_tells_the_cursor_key_the_schema_state() {
        java.util.List<String> states = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicInteger loads = new java.util.concurrent.atomic.AtomicInteger();
        runner.withUserConfiguration(HostWithPendingSchema.class)
                .withBean(RuleEngineCursorKeyInitializer.class,
                        () -> new RuleEngineCursorKeyInitializer(loads::incrementAndGet, () -> { }, states::add))
                .withPropertyValues("horus.bpm.rules.migrate-on-start=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(loads).hasValue(0);
                    assertThat(states).singleElement().asString()
                            .contains("1 migration(s) pending", "migrate-on-start=false");
                });
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
