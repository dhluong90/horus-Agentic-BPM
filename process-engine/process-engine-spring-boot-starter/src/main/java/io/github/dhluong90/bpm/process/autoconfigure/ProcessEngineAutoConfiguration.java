package io.github.dhluong90.bpm.process.autoconfigure;

import io.github.dhluong90.bpm.host.autoconfigure.ApproverMustDifferFrom;
import io.github.dhluong90.bpm.host.autoconfigure.RetireApproval;
import io.github.dhluong90.bpm.host.autoconfigure.RetireLastVersion;
import io.github.dhluong90.bpm.host.spi.EngineAccess;
import io.github.dhluong90.bpm.host.spi.ModuleDescriptor;
import io.github.dhluong90.bpm.process.api.DefinitionService;
import io.github.dhluong90.bpm.process.api.ProcessEngine;
import io.github.dhluong90.bpm.process.api.ProcessQueryService;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.TaskService;
import io.github.dhluong90.bpm.process.bpmn.subset.SubsetBpmnReader;
import io.github.dhluong90.bpm.process.bpmn.subset.SubsetBpmnWriter;
import io.github.dhluong90.bpm.process.bpmn.xml.Limits;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorCatalog;
import io.github.dhluong90.bpm.process.internal.expr.V1ExpressionEvaluator;
import io.github.dhluong90.bpm.process.internal.service.DecisionPort;
import io.github.dhluong90.bpm.process.internal.service.EngineDefaults;
import io.github.dhluong90.bpm.process.internal.service.EngineSettings;
import io.github.dhluong90.bpm.process.internal.service.JobExecutor;
import io.github.dhluong90.bpm.process.internal.service.JobSettings;
import io.github.dhluong90.bpm.process.internal.service.LocalServiceInvoker;
import io.github.dhluong90.bpm.process.internal.service.LiveNodeCountService;
import io.github.dhluong90.bpm.process.internal.service.ProcessQueries;
import io.github.dhluong90.bpm.process.internal.service.ProcessServices;
import io.github.dhluong90.bpm.process.postgres.JdbcJobStore;
import io.github.dhluong90.bpm.process.postgres.JdbcProcessStore;
import io.github.dhluong90.bpm.process.postgres.ProcessEngineSchemaMigrator;
import io.github.dhluong90.bpm.process.postgres.ProcessEngineTransactions;
import io.github.dhluong90.bpm.process.rest.ProcessController;
import io.github.dhluong90.bpm.process.rest.ProcessProblemDetails;
import io.github.dhluong90.bpm.process.spi.CallerResolver;
import io.github.dhluong90.bpm.process.spi.ProcessEventPublisher;
import io.github.dhluong90.bpm.process.spi.ServiceTaskHandler;
import io.github.dhluong90.bpm.process.spi.TenantResolver;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Auto-configures the Process Engine on the host's {@link DataSource} and {@link PlatformTransactionManager}. Every
 * extension point has a default that a host bean of the same type replaces (ADR-0024). No component scan.
 */
@AutoConfiguration(afterName = {
    "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
    "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
    "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
    "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
    "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
    "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
    "io.github.dhluong90.bpm.rules.autoconfigure.RuleEngineAutoConfiguration"
}, beforeName = {
    "io.github.dhluong90.bpm.host.autoconfigure.EngineAccessAutoConfiguration",
    "io.github.dhluong90.bpm.host.autoconfigure.HostMetadataAutoConfiguration"
})
@ConditionalOnSingleCandidate(DataSource.class)
@ConditionalOnProperty(prefix = "horus.bpm.processes", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ProcessEngineProperties.class)
public class ProcessEngineAutoConfiguration {

    static final String DEV_PROFILE = "dev";
    static final List<String> PERMISSIONS = List.of("processes:read", "processes:deploy", "processes:author",
            "processes:approve", "processes:start", "processes:cancel", "processes:task:work");
    /** Removed property; set at all, it fails start-up naming its replacement (PRE-022 e). */
    static final String REMOVED_AUTO_APPROVE = "horus.bpm.processes.definitions-auto-approve";
    private static final Log LOG = LogFactory.getLog(ProcessEngineAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    ProcessEngineSchemaMigrator processEngineSchemaMigrator(DataSource dataSource) {
        return new ProcessEngineSchemaMigrator(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    ProcessEngineTransactions processEngineTransactions(ObjectProvider<PlatformTransactionManager> transactionManager) {
        return new ProcessEngineTransactions(transactionManager.getIfUnique(() -> {
            throw new IllegalStateException("The Process Engine needs exactly one PlatformTransactionManager from the "
                    + "host. Add spring-boot-starter-jdbc, or mark one transaction manager @Primary.");
        }));
    }

    @Bean
    @ConditionalOnMissingBean
    JdbcProcessStore processEngineStore(DataSource dataSource) {
        return new JdbcProcessStore(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    JdbcJobStore processEngineJobStore(DataSource dataSource) {
        return new JdbcJobStore(dataSource);
    }

    /** This replica's job settings; the replica id defaults to the host name plus a random suffix. */
    @Bean
    @ConditionalOnMissingBean
    JobSettings processEngineJobSettings(ProcessEngineProperties properties) {
        return properties.jobs().toSettings(defaultReplicaId());
    }

    static String defaultReplicaId() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.io.IOException | SecurityException e) {
            host = "replica";
        }
        String suffix = Long.toString(java.util.concurrent.ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE, 36);
        String id = host + "-" + suffix;
        return id.length() > 200 ? id.substring(id.length() - 200) : id;
    }

    @Bean
    @ConditionalOnMissingBean
    JobExecutor processEngineJobExecutor(JdbcJobStore jobs, ProcessEngineTransactions transactions,
            ObjectProvider<Clock> clock, JobSettings settings, ProcessServices services) {
        return new JobExecutor(jobs, transactions, clock.getIfUnique(Clock::systemUTC), settings, services.jobHandlers());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "horus.bpm.processes.jobs", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    ProcessJobPoller processEngineJobPoller(JobExecutor executor, ProcessEngineProperties properties) {
        return new ProcessJobPoller(executor::pollOnce, executor.replicaId(), properties.jobs().pollInterval(),
                Clock.systemUTC());
    }

    /** Bean name of the job poller health contributor on both Spring Boot lines ({@code processEngineJobs}). */
    static final String JOBS_HEALTH_BEAN = "processEngineJobsHealthIndicator";

    /** Spring Boot 4 hosts with the health module: job poller health. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.health.contributor.HealthIndicator")
    static class JobsHealthConfiguration {

        @Bean(JOBS_HEALTH_BEAN)
        @ConditionalOnMissingBean(name = JOBS_HEALTH_BEAN)
        @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(ProcessJobPoller.class)
        org.springframework.boot.health.contributor.HealthIndicator processEngineJobsHealthIndicator(
                ProcessJobPoller poller) {
            return new ProcessJobsHealthIndicator(poller);
        }
    }

    /** Spring Boot 3.5 hosts with Actuator, only when the Spring Boot 4 health module is absent. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass(
            "org.springframework.boot.health.contributor.HealthIndicator")
    static class JobsSpringBoot35HealthConfiguration {

        @Bean(JOBS_HEALTH_BEAN)
        @ConditionalOnMissingBean(name = JOBS_HEALTH_BEAN)
        @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(ProcessJobPoller.class)
        org.springframework.boot.actuate.health.HealthIndicator processEngineJobsHealthIndicator(
                ProcessJobPoller poller) {
            return new ProcessJobsSpringBoot35HealthIndicator(poller);
        }
    }

    @Bean
    @ConditionalOnMissingBean
    ProcessEventPublisher processEngineEventPublisher(ApplicationEventPublisher publisher) {
        return publisher::publishEvent;
    }

    /**
     * The engine's services. Never lazy: the decision port inside claims the Rule Engine's tenant-scoped entry point
     * while beans are created, before the Rule Engine starter closes unclaimed identities (#336). The claim and the
     * start-up check that it happened are in {@link RuleEngineDecisions}, the only class here that names Rule Engine
     * types.
     */
    @Bean
    @Lazy(false)
    @ConditionalOnMissingBean
    ProcessServices processEngineServices(ObjectProvider<TenantResolver> tenants, ObjectProvider<CallerResolver> callers,
            ObjectProvider<Clock> clock, ProcessEngineTransactions transactions, JdbcProcessStore store,
            JdbcJobStore jobs, JobSettings jobSettings, ObjectProvider<ServiceTaskHandler> handlers, BeanFactory beans,
            ProcessEventPublisher publisher, ProcessEngineProperties properties, Environment environment) {
        requireDevOnlyRelaxations(environment, properties);
        if (properties.definitions().autoApprove()) {
            LOG.warn("Process definitions are imported as ACTIVE without approval "
                    + "(horus.bpm.processes.definitions.auto-approve, dev profile).");
        }
        Map<String, ServiceTaskHandler> byName = new LinkedHashMap<>();
        handlers.orderedStream().forEach(h -> {
            if (byName.putIfAbsent(h.name(), h) != null) {
                throw new IllegalStateException("Two ServiceTaskHandler beans are named " + h.name());
            }
        });
        SubsetBpmnReader reader = new SubsetBpmnReader(new Limits(properties.definitions().maxBytes(),
                Limits.DEFAULTS.maxDepth(), Limits.DEFAULTS.maxElements(), Limits.DEFAULTS.maxForeignFragmentBytes(),
                Limits.DEFAULTS.maxTextChars(), Limits.DEFAULTS.maxAttributeChars()));
        return new ProcessServices(tenants.getIfUnique(() -> () -> null), callers.getIfUnique(), clock.getIfUnique(Clock::systemUTC),
                transactions, store, Map.of(SourceFormat.BPMN_XML, reader::read),
                Map.of(SourceFormat.BPMN_XML, new SubsetBpmnWriter()::write),
                new LocalServiceInvoker(byName, decisions(beans), properties.handlers().slowCallWarning()), publisher,
                properties.toSettings(EngineSettings.ApproverRule.valueOf(ApproverMustDifferFrom.from(environment).name()),
                        EngineSettings.RetireLastVersionRule.valueOf(RetireLastVersion.from(environment).name())),
                BehaviorCatalog.registry(),
                new V1ExpressionEvaluator(EngineDefaults.CONDITION_CACHE_ENTRIES), jobs, jobSettings);
    }

    /**
     * Refuses, outside the {@code dev} profile, the relaxations of TR-SEC-11, and the removed auto-approve key on any
     * profile (PRE-022).
     *
     * @param environment the environment
     * @param properties the bound settings
     */
    static void requireDevOnlyRelaxations(Environment environment, ProcessEngineProperties properties) {
        if (environment.containsProperty(REMOVED_AUTO_APPROVE)) {
            throw new IllegalStateException(REMOVED_AUTO_APPROVE + " was removed; use "
                    + "horus.bpm.processes.definitions.auto-approve, which is accepted only when the dev profile is "
                    + "active on its own (PRE-022). Remove the old key whatever its value.");
        }
        if (properties.definitions().autoApprove()) {
            requireDevProfileOnly(environment, "horus.bpm.processes.definitions.auto-approve");
        }
        // PD-102 and PD-101: the four-eyes retirement modes are not built for process definitions in this release.
        if (RetireApproval.from(environment) == RetireApproval.FOUR_EYES) {
            throw new IllegalStateException(RetireApproval.PROPERTY + "=four-eyes is not available for process "
                    + "definitions in this release; use single (the default), which needs processes:approve and a "
                    + "reason (PD-102)");
        }
        if (RetireLastVersion.from(environment) == RetireLastVersion.FOUR_EYES) {
            throw new IllegalStateException(RetireLastVersion.PROPERTY + "=four-eyes is not available for process "
                    + "definitions in this release; use confirm (the default) or refuse (PD-101)");
        }
        if (ApproverMustDifferFrom.from(environment) == ApproverMustDifferFrom.REQUESTER) {
            LOG.warn(ApproverMustDifferFrom.PROPERTY + "=requester relaxes four eyes for process definitions: the "
                    + "importer of a version may approve it (PD-096). Every approval records this.");
        }
        if (!properties.makerChecker().enabled()) {
            requireDevProfileOnly(environment, "horus.bpm.processes.maker-checker.enabled=false");
            LOG.warn("Maker-checker for process definitions is off (horus.bpm.processes.maker-checker.enabled, dev "
                    + "profile): a requester may approve their own version.");
        }
    }

    /**
     * The decision port over the Rule Engine when it is on the class path and the host has a {@code RuleEngine}
     * (ADR-0027); empty otherwise, and a definition with a business rule task is then refused at activation. The Rule
     * Engine dependency is optional: {@link RuleEngineDecisions} is loaded only once its class is known to exist.
     */
    private static Optional<DecisionPort> decisions(BeanFactory beans) {
        ClassLoader loader = ProcessEngineAutoConfiguration.class.getClassLoader();
        if (!org.springframework.util.ClassUtils.isPresent(RuleEngineDecisions.RULE_ENGINE_CLASS, loader)) {
            return Optional.empty();
        }
        return RuleEngineDecisions.from(beans);
    }

    /**
     * A dev-only relaxation is accepted only when {@code dev} is an explicitly active profile and every other active
     * profile is {@code test}: {@code prod,dev} is refused, and so is {@code spring.profiles.default=dev} with no
     * active profile (PRE-022).
     *
     * @param environment the environment
     * @param property the relaxing property, named in the refusal
     */
    static void requireDevProfileOnly(Environment environment, String property) {
        List<String> active = List.of(environment.getActiveProfiles());
        boolean ok = active.contains(DEV_PROFILE) && active.stream().allMatch(p -> p.equals(DEV_PROFILE) || p.equals("test"));
        if (!ok) {
            throw new IllegalStateException(property + " relaxes a control and is accepted only when the dev profile is "
                    + "active on its own (optionally with test); active profiles are " + active + " (PRE-022).");
        }
    }

    @Bean
    @ConditionalOnMissingBean(DefinitionService.class)
    DefinitionService processDefinitionService(ProcessServices services) {
        return services;
    }

    @Bean
    @ConditionalOnMissingBean(ProcessEngine.class)
    ProcessEngine processEngine(ProcessServices services) {
        return services;
    }

    @Bean
    @ConditionalOnMissingBean
    LiveNodeCountService processEngineLiveNodeCounts(ObjectProvider<TenantResolver> tenants,
            ObjectProvider<CallerResolver> callers, ObjectProvider<Clock> clock, ProcessEngineTransactions transactions,
            JdbcProcessStore store, ProcessEngineProperties properties) {
        return new LiveNodeCountService(tenants.getIfUnique(() -> () -> null), callers.getIfUnique(),
                clock.getIfUnique(Clock::systemUTC), transactions, store, properties.stats().toSettings());
    }

    @Bean
    @ConditionalOnMissingBean(TaskService.class)
    TaskService processTaskService(ProcessServices services) {
        return services;
    }

    @Bean
    @ConditionalOnMissingBean(ProcessQueryService.class)
    ProcessQueryService processQueryService(ProcessServices services, LiveNodeCountService liveCounts) {
        return new ProcessQueries(services, liveCounts);
    }

    @Bean
    @ConditionalOnMissingBean
    ProcessEngineInitializer processEngineInitializer(ProcessEngineSchemaMigrator migrator, ProcessServices services,
            ProcessEngineProperties properties, ResourceLoader resources) {
        return new ProcessEngineInitializer(migrator, services, properties, resources);
    }

    @Bean
    @ConditionalOnMissingBean(name = "processEngineModuleDescriptor")
    ModuleDescriptor processEngineModuleDescriptor(ProcessEngineProperties properties, Environment environment) {
        return new ModuleDescriptor("processes", ProcessEngineModule.engineVersion(), "v1", List.of(),
                Map.of(ApproverMustDifferFrom.META_SETTING, ApproverMustDifferFrom.from(environment).value(),
                        RetireApproval.META_SETTING, RetireApproval.from(environment).value(),
                        RetireLastVersion.META_SETTING, RetireLastVersion.from(environment).value()),
                PERMISSIONS, properties.security().rolePermissions());
    }

    /**
     * Default caller and tenant from the host contract's {@code EngineAccess} (bpm-host-metadata); a host bean of
     * the same type replaces each. Without a host contract every call is refused as unauthenticated.
     */
    @Bean
    @ConditionalOnMissingBean
    CallerResolver processEngineCallerResolver(ObjectProvider<EngineAccess> access) {
        return EngineAccessResolvers.callers(access::getIfAvailable);
    }

    @Bean
    @ConditionalOnMissingBean
    TenantResolver processEngineTenantResolver(ObjectProvider<EngineAccess> access) {
        return EngineAccessResolvers.tenants(access::getIfAvailable);
    }

    /** REST endpoints in a servlet host. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
    static class RestConfiguration {

        @Bean
        @ConditionalOnMissingBean
        ProcessController processEngineController(DefinitionService definitions, ProcessEngine engine, TaskService tasks,
                ProcessQueryService queries, ProcessEngineProperties properties) {
            return new ProcessController(definitions, engine, tasks, queries, properties.definitions().maxBytes());
        }

        @Bean
        @ConditionalOnMissingBean
        ProcessProblemDetails processEngineProblemDetails() {
            return new ProcessProblemDetails();
        }
    }
}
