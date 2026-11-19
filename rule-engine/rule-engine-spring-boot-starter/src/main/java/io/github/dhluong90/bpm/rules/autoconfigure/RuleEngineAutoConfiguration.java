package io.github.dhluong90.bpm.rules.autoconfigure;

import io.github.dhluong90.bpm.host.spi.ModuleDescriptor;
import io.github.dhluong90.bpm.rules.postgres.DecisionLogPartitions;
import io.github.dhluong90.bpm.rules.postgres.RuleEngineSchemaMigrator;
import io.github.dhluong90.bpm.rules.postgres.RuleEngineTransactions;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Composition root of the Rule Engine inside a Spring Boot host.
 *
 * <p>Binds to the host's single {@link DataSource} and to the {@link PlatformTransactionManager} that
 * manages it; it never creates a pool, a transaction manager, a {@code Flyway}, a {@code Clock} or a
 * {@code TransactionOperations} bean, so the host's own auto-configuration is unaffected (TR-EMB-04,
 * TR-EMB-06). Registered through {@code AutoConfiguration.imports}; nothing is component-scanned.
 * Every engine bean backs off when the host defines its own. When the host has no single
 * {@code DataSource}, {@link RuleEngineInactiveAutoConfiguration} logs a warning instead.
 *
 * <p>Ordering names the auto-configuration classes of both Spring Boot lines as strings; names that
 * do not exist on the running line are ignored (ADR-0011).
 */
@AutoConfiguration(afterName = {
    // Spring Boot 4.x
    "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
    "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
    "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
    // Spring Boot 3.5.x
    "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
    "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
    "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
})
@ConditionalOnSingleCandidate(DataSource.class)
@ConditionalOnProperty(prefix = "horus.bpm.rules", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(RuleEngineProperties.class)
@Import(RuleEngineServicesConfiguration.class)
public class RuleEngineAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    RuleEngineSchemaMigrator ruleEngineSchemaMigrator(DataSource dataSource) {
        return new RuleEngineSchemaMigrator(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    RuleEngineTransactions ruleEngineTransactions(
            DataSource dataSource, ObjectProvider<PlatformTransactionManager> transactionManager) {
        PlatformTransactionManager hostTransactionManager = transactionManager.getIfUnique(() -> {
            throw new IllegalStateException("The Rule Engine needs exactly one PlatformTransactionManager from the host. "
                    + "Add spring-boot-starter-jdbc, or mark one transaction manager @Primary.");
        });
        HostResources.requireSameResource(dataSource, hostTransactionManager);
        return new RuleEngineTransactions(hostTransactionManager);
    }

    @Bean
    @ConditionalOnMissingBean
    DecisionLogPartitions ruleEngineDecisionLogPartitions(DataSource dataSource, RuleEngineTransactions transactions) {
        return new DecisionLogPartitions(dataSource, transactions);
    }

    @Bean
    @ConditionalOnMissingBean
    RuleEnginePartitionMaintenance ruleEnginePartitionMaintenance(
            DecisionLogPartitions partitions, RuleEngineProperties properties, ObjectProvider<Clock> clock) {
        return new RuleEnginePartitionMaintenance(partitions, engineClock(clock),
                properties.decisionLog().partitionsAheadOrDefault());
    }

    @Bean
    @ConditionalOnMissingBean
    RuleEngineSchemaInitializer ruleEngineSchemaInitializer(
            RuleEngineSchemaMigrator migrator,
            DecisionLogPartitions partitions,
            RuleEngineProperties properties,
            ObjectProvider<Clock> clock,
            ObjectProvider<RuleEngineCursorKeyInitializer> cursorKeys,
            ObjectProvider<RuleEnginePartitionMaintenance> maintenance) {
        return new RuleEngineSchemaInitializer(migrator, partitions, properties.migrateOnStart(), engineClock(clock),
                cursorKeys.getIfAvailable(), maintenance.getIfAvailable());
    }

    /** The decision log gauge, bound by the host's meter registry when Micrometer is present (DESIGN 4.8). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.binder.MeterBinder")
    static class RuleEngineMetricsConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "ruleEngineMetrics")
        RuleEngineMetrics ruleEngineMetrics(DecisionLogPartitions partitions) {
            return new RuleEngineMetrics(partitions);
        }
    }

    /**
     * The {@code rules} entry of {@code GET /api/bpm/meta} and the permissions {@code GET /api/bpm/me} may
     * list. A role mapping that names an unknown permission stops the start-up.
     */
    @Bean
    @ConditionalOnMissingBean(name = "ruleEngineModuleDescriptor")
    ModuleDescriptor ruleEngineModuleDescriptor(RuleEngineProperties properties) {
        return RuleEngineModule.descriptor(properties.security().rolePermissions(), properties.authoring().cockpitDrafts());
    }

    /** The host's unique {@code Clock} bean if it has one, otherwise system UTC. Never registered as a bean. */
    static Clock engineClock(ObjectProvider<Clock> clock) {
        return clock.getIfUnique(Clock::systemUTC);
    }

    /** Health API of Spring Boot 4 (module spring-boot-health). */
    static final String SPRING_BOOT_4_HEALTH = "org.springframework.boot.health.contributor.HealthIndicator";
    /** Health API of Spring Boot 3.5 (module spring-boot-actuator). */
    static final String SPRING_BOOT_3_5_HEALTH = "org.springframework.boot.actuate.health.HealthIndicator";
    /** Bean name on both lines; a host bean of this name replaces the engine's (TR-EXT-02). */
    static final String HEALTH_BEAN = "ruleEngineHealthIndicator";

    static RuleEngineHealthCheck healthCheck(RuleEngineSchemaMigrator migrator, DecisionLogPartitions partitions,
            ObjectProvider<Clock> clock, ObjectProvider<RuleEngineBackgroundJob> jobs) {
        return new RuleEngineHealthCheck(migrator, partitions, engineClock(clock), System::nanoTime,
                jobs.orderedStream().toList());
    }

    /** Spring Boot 4 hosts with the health module. Contributed as {@code ruleEngine}. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = SPRING_BOOT_4_HEALTH)
    @Conditional(OnRuleEngineHealthEnabledCondition.class)
    static class RuleEngineHealthConfiguration {

        @Bean(HEALTH_BEAN)
        @ConditionalOnMissingBean(name = HEALTH_BEAN)
        org.springframework.boot.health.contributor.HealthIndicator ruleEngineHealthIndicator(
                RuleEngineSchemaMigrator migrator, DecisionLogPartitions partitions, ObjectProvider<Clock> clock,
                ObjectProvider<RuleEngineBackgroundJob> jobs) {
            return new RuleEngineHealthIndicator(healthCheck(migrator, partitions, clock, jobs));
        }
    }

    /**
     * Spring Boot 3.5 hosts with Actuator: only when the 3.5 health API is present and the Spring Boot 4
     * health module is absent, so exactly one adapter is active on any host. Contributed as
     * {@code ruleEngine}.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = SPRING_BOOT_3_5_HEALTH)
    @ConditionalOnMissingClass(SPRING_BOOT_4_HEALTH)
    @Conditional(OnRuleEngineHealthEnabledCondition.class)
    static class RuleEngineSpringBoot35HealthConfiguration {

        @Bean(HEALTH_BEAN)
        @ConditionalOnMissingBean(name = HEALTH_BEAN)
        org.springframework.boot.actuate.health.HealthIndicator ruleEngineHealthIndicator(
                RuleEngineSchemaMigrator migrator, DecisionLogPartitions partitions, ObjectProvider<Clock> clock,
                ObjectProvider<RuleEngineBackgroundJob> jobs) {
            return new RuleEngineSpringBoot35HealthIndicator(healthCheck(migrator, partitions, clock, jobs));
        }
    }
}
