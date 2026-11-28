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
import io.github.dhluong90.bpm.expr.spi.ExpressionFunction;
import io.github.dhluong90.bpm.rules.internal.service.EngineSettings;
import io.github.dhluong90.bpm.rules.internal.service.HostFunctions;
import io.github.dhluong90.bpm.rules.spi.RuleFunctionRegistry;
import io.github.dhluong90.bpm.rules.internal.service.RuleEngines;
import io.github.dhluong90.bpm.rules.api.RuleSetHistoryService;
import io.github.dhluong90.bpm.rules.api.RuleSetTemplateService;
import io.github.dhluong90.bpm.rules.api.SimulationReportService;
import io.github.dhluong90.bpm.rules.internal.service.DefaultRuleSetTemplateService;
import io.github.dhluong90.bpm.rules.internal.service.RuleSetTemplates;
import io.github.dhluong90.bpm.rules.rest.RuleSetTemplateController;
import io.github.dhluong90.bpm.rules.rest.SimulationReportController;
import io.github.dhluong90.bpm.rules.rest.RuleSetHistoryController;
import io.github.dhluong90.bpm.rules.spi.DecisionExplainer;
import io.github.dhluong90.bpm.rules.postgres.CacheCoherence;
import io.github.dhluong90.bpm.rules.postgres.PostgresRuleEngines;
import io.github.dhluong90.bpm.rules.postgres.RuleEngineTransactions;
import io.github.dhluong90.bpm.rules.rest.DecisionController;
import io.github.dhluong90.bpm.rules.rest.DecisionLogController;
import io.github.dhluong90.bpm.rules.rest.ProblemDetails;
import io.github.dhluong90.bpm.rules.rest.ApprovalController;
import io.github.dhluong90.bpm.rules.rest.RuleSetController;
import io.github.dhluong90.bpm.rules.rest.SimulationController;
import io.github.dhluong90.bpm.rules.spi.ApprovalSealKey;
import io.github.dhluong90.bpm.rules.spi.CallerResolver;
import io.github.dhluong90.bpm.rules.spi.MaskingPolicy;
import io.github.dhluong90.bpm.rules.spi.TenantResolver;
import java.time.Clock;
import java.util.Map;
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
            MaskingPolicy masking, Environment environment, DecisionExplainer explainer,
            RuleFunctionRegistry functions, ObjectProvider<ApprovalSealKey> sealKey,
            ObjectProvider<io.github.dhluong90.bpm.rules.postgres.AsyncDecisionLog> asyncLog) {
        EngineSettings settings = properties.toSettings(environment);
        warnIfApprovalsSkipPersonalDataAcknowledgement(settings);
        requireDevProfileForUnboundApprovals(settings, environment);
        warnIfApprovalsAreNotBoundToContent(settings);
        ApprovalSealKey seal = sealKey.getIfAvailable(ApprovalSealKey::none);
        if (seal.current().isPresent() && seal.acceptUnkeyedSeals()) {
            org.apache.commons.logging.LogFactory.getLog(RuleEngineServicesConfiguration.class).warn(
                    "Approval seal window open: acceptUnkeyedSeals is on, so plain and pre-seal seals are accepted"
                            + " next to the key. Re-seal the approved versions and turn it off.");
        }
        try {
            return new RuleEngineInternals(PostgresRuleEngines.create(dataSource, transactions, tenants, callers,
                    RuleEngineAutoConfiguration.engineClock(clock), settings, masking,
                    properties.toQuerySettings(), explainer, functions.functions(),
                    HostFunctions.VersionChange.parse(properties.evaluation().functionVersionChange()),
                    // RLE-223: a host bean supplies the key from outside the database; without one, plain seals.
                    seal,
                    // RLE-230: the queue when decision-log.mode is async; otherwise the transactional log.
                    asyncLog.getIfAvailable() != null
                            ? asyncLog.getIfAvailable()
                            : new io.github.dhluong90.bpm.rules.postgres.JdbcDecisionLog(dataSource)));
        } catch (IllegalArgumentException e) {
            // A function registered twice, with a built-in name or an invalid name (RLE-058): nothing is served.
            throw new IllegalStateException("The Rule Engine does not start: " + e.getMessage(), e);
        }
    }

    /**
     * The host's functions for rule cells (RLE-055): every {@link ExpressionFunction} bean. A host bean of this type
     * replaces it. Functions are registered per host, never per tenant, and must be pure calculations (PD-119).
     */
    @Bean
    @ConditionalOnMissingBean
    RuleFunctionRegistry ruleEngineFunctionRegistry(ObjectProvider<ExpressionFunction> functions) {
        return () -> functions.orderedStream().toList();
    }

    /** Checks the host functions against the versions published rule sets were approved with (RLE-058). */
    @Bean
    @ConditionalOnMissingBean
    RuleEngineFunctionCheck ruleEngineFunctionCheck(RuleEngineInternals internals) {
        return RuleEngineFunctionCheck.of(internals.engines().functionCheck());
    }

    /** The function version setting and the functions whose version changed, in the rules settings of {@code /meta}. */
    @Bean
    @ConditionalOnMissingBean(name = "ruleEngineFunctionSettings")
    ModuleTenantSettings ruleEngineFunctionSettings(RuleEngineFunctionCheck check, RuleEngineProperties properties) {
        return check.tenantSettings(HostFunctions.VersionChange.parse(properties.evaluation().functionVersionChange()));
    }

    /**
     * The renderer of decision explanations (RLE-030, PD-027): the engine's English and Vietnamese catalogues with the
     * host's {@code horus-bpm/rules/explain_<language>.properties} laid over them; a host bean replaces it.
     */
    @Bean
    @ConditionalOnMissingBean
    DecisionExplainer ruleEngineDecisionExplainer(RuleEngineProperties properties,
            org.springframework.core.io.ResourceLoader resources) {
        return RuleEngineExplainCatalogues.explainer(properties.explain().defaultLanguageOrDefault(),
                resources.getClassLoader());
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

    /**
     * {@code approval.require-content-hash=false} is accepted only when {@code dev} is an active profile on its own,
     * optionally with {@code test} (PD-139): any other profile stops start-up.
     *
     * @param settings the validated settings
     * @param environment the environment
     */
    static void requireDevProfileForUnboundApprovals(EngineSettings settings, Environment environment) {
        if (settings.requireContentHash()) {
            return;
        }
        java.util.List<String> active = java.util.List.of(environment.getActiveProfiles());
        boolean ok = active.contains("dev") && active.stream().allMatch(p -> p.equals("dev") || p.equals("test"));
        if (!ok) {
            throw new IllegalStateException("horus.bpm.rules.approval.require-content-hash=false relaxes a control and"
                    + " is accepted only when the dev profile is active on its own (optionally with test); active"
                    + " profiles are " + active + " (PD-139, RLE-084).");
        }
    }

    /**
     * Turning {@code approval.require-content-hash} off relaxes a control (RLE-084): allowed, but never silently.
     *
     * @param settings the validated settings
     * @return whether the warning was logged
     */
    static boolean warnIfApprovalsAreNotBoundToContent(EngineSettings settings) {
        if (settings.requireContentHash()) {
            return false;
        }
        LOG.warn("horus.bpm.rules.approval.require-content-hash is false: an approval or rejection that does not name"
                + " expectedCanonicalHash is applied to whatever content is stored at that lock version, not the"
                + " content the reviewer saw (RLE-084). The setting is shown in /api/bpm/meta.");
        return true;
    }

    @Bean
    @ConditionalOnMissingBean
    RuleEngineMaskingCheck ruleEngineMaskingCheck(RuleEngineInternals internals, MaskingPolicy masking,
            ObjectProvider<Clock> clock, org.springframework.core.env.Environment environment) {
        // PD-146: the one start-up summary of unclassified inputs is logged outside the dev profile only
        return RuleEngineMaskingCheck.of(internals.engines().maskingCheck(), masking,
                RuleEngineAutoConfiguration.engineClock(clock), !environment.matchesProfiles("dev"));
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

    /**
     * Warns once at start-up when no cursor key is configured, because the engine then signs list cursors with a key
     * kept in the database (AUD-13).
     *
     * @param log where the warning goes
     * @param configured the configured keys, empty when none
     */
    static void warnWhenNoCursorKeys(org.apache.commons.logging.Log log, Map<String, byte[]> configured) {
        if (configured.isEmpty()) {
            log.warn("No cursor keys configured (horus.bpm.rules.cursor.keys and cursor.active-key-id): "
                    + "list cursors are signed with a key stored in rle_engine_key, which anyone who can read the "
                    + "database can use. Set the keys from a secret for production.");
        }
    }

    @Bean
    @ConditionalOnMissingBean
    RuleEngineCursorKeyInitializer ruleEngineCursorKeyInitializer(RuleEngineInternals internals,
            RuleEngineProperties properties) {
        // AUD-13: keys from configuration or a secret keep the signing key out of the database.
        Map<String, byte[]> configured = properties.cursor().decoded();
        if (!configured.isEmpty()) {
            internals.engines().cursorKeys().configure(properties.cursor().activeKeyId(), configured);
        }
        warnWhenNoCursorKeys(org.apache.commons.logging.LogFactory.getLog(RuleEngineServicesConfiguration.class),
                configured);
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

    @Bean
    @ConditionalOnMissingBean
    RuleSetHistoryService ruleEngineRuleSetHistoryService(RuleEngineInternals internals) {
        return internals.engines().ruleSetHistory();
    }

    @Bean
    @ConditionalOnMissingBean
    SimulationReportService ruleEngineSimulationReportService(RuleEngineInternals internals) {
        return internals.engines().simulationReports();
    }

    /**
     * The rule set templates (PD-112, RLE-197, RLE-198): the four built-in ones the host has not hidden, then the files
     * in {@code horus.bpm.rules.templates.location}. Read once here, so a template file that is not valid stops the
     * start and names itself; a host bean replaces it.
     */
    @Bean
    @ConditionalOnMissingBean
    RuleSetTemplateService ruleEngineRuleSetTemplateService(RuleEngineProperties properties, CallerResolver callers) {
        RuleEngineProperties.Templates templates = properties.templates();
        try {
            return new DefaultRuleSetTemplateService(callers, RuleSetTemplates.of(
                    TemplateFolder.read(templates.location()), java.util.Set.copyOf(templates.hidden())));
        } catch (IllegalArgumentException | java.io.UncheckedIOException e) {
            throw new IllegalStateException("The Rule Engine does not start: " + e.getMessage(), e);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.security.core.context.SecurityContextHolder")
    static class SecurityCallerConfiguration {

        @Bean
        @ConditionalOnMissingBean
        CallerResolver ruleEngineCallerResolver(ObjectProvider<EngineAccess> access,
                ObjectProvider<io.github.dhluong90.bpm.host.spi.DisplayNames> names) {
            return EngineAccessResolvers.callers(access::getIfAvailable, names::getIfAvailable);
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
        ApprovalController ruleEngineApprovalController(VersionService versions, CallerResolver callers) {
            return new ApprovalController(versions, callers);
        }

        @Bean
        @ConditionalOnMissingBean
        DecisionController ruleEngineDecisionController(RuleEngine engine, DecisionLogQuery log,
                CallerResolver callers) {
            return new DecisionController(engine, log, callers);
        }

        @Bean
        @ConditionalOnMissingBean
        DecisionLogController ruleEngineDecisionLogController(DecisionLogService decisions,
                io.github.dhluong90.bpm.rules.api.DecisionLogQuery log, CallerResolver callers) {
            return new DecisionLogController(decisions, log, callers);
        }

        @Bean
        @ConditionalOnMissingBean
        RuleSetHistoryController ruleEngineRuleSetHistoryController(RuleSetHistoryService history,
                CallerResolver callers) {
            return new RuleSetHistoryController(history, callers);
        }

        @Bean
        @ConditionalOnMissingBean
        SimulationController ruleEngineSimulationController(RuleEngine engine, VersionService versions,
                CallerResolver callers) {
            return new SimulationController(engine, versions, callers);
        }

        @Bean
        @ConditionalOnMissingBean
        SimulationReportController ruleEngineSimulationReportController(SimulationReportService reports,
                CallerResolver callers) {
            return new SimulationReportController(reports, callers);
        }

        @Bean
        @ConditionalOnMissingBean
        RuleSetTemplateController ruleEngineRuleSetTemplateController(RuleSetTemplateService templates,
                CallerResolver callers) {
            return new RuleSetTemplateController(templates, callers);
        }

        @Bean
        @ConditionalOnMissingBean
        ProblemDetails ruleEngineProblemDetails() {
            return new ProblemDetails();
        }
    }
}
