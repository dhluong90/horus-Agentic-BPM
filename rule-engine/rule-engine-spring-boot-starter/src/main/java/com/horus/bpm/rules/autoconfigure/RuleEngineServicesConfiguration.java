package com.horus.bpm.rules.autoconfigure;

import com.horus.bpm.rules.api.RuleEngine;
import com.horus.bpm.rules.api.VersionService;
import com.horus.bpm.rules.internal.service.RuleEngines;
import com.horus.bpm.rules.postgres.PostgresRuleEngines;
import com.horus.bpm.rules.postgres.RuleEngineTransactions;
import com.horus.bpm.rules.rest.DecisionController;
import com.horus.bpm.rules.rest.ProblemDetails;
import com.horus.bpm.rules.rest.RuleSetController;
import com.horus.bpm.rules.spi.CallerResolver;
import com.horus.bpm.rules.spi.TenantResolver;
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
import org.springframework.core.env.Environment;

/**
 * The engine's entry points ({@link RuleEngine}, {@link VersionService}), the security SPIs and the
 * REST controllers. Imported by {@link RuleEngineAutoConfiguration}; every bean backs off when the
 * host defines its own.
 */
@Configuration(proxyBeanMethods = false)
class RuleEngineServicesConfiguration {

    private static final Log LOG = LogFactory.getLog(RuleEngineServicesConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    TenantResolver ruleEngineTenantResolver(
            ObjectProvider<com.horus.bpm.host.spi.TenantResolver> hostResolver, Environment environment) {
        com.horus.bpm.host.spi.TenantResolver host = hostResolver.getIfUnique();
        if (host != null && SECURITY_PRESENT) {
            return new HostTenantResolverBridge(host, environment.getProperty("horus.bpm.tenancy.claim", "tenant_id"),
                    claims(RuleEngineServicesConfiguration.class.getClassLoader()));
        }
        LOG.warn("The host defines no TenantResolver: every Rule Engine call is refused with UNAUTHENTICATED. "
                + "Declare a com.horus.bpm.host.spi.TenantResolver (or the engine's own) that reads the tenant from "
                + "the authenticated principal.");
        return () -> null;
    }

    private static final boolean SECURITY_PRESENT = org.springframework.util.ClassUtils.isPresent(
            "org.springframework.security.core.context.SecurityContextHolder",
            RuleEngineServicesConfiguration.class.getClassLoader());

    /** Token claims when Spring Security OAuth2 is present; otherwise a principal has no claims. */
    static java.util.function.Function<org.springframework.security.core.Authentication, java.util.Map<String, Object>>
            claims(ClassLoader classLoader) {
        return org.springframework.util.ClassUtils.isPresent(OAuth2PrincipalClaims.CLAIM_ACCESSOR, classLoader)
                ? new OAuth2PrincipalClaims() : authentication -> java.util.Map.of();
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
        CallerResolver ruleEngineCallerResolver(RuleEngineProperties properties) {
            return new SecurityContextCallerResolver(properties.security().rolePermissions());
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
        ProblemDetails ruleEngineProblemDetails() {
            return new ProblemDetails();
        }
    }
}
