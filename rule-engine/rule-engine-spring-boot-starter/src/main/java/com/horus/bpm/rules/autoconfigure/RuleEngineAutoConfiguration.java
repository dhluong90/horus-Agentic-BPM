package com.horus.bpm.rules.autoconfigure;

import com.horus.bpm.rules.postgres.RuleEngineSchemaMigrator;
import com.horus.bpm.rules.postgres.RuleEngineTransactions;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Composition root of the Rule Engine inside a Spring Boot host.
 *
 * <p>Binds to the host's {@link DataSource} and {@link PlatformTransactionManager}; it never creates
 * a pool, a transaction manager, a {@code Flyway} bean or a {@code TransactionOperations} bean, so
 * the host's own auto-configuration is unaffected (TR-EMB-04, TR-EMB-06). Registered through
 * {@code AutoConfiguration.imports}; nothing is component-scanned. Every engine bean backs off when
 * the host defines its own.
 */
@AutoConfiguration(afterName = {
    "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
    "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
    "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
})
@ConditionalOnBean(DataSource.class)
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
    RuleEngineTransactions ruleEngineTransactions(ObjectProvider<PlatformTransactionManager> transactionManager) {
        PlatformTransactionManager hostTransactionManager = transactionManager.getIfUnique(() -> {
            throw new IllegalStateException("The Rule Engine needs exactly one PlatformTransactionManager from the host. "
                    + "Add spring-boot-starter-jdbc, or mark one transaction manager @Primary.");
        });
        return new RuleEngineTransactions(hostTransactionManager);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "horus.bpm.rules.schema", name = "migrate", havingValue = "true", matchIfMissing = true)
    RuleEngineSchemaInitializer ruleEngineSchemaInitializer(RuleEngineSchemaMigrator migrator) {
        return new RuleEngineSchemaInitializer(migrator);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(HealthIndicator.class)
    static class RuleEngineHealthConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "ruleEngineHealthIndicator")
        HealthIndicator ruleEngineHealthIndicator(
                RuleEngineSchemaMigrator migrator, ObjectProvider<RuleEngineSchemaInitializer> initializer) {
            return new RuleEngineHealthIndicator(migrator, initializer);
        }
    }
}
