package io.github.dhluong90.bpm.rules.autoconfigure;

import io.github.dhluong90.bpm.host.spi.EngineAccess;
import io.github.dhluong90.bpm.host.spi.ModuleTenantSettings;
import io.github.dhluong90.bpm.rules.api.DecisionLogQuery;
import io.github.dhluong90.bpm.rules.api.DecisionLogService;
import io.github.dhluong90.bpm.rules.api.RuleEngine;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.VersionWarning;
import io.github.dhluong90.bpm.rules.embedded.EmbeddedEngineGrant;
import io.github.dhluong90.bpm.rules.internal.service.EmbeddedEngineGrants;
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
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

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

    /**
     * The internals, as a bean of a package-private type: not {@link RuleEngines}, so host code has no bean from which
     * to reach the tenant-scoped entry point (#336).
     */
    @Bean
    @ConditionalOnMissingBean
    RuleEngineInternals ruleEngineInternals(DataSource dataSource, RuleEngineTransactions transactions, TenantResolver tenants,
            CallerResolver callers, ObjectProvider<Clock> clock, RuleEngineProperties properties,
            MaskingPolicy masking, Environment environment) {
        EngineSettings settings = properties.toSettings(environment);
        warnIfApprovalsSkipPersonalDataAcknowledgement(settings);
        return new RuleEngineInternals(PostgresRuleEngines.create(dataSource, transactions, tenants, callers,
                RuleEngineAutoConfiguration.engineClock(clock), settings, masking,
                properties.toQuerySettings()));
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
    RuleEngineMaskingCheck ruleEngineMaskingCheck(RuleEngineInternals internals, MaskingPolicy masking,
            ObjectProvider<Clock> clock) {
        return RuleEngineMaskingCheck.of(internals.engines().maskingCheck(), masking,
                RuleEngineAutoConfiguration.engineClock(clock));
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
    DecisionLogService ruleEngineDecisionLogService(RuleEngineInternals internals) {
        return internals.engines().decisionLog();
    }

    @Bean
    @ConditionalOnMissingBean
    RuleEngineCursorKeyInitializer ruleEngineCursorKeyInitializer(RuleEngineInternals internals) {
        return new RuleEngineCursorKeyInitializer(internals.engines().cursorKeys());
    }

    @Bean
    @ConditionalOnMissingBean
    RuleEngineLifecycleSweeper ruleEngineLifecycleSweeper(RuleEngineInternals internals,
            RuleEngineProperties properties) {
        return new RuleEngineLifecycleSweeper(internals.engines().lifecycleSweep()::run,
                properties.lifecycle().sweepIntervalOrDefault());
    }

    @Bean
    @ConditionalOnMissingBean
    RuleEngineCacheCoherence ruleEngineCacheCoherence(DataSource dataSource, RuleEngineInternals internals,
            RuleEngineProperties properties) {
        return new RuleEngineCacheCoherence(new CacheCoherence(dataSource, internals.engines().cache(),
                properties.cache().epochCheckIntervalOrDefault(), properties.cache().listenNotifyOrDefault()));
    }

    /** The compiled-cache, evaluation and retirement meters, bound by the host's meter registry when Micrometer is present (DESIGN 4.8). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.binder.MeterBinder")
    static class CacheMetricsConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "ruleEngineCacheMetrics")
        RuleEngineCacheMetrics ruleEngineCacheMetrics(RuleEngineInternals internals) {
            return new RuleEngineCacheMetrics(internals.engines().cache());
        }

        /** The evaluation and retirement meters (DESIGN 4.8, TR-OBS-02). */
        @Bean
        @ConditionalOnMissingBean(name = "ruleEngineEvaluationMetrics")
        RuleEngineEvaluationMetrics ruleEngineEvaluationMetrics(RuleEngineInternals internals) {
            return new RuleEngineEvaluationMetrics(internals.engines().meters());
        }
    }

    /**
     * The one-time hand-off of the tenant-scoped entry point to the Process Engine (ADR-0027, #336). Registered only
     * when the process starter's decision port, its one registered claimant, resolves to a class in a sealed package of a
     * jar with this Rule Engine's {@code Implementation-Version} ({@link EmbeddedEngineClaimantCondition}). Otherwise
     * there is no grant, and the host keeps running without it. The Process Engine claims it while its beans are
     * created; when start-up finishes, any identity still unclaimed is closed for good. A host that injects it is
     * refused.
     */
    @Configuration(proxyBeanMethods = false)
    @Conditional(EmbeddedEngineClaimantCondition.class)
    static class EmbeddedEngineGrantConfiguration {

        @Bean
        EmbeddedEngineGrant ruleEngineEmbeddedEngineGrant(RuleEngineInternals internals) {
            return internals.engines().openEmbeddedEngineGrant();
        }

        /** Runs after every singleton, the Process Engine's included, has been created. */
        @Bean
        SmartInitializingSingleton ruleEngineEmbeddedEngineGrantCloser(EmbeddedEngineGrant grant) {
            return grant::closeUnclaimed;
        }
    }

    /**
     * Matches when the registered claimant of {@code system:process-engine} would be accepted. A claimant class that is
     * present but refused (unsealed package, class directory, another version) is logged, and no grant is registered.
     */
    static final class EmbeddedEngineClaimantCondition extends SpringBootCondition {

        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Optional<String> refusal = EmbeddedEngineGrants.registeredClaimantRefusal(EMBEDDED_ENGINE_ID);
            if (refusal.isEmpty()) {
                return ConditionOutcome.match("the decision port is sealed and carries the Rule Engine's version");
            }
            if (!refusal.get().endsWith("is not on the class path")) {
                LOG.warn("No tenant-scoped entry point is granted to the Process Engine: " + refusal.get()
                        + ". Decision steps of processes fail closed (#336).");
            }
            return ConditionOutcome.noMatch(refusal.get());
        }
    }

    /**
     * The engine identity whose registered claimant is the process starter's decision port; see
     * {@link EmbeddedEngineGrant#REGISTERED_CLAIMANTS}.
     */
    static final String EMBEDDED_ENGINE_ID = "system:process-engine";

    @Bean
    @ConditionalOnMissingBean
    RuleEngine ruleEngine(RuleEngineInternals internals) {
        return internals.engines().ruleEngine();
    }

    @Bean
    @ConditionalOnMissingBean
    VersionService ruleEngineVersionService(RuleEngineInternals internals) {
        return internals.engines().versionService();
    }

    @Bean
    @ConditionalOnMissingBean
    DecisionLogQuery ruleEngineDecisionLogQuery(RuleEngineInternals internals) {
        return internals.engines().decisionLogQuery();
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
