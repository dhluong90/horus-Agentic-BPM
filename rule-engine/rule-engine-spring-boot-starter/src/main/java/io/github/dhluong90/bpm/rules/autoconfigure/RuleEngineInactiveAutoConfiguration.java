package io.github.dhluong90.bpm.rules.autoconfigure;

import javax.sql.DataSource;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.autoconfigure.condition.NoneNestedConditions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

/**
 * Makes a starter that backs off visible. When the Rule Engine is enabled but the host has no single
 * {@link DataSource}, the engine creates no beans and no tables; this logs one warning at start-up
 * instead of failing silently.
 */
@AutoConfiguration(afterName = {
    // Spring Boot 4.x
    "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
    // Spring Boot 3.5.x
    "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
})
@ConditionalOnProperty(prefix = "horus.bpm.rules", name = "enabled", havingValue = "true", matchIfMissing = true)
@Conditional(RuleEngineInactiveAutoConfiguration.NoSingleDataSource.class)
public class RuleEngineInactiveAutoConfiguration {

    static final String MESSAGE = "The Rule Engine starter is on the classpath but inactive: the host has no single "
            + "DataSource bean (none, or several without @Primary). No rle_* tables are created. Add "
            + "spring-boot-starter-jdbc and a datasource, mark one DataSource @Primary, or set "
            + "horus.bpm.rules.enabled=false.";

    private static final Log LOG = LogFactory.getLog(RuleEngineInactiveAutoConfiguration.class);

    @Bean
    SmartInitializingSingleton ruleEngineInactiveWarning() {
        return () -> LOG.warn(MESSAGE);
    }

    /** Matches when the host does not have exactly one DataSource candidate. */
    static final class NoSingleDataSource extends NoneNestedConditions {

        NoSingleDataSource() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnSingleCandidate(DataSource.class)
        static final class SingleDataSource {
        }
    }
}
