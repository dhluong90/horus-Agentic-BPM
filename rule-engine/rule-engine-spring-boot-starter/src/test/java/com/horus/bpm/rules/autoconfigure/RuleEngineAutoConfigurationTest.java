package com.horus.bpm.rules.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.horus.bpm.rules.postgres.RuleEngineSchemaMigrator;
import com.horus.bpm.rules.postgres.RuleEngineTransactions;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Wiring of the starter without a database. Schema migration is switched off here; it is exercised
 * against PostgreSQL in the example host's integration tests.
 */
class RuleEngineAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RuleEngineAutoConfiguration.class))
            .withPropertyValues("horus.bpm.rules.schema.migrate=false");

    @Test
    void tr_emb_04_engine_binds_to_the_host_datasource_and_transaction_manager() {
        runner.withUserConfiguration(HostJdbc.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DataSource.class);
            assertThat(context).hasSingleBean(PlatformTransactionManager.class);
            assertThat(context.getBean(RuleEngineTransactions.class).transactionManager())
                    .isSameAs(context.getBean(PlatformTransactionManager.class));
        });
    }

    @Test
    void tr_emb_04_engine_registers_no_bean_types_the_host_owns() {
        runner.withUserConfiguration(HostJdbc.class).run(context -> {
            assertThat(context).doesNotHaveBean(Flyway.class);
            assertThat(context).doesNotHaveBean(TransactionOperations.class);
            assertThat(context.getBeanNamesForType(DataSource.class)).containsExactly("dataSource");
        });
    }

    @Test
    void starter_backs_off_without_a_host_datasource() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(RuleEngineSchemaMigrator.class);
            assertThat(context).doesNotHaveBean(RuleEngineTransactions.class);
        });
    }

    @Test
    void starter_backs_off_when_disabled() {
        runner.withUserConfiguration(HostJdbc.class)
                .withPropertyValues("horus.bpm.rules.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(RuleEngineSchemaMigrator.class));
    }

    @Test
    void schema_migration_is_skipped_when_switched_off_and_health_is_still_contributed() {
        runner.withUserConfiguration(HostJdbc.class).run(context -> {
            assertThat(context).doesNotHaveBean(RuleEngineSchemaInitializer.class);
            assertThat(context).hasBean("ruleEngineHealthIndicator");
            assertThat(context.getBean("ruleEngineHealthIndicator")).isInstanceOf(HealthIndicator.class);
        });
    }

    @Test
    void host_defined_migrator_replaces_the_engine_default() {
        runner.withUserConfiguration(HostJdbc.class, HostMigrator.class).run(context -> {
            assertThat(context).hasSingleBean(RuleEngineSchemaMigrator.class);
            assertThat(context).hasBean("customMigrator");
        });
    }

    @Test
    void engine_fails_fast_when_the_host_has_no_transaction_manager() {
        runner.withUserConfiguration(HostDataSourceOnly.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("needs exactly one PlatformTransactionManager");
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class HostDataSourceOnly {
        @Bean
        DataSource dataSource() {
            // Never connected: migration is switched off in these tests.
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
    static class HostMigrator {
        @Bean
        RuleEngineSchemaMigrator customMigrator(DataSource dataSource) {
            return new RuleEngineSchemaMigrator(dataSource);
        }
    }
}
