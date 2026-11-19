package com.horus.bpm.rules.autoconfigure;

import com.horus.bpm.host.spi.ModuleDescriptor;
import com.horus.bpm.rules.postgres.DecisionLogPartitions;
import com.horus.bpm.rules.postgres.RuleEngineSchemaMigrator;
import com.horus.bpm.rules.postgres.RuleEngineTransactions;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
    RuleEngineSchemaInitializer ruleEngineSchemaInitializer(
            RuleEngineSchemaMigrator migrator,
            DecisionLogPartitions partitions,
            RuleEngineProperties properties,
            ObjectProvider<Clock> clock) {
        return new RuleEngineSchemaInitializer(migrator, partitions, properties.migrateOnStart(), engineClock(clock));
    }

    /**
     * The {@code rules} entry of {@code GET /api/bpm/meta} and the permissions {@code GET /api/bpm/me} may
     * list. A role mapping that names an unknown permission stops the start-up.
     */
    @Bean
    @ConditionalOnMissingBean(name = "ruleEngineModuleDescriptor")
    ModuleDescriptor ruleEngineModuleDescriptor(RuleEngineProperties properties) {
        return RuleEngineModule.descriptor(properties.security().rolePermissions());
    }

    /** The host's unique {@code Clock} bean if it has one, otherwise system UTC. Never registered as a bean. */
    static Clock engineClock(ObjectProvider<Clock> clock) {
        return clock.getIfUnique(Clock::systemUTC);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(HealthIndicator.class)
    static class RuleEngineHealthConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "ruleEngineHealthIndicator")
        HealthIndicator ruleEngineHealthIndicator(
                RuleEngineSchemaMigrator migrator, DecisionLogPartitions partitions, ObjectProvider<Clock> clock) {
            return new RuleEngineHealthIndicator(migrator, partitions, engineClock(clock));
        }
    }
}
