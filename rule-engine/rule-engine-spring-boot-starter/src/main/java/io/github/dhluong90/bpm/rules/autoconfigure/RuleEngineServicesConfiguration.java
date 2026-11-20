package io.github.dhluong90.bpm.rules.autoconfigure;

import io.github.dhluong90.bpm.host.spi.EngineAccess;
import io.github.dhluong90.bpm.host.spi.ModuleTenantSettings;
import io.github.dhluong90.bpm.rules.api.DecisionLogQuery;
import io.github.dhluong90.bpm.rules.api.DecisionLogService;
import io.github.dhluong90.bpm.rules.api.RuleEngine;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.VersionWarning;
import io.github.dhluong90.bpm.rules.internal.service.EngineSettings;
import io.github.dhluong90.bpm.rules.internal.service.RuleEngines;
import io.github.dhluong90.bpm.rules.postgres.CacheCoherence;
import io.github.dhluong90.bpm.rules.postgres.PostgresRuleEngines;
import io.github.dhluong90.bpm.rules.postgres.RuleEngineTransactions;
import io.github.dhluong90.bpm.rules.rest.DecisionController;
import io.github.dhluong90.bpm.rules.rest.DecisionLogController;
import io.github.dhluong90.bpm.rules.rest.ProblemDetails;
import io.github.dhluong90.bpm.rules.rest.RuleSetController;
import io.github.dhluong90.bpm.rules.rest.SimulationController;
import io.github.dhluong90.bpm.rules.spi.CallerResolver;
import io.github.dhluong90.bpm.rules.spi.MaskingPolicy;
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
import org.springframework.core.env.Environment;

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

    /**
     * Masking from {@code horus.bpm.rules.masking.*} and {@code decision-log.mask-default}; a host bean, for example
     * one reading a key per tenant from a vault, replaces it.
     */
    @Bean
    @ConditionalOnMissingBean
    MaskingPolicy ruleEngineMaskingPolicy(RuleEngineProperties properties, Environment environment) {
        return PropertyMaskingPolicy.from(properties,
                environment.getProperty(PropertyMaskingPolicy.SHARED_KEY_PROPERTY),
                environment.getProperty(PropertyMaskingPolicy.SHARED_KEY_ID_PROPERTY));
    }

    @Bean
    @ConditionalOnMissingBean
    RuleEngines ruleEngines(DataSource dataSource, RuleEngineTransactions transactions, TenantResolver tenants,
            CallerResolver callers, ObjectProvider<Clock> clock, RuleEngineProperties properties,
            MaskingPolicy masking, Environment environment) {
        EngineSettings settings = properties.toSettings(environment);
        warnIfApprovalsSkipPersonalDataAcknowledgement(settings);
        return PostgresRuleEngines.create(dataSource, transactions, tenants, callers,
                RuleEngineAutoConfiguration.engineClock(clock), settings, masking,
                properties.toQuerySettings());
    }

    /**
     * A list without {@code UNMASKED_PERSONAL_DATA} relaxes a control (PD-104): allowed, but never silently.
     *
     * @param settings the validated settings
     * @return whether the warning was logged
     */
    static boolean warnIfApprovalsSkipPersonalDataAcknowledgement(EngineSettings settings) {
        if (settings.acknowledgeWarnings().contains(VersionWarning.UNMASKED_PERSONAL_DATA)) {
            return false;
        }
        LOG.warn("horus.bpm.rules.lifecycle.acknowledge-warnings is " + settings.acknowledgeWarnings()
                + " and does not list " + VersionWarning.UNMASKED_PERSONAL_DATA + ": a version whose input looks like"
                + " personal data and is logged unmasked can be approved without the approver accepting it on the"
                + " record (PD-104). The setting is shown in /api/bpm/meta.");
        return true;
    }

    @Bean
    @ConditionalOnMissingBean
    RuleEngineMaskingCheck ruleEngineMaskingCheck(RuleEngines engines, MaskingPolicy masking,
            ObjectProvider<Clock> clock) {
        return RuleEngineMaskingCheck.of(engines.maskingCheck(), masking, RuleEngineAutoConfiguration.engineClock(clock));
    }

    /**
     * The masking default and strict mode in force for the caller's tenant, in the {@code rules} settings of
     * {@code GET /api/bpm/meta} (RLE-050, RLE-052).
     */
    @Bean
    @ConditionalOnMissingBean(name = "ruleEngineTenantSettings")
    ModuleTenantSettings ruleEngineTenantSettings(MaskingPolicy masking) {
        return RuleEngineModule.tenantSettings(masking);
    }

    @Bean
    @ConditionalOnMissingBean
    DecisionLogService ruleEngineDecisionLogService(RuleEngines engines) {
        return engines.decisionLog();
    }

    @Bean
    @ConditionalOnMissingBean
    RuleEngineCursorKeyInitializer ruleEngineCursorKeyInitializer(RuleEngines engines) {
        return new RuleEngineCursorKeyInitializer(engines.cursorKeys());
    }

    @Bean
    @ConditionalOnMissingBean
    RuleEngineLifecycleSweeper ruleEngineLifecycleSweeper(RuleEngines engines, RuleEngineProperties properties) {
        return new RuleEngineLifecycleSweeper(engines.lifecycleSweep()::run,
                properties.lifecycle().sweepIntervalOrDefault());
    }

    @Bean
    @ConditionalOnMissingBean
    RuleEngineCacheCoherence ruleEngineCacheCoherence(DataSource dataSource, RuleEngines engines,
            RuleEngineProperties properties) {
        return new RuleEngineCacheCoherence(new CacheCoherence(dataSource, engines.cache(),
                properties.cache().epochCheckIntervalOrDefault(), properties.cache().listenNotifyOrDefault()));
    }

    /** The compiled-cache meters, bound by the host's meter registry when Micrometer is present (DESIGN 4.8). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.binder.MeterBinder")
    static class CacheMetricsConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "ruleEngineCacheMetrics")
        RuleEngineCacheMetrics ruleEngineCacheMetrics(RuleEngines engines) {
            return new RuleEngineCacheMetrics(engines.cache());
        }
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

    @Bean
    @ConditionalOnMissingBean
    DecisionLogQuery ruleEngineDecisionLogQuery(RuleEngines engines) {
        return engines.decisionLogQuery();
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
        DecisionController ruleEngineDecisionController(RuleEngine engine, DecisionLogQuery log,
                CallerResolver callers) {
            return new DecisionController(engine, log, callers);
        }

        @Bean
        @ConditionalOnMissingBean
        DecisionLogController ruleEngineDecisionLogController(DecisionLogService decisions, CallerResolver callers) {
            return new DecisionLogController(decisions, callers);
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
