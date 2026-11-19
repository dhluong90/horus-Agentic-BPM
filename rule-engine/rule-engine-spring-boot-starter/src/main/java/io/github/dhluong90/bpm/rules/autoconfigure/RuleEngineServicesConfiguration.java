package io.github.dhluong90.bpm.rules.autoconfigure;

import io.github.dhluong90.bpm.host.spi.EngineAccess;
import io.github.dhluong90.bpm.rules.api.RuleEngine;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.internal.service.RuleEngines;
import io.github.dhluong90.bpm.rules.postgres.PostgresRuleEngines;
import io.github.dhluong90.bpm.rules.postgres.RuleEngineTransactions;
import io.github.dhluong90.bpm.rules.rest.DecisionController;
import io.github.dhluong90.bpm.rules.rest.ProblemDetails;
import io.github.dhluong90.bpm.rules.rest.RuleSetController;
import io.github.dhluong90.bpm.rules.rest.SimulationController;
import io.github.dhluong90.bpm.rules.spi.CallerResolver;
import io.github.dhluong90.bpm.rules.spi.TenantResolver;
import java.time.Clock;
import java.util.Set;
import javax.sql.DataSource;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The engine's entry points ({@link RuleEngine}, {@link VersionService}), the security SPIs and the
 * REST controllers. Imported by {@link RuleEngineAutoConfiguration}; every bean backs off when the
 * host defines its own.
 */
@Configuration(proxyBeanMethods = false)
class RuleEngineServicesConfiguration {

    private static final Log LOG = LogFactory.getLog(RuleEngineServicesConfiguration.class);

    /**
     * The tenant from the host contract's {@code EngineAccess}, resolved per call so the host module's
     * bean, registered after this configuration, is found.
     */
    @Bean
    @ConditionalOnMissingBean
    TenantResolver ruleEngineTenantResolver(ObjectProvider<EngineAccess> access) {
        return EngineAccessResolvers.tenants(access::getIfAvailable);
    }

    @Bean
    @ConditionalOnMissingBean
    RuleEngines ruleEngines(DataSource dataSource, RuleEngineTransactions transactions, TenantResolver tenants,
            CallerResolver callers, ObjectProvider<Clock> clock, RuleEngineProperties properties) {
        return PostgresRuleEngines.create(dataSource, transactions, tenants, callers,
                RuleEngineAutoConfiguration.engineClock(clock), properties.toSettings());
    }

    @Bean
    @ConditionalOnMissingBean
    RuleEngineCursorKeyInitializer ruleEngineCursorKeyInitializer(RuleEngines engines) {
        return new RuleEngineCursorKeyInitializer(engines.cursorKeys());
    }

    @Bean
    @ConditionalOnMissingBean
    RuleEngine ruleEngine(RuleEngines engines) {
        return engines.ruleEngine();
    }

    @Bean
    @ConditionalOnMissingBean
    VersionService ruleEngineVersionService(RuleEngines engines) {
        return engines.versionService();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.security.core.context.SecurityContextHolder")
    static class SecurityCallerConfiguration {

        @Bean
        @ConditionalOnMissingBean
        CallerResolver ruleEngineCallerResolver(ObjectProvider<EngineAccess> access) {
            return EngineAccessResolvers.callers(access::getIfAvailable);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass("org.springframework.security.core.context.SecurityContextHolder")
    static class NoSecurityCallerConfiguration {

        @Bean
        @ConditionalOnMissingBean
        CallerResolver ruleEngineCallerResolver() {
            LOG.warn("No CallerResolver bean and no Spring Security: every Rule Engine write is refused with "
                    + "UNAUTHENTICATED. Declare a CallerResolver bean.");
            return new CallerResolver() {
                @Override
                public String currentCallerId() {
                    return null;
                }

                @Override
                public Set<String> currentPermissions() {
                    return Set.of();
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
    static class RestConfiguration {

        @Bean
        @ConditionalOnMissingBean
        RuleSetController ruleEngineRuleSetController(VersionService versions, CallerResolver callers) {
            return new RuleSetController(versions, callers);
        }

        @Bean
        @ConditionalOnMissingBean
        DecisionController ruleEngineDecisionController(RuleEngine engine, CallerResolver callers) {
            return new DecisionController(engine, callers);
        }

        @Bean
        @ConditionalOnMissingBean
        SimulationController ruleEngineSimulationController(RuleEngine engine, CallerResolver callers) {
            return new SimulationController(engine, callers);
        }

        @Bean
        @ConditionalOnMissingBean
        ProblemDetails ruleEngineProblemDetails() {
            return new ProblemDetails();
        }
    }
}
