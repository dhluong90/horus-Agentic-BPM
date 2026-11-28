package io.github.dhluong90.bpm.process.autoconfigure;

import io.github.dhluong90.bpm.host.autoconfigure.ApproverMustDifferFrom;
import io.github.dhluong90.bpm.host.autoconfigure.RetireApproval;
import io.github.dhluong90.bpm.host.autoconfigure.RetireLastVersion;
import io.github.dhluong90.bpm.host.spi.EngineAccess;
import io.github.dhluong90.bpm.host.spi.ModuleDescriptor;
import io.github.dhluong90.bpm.process.api.DefinitionService;
import io.github.dhluong90.bpm.process.api.MigrationPlan;
import io.github.dhluong90.bpm.process.api.MigrationService;
import io.github.dhluong90.bpm.process.api.IncidentService;
import io.github.dhluong90.bpm.process.api.ProcessEngine;
import io.github.dhluong90.bpm.process.api.ProcessQueryService;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.TaskService;
import io.github.dhluong90.bpm.process.bpmn.subset.BpmnLayoutReader;
import io.github.dhluong90.bpm.process.bpmn.subset.SubsetBpmnReader;
import io.github.dhluong90.bpm.process.bpmn.subset.SubsetBpmnWriter;
import io.github.dhluong90.bpm.process.bpmn.xml.Limits;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorCatalog;
import io.github.dhluong90.bpm.process.internal.expr.V1ExpressionEvaluator;
import io.github.dhluong90.bpm.process.internal.service.DecisionPort;
import io.github.dhluong90.bpm.process.internal.service.EngineDefaults;
import io.github.dhluong90.bpm.process.internal.service.EngineSettings;
import io.github.dhluong90.bpm.process.internal.service.AgentReplays;
import io.github.dhluong90.bpm.process.internal.service.JobExecutor;
import io.github.dhluong90.bpm.process.internal.service.AsyncSettings;
import io.github.dhluong90.bpm.process.internal.service.AsyncStepExecutor;
import io.github.dhluong90.bpm.process.internal.service.AgentStepRunner;
import io.github.dhluong90.bpm.process.internal.service.AsyncStepRunner;
import io.github.dhluong90.bpm.process.internal.service.JsonSchemaSubsetValidator;
import io.github.dhluong90.bpm.process.internal.service.ServiceStepRunner;
import io.github.dhluong90.bpm.process.spi.AgentAdapter;
import io.github.dhluong90.bpm.process.spi.AssigneeDirectory;
import io.github.dhluong90.bpm.process.spi.RoleCatalogue;
import io.github.dhluong90.bpm.process.spi.InputMasker;
import io.github.dhluong90.bpm.process.spi.FormRegistry;
import io.github.dhluong90.bpm.process.spi.OutputSchemaValidator;
import io.github.dhluong90.bpm.process.internal.service.JobSettings;
import io.github.dhluong90.bpm.process.spi.ServiceAdapter;
import io.github.dhluong90.bpm.process.internal.service.LocalServiceInvoker;
import io.github.dhluong90.bpm.process.internal.service.InstanceActivityService;
import io.github.dhluong90.bpm.process.internal.service.LiveNodeCountService;
import io.github.dhluong90.bpm.process.internal.service.Migrations;
import io.github.dhluong90.bpm.process.postgres.JdbcGaugeStore;
import io.github.dhluong90.bpm.process.postgres.JdbcNodeStatsStore;
import io.github.dhluong90.bpm.process.internal.service.NodeWindowStatsService;
import io.github.dhluong90.bpm.process.internal.service.NodeStatsRollup;
import io.github.dhluong90.bpm.process.internal.service.ProcessQueries;
import io.github.dhluong90.bpm.process.internal.service.ProcessServices;
import io.github.dhluong90.bpm.process.postgres.JdbcAgentReplayStore;
import io.github.dhluong90.bpm.process.postgres.JdbcJobStore;
import io.github.dhluong90.bpm.process.postgres.JdbcLayoutStore;
import io.github.dhluong90.bpm.process.postgres.JdbcMigrationStore;
import io.github.dhluong90.bpm.process.postgres.JdbcProcessStore;
import io.github.dhluong90.bpm.process.postgres.JdbcCalendarStore;
import io.github.dhluong90.bpm.process.internal.service.Calendars;
import io.github.dhluong90.bpm.process.api.CalendarService;
import io.github.dhluong90.bpm.process.spi.BusinessCalendar;
import io.github.dhluong90.bpm.process.postgres.ProcessEngineSchemaMigrator;
import io.github.dhluong90.bpm.process.postgres.ProcessEngineTransactions;
import io.github.dhluong90.bpm.process.rest.AgentReplayController;
import io.github.dhluong90.bpm.process.rest.MigrationController;
import io.github.dhluong90.bpm.process.rest.IncidentController;
import io.github.dhluong90.bpm.process.rest.ProcessController;
import io.github.dhluong90.bpm.process.rest.ProcessProblemDetails;
import io.github.dhluong90.bpm.process.spi.CallerResolver;
import io.github.dhluong90.bpm.process.spi.ProcessEventPublisher;
import io.github.dhluong90.bpm.process.spi.ServiceTaskHandler;
import io.github.dhluong90.bpm.process.spi.TenantResolver;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
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
            "processes:approve", "processes:start", "processes:cancel", "processes:task:work",
            "processes:step:complete", "processes:agent:replay", "processes:task:manage", "processes:calendar:manage",
            "processes:migration:manage", "processes:incident:manage", "processes:signal:broadcast",
            "processes:message:correlate");
    /** Setting published in {@code /api/bpm/meta}: the approval a migration plan's execution needs (PD-131). */
    static final String MIGRATION_APPROVAL_SETTING = "migration.approval";
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
    ProcessEngineTransactions processEngineTransactions(ObjectProvider<PlatformTransactionManager> transactionManager,
            DataSource dataSource) {
        return new ProcessEngineTransactions(transactionManager.getIfUnique(() -> {
            throw new IllegalStateException("The Process Engine needs exactly one PlatformTransactionManager from the "
                    + "host. Add spring-boot-starter-jdbc, or mark one transaction manager @Primary.");
        }), org.springframework.jdbc.core.simple.JdbcClient.create(dataSource));
    }

    @Bean
    @ConditionalOnMissingBean
    JdbcProcessStore processEngineStore(DataSource dataSource, ProcessEngineProperties properties) {
        return new JdbcProcessStore(dataSource, properties.idempotency().lockTimeout());
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

    /** Async step settings: lease margin, default timeout and adapter slots (ADR-0008). */
    @Bean
    @ConditionalOnMissingBean
    AsyncSettings processEngineAsyncSettings(ProcessEngineProperties properties) {
        return properties.toAsyncSettings();
    }

    /**
     * The engine's default output schema check of async results, a documented JSON Schema subset that refuses any
     * keyword it does not support (#363). A host may register its own.
     */
    @Bean
    @ConditionalOnMissingBean
    OutputSchemaValidator processEngineOutputSchemaValidator() {
        return new JsonSchemaSubsetValidator();
    }

    /**
     * The default task forms (PRE-031): JSON documents on the classpath under
     * {@code horus.bpm.processes.tasks.form-resource-root}. A host with its own form service registers a
     * {@link FormRegistry} bean.
     */
    @Bean
    @ConditionalOnMissingBean
    FormRegistry processEngineFormRegistry(ProcessEngineProperties properties) {
        return new ClasspathFormRegistry(properties.tasks().formResourceRoot(),
                ProcessEngineAutoConfiguration.class.getClassLoader());
    }

    /**
     * Makes the audit copy of masked agent inputs (#59): with the Rule Engine and its masking key for the tenant, the
     * keyed fingerprint the decision log keeps for the same value ({@code hmac:<keyId>:<hex>}); otherwise
     * {@code ***}. A host may register its own.
     */
    @Bean
    @ConditionalOnMissingBean
    InputMasker processEngineInputMasker(BeanFactory beans) {
        ClassLoader loader = ProcessEngineAutoConfiguration.class.getClassLoader();
        if (!org.springframework.util.ClassUtils.isPresent(RuleEngineDecisions.MASKING_POLICY_CLASS, loader)) {
            return InputMasker.REDACT;
        }
        return RuleEngineDecisions.inputMasker(beans);
    }

    /**
     * Runs async steps outside any transaction, one runner per job kind with its own slots (#363): remote service
     * steps through the host's {@code ServiceAdapter}s, agent steps through its {@code AgentAdapter}s (#52), and the
     * undos of a compensation walk through the same {@code ServiceAdapter}s on their own lane (#67). The pool has as many daemon threads as all runners have
     * slots, so no claimed call waits for a thread; one daemon thread abandons calls at their timeout. Closed with the
     * context.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    AsyncStepExecutor processEngineAsyncStepExecutor(JdbcJobStore jobs, ProcessEngineTransactions transactions,
            ObjectProvider<Clock> clock, JobSettings settings, AsyncSettings async,
            ObjectProvider<ServiceAdapter> adapters, ObjectProvider<AgentAdapter> agents, ProcessServices services) {
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        // #67: the undos of a compensation walk have their own lane, on the same ServiceAdapters.
        List<AsyncStepRunner> runners = List.of(new ServiceStepRunner(adaptersByName(adapters)),
                new AgentStepRunner(agentsByName(agents)),
                new ServiceStepRunner(adaptersByName(adapters), io.github.dhluong90.bpm.process.internal.store.JobStore
                        .COMPENSATION));
        int threads = AsyncStepExecutor.threadsNeeded(async, settings,
                runners.stream().map(AsyncStepRunner::jobKind).toList());
        return new AsyncStepExecutor(jobs, transactions, clock.getIfUnique(Clock::systemUTC), settings, async,
                runners,
                services, java.util.concurrent.Executors.newFixedThreadPool(threads, r -> {
                    Thread t = new Thread(r, "horus-bpm-adapter-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }), java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "horus-bpm-adapter-timeouts");
                    t.setDaemon(true);
                    return t;
                }));
    }

    static Map<String, AgentAdapter> agentsByName(ObjectProvider<AgentAdapter> agents) {
        Map<String, AgentAdapter> byName = new LinkedHashMap<>();
        agents.orderedStream().forEach(a -> {
            if (byName.putIfAbsent(a.name(), a) != null) {
                throw new IllegalStateException("Two AgentAdapter beans are named " + a.name());
            }
        });
        return byName;
    }

    static Map<String, ServiceAdapter> adaptersByName(ObjectProvider<ServiceAdapter> adapters) {
        Map<String, ServiceAdapter> byName = new LinkedHashMap<>();
        adapters.orderedStream().forEach(a -> {
            if (byName.putIfAbsent(a.name(), a) != null) {
                throw new IllegalStateException("Two ServiceAdapter beans are named " + a.name());
            }
        });
        return byName;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "horus.bpm.processes.jobs", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    ProcessJobPoller processEngineJobPoller(JobExecutor executor, AsyncStepExecutor async, AgentReplays replays,
            io.github.dhluong90.bpm.process.internal.service.MigrationRunner migrationRunner,
            ProcessServices services, ProcessEngineProperties properties) {
        // One poll runs the timers, starts the async attempts there are free adapter slots for, replays one page of an
        // offline agent replay (#98), runs up to ten batches of one approved migration (#90) and applies the task
        // escalation levels that have come due (#73).
        return new ProcessJobPoller(() -> executor.pollOnce() + async.pollOnce() + replays.pollOnce()
                + migrationRunner.runDue(MIGRATION_BATCHES_PER_POLL) + services.escalateOverdue(),
                executor.replicaId(),
                properties.jobs().pollInterval(), Clock.systemUTC());
    }

    /** Batches of one migration a poll runs before it gives the plan back, so several plans take turns. */
    static final int MIGRATION_BATCHES_PER_POLL = 10;

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
            JdbcJobStore jobs, JobSettings jobSettings, AsyncSettings async, ObjectProvider<ServiceAdapter> adapters,
            ObjectProvider<AgentAdapter> agents, ObjectProvider<ServiceTaskHandler> handlers, BeanFactory beans,
            ProcessEventPublisher publisher, ProcessEngineProperties properties, Environment environment,
            OutputSchemaValidator outputSchemas, InputMasker inputMasker, BusinessCalendar calendar,
            ObjectProvider<AssigneeDirectory> directory, ObjectProvider<RoleCatalogue> roles,
            JdbcLayoutStore layoutStore, FormRegistry forms,
            ObjectProvider<io.github.dhluong90.bpm.process.spi.MessageAdapter> messageAdapter,
            ObjectProvider<io.github.dhluong90.bpm.process.spi.DataStoreAdapter> dataStores) {
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
        ProcessServices services = new ProcessServices(tenants.getIfUnique(() -> () -> null), callers.getIfUnique(), clock.getIfUnique(Clock::systemUTC),
                transactions, store, Map.of(SourceFormat.BPMN_XML, reader),
                Map.of(SourceFormat.BPMN_XML, new SubsetBpmnWriter()::write),
                new LocalServiceInvoker(byName, decisions(beans), properties.handlers().slowCallWarning()), publisher,
                properties.toSettings(EngineSettings.ApproverRule.valueOf(ApproverMustDifferFrom.from(environment).name()),
                        EngineSettings.RetireLastVersionRule.valueOf(RetireLastVersion.from(environment).name())),
                BehaviorCatalog.registry(),
                new V1ExpressionEvaluator(EngineDefaults.CONDITION_CACHE_ENTRIES), jobs, jobSettings, async,
                adaptersByName(adapters).keySet(), agentsByName(agents).keySet(), outputSchemas, inputMasker,
                properties.agents().labels().toLimits(), properties.agents().memory().toLimits(), calendar);
        // PD-110: reassign and delegate ask the host's directory whether the target holds the role or group.
        services.useAssigneeDirectory(directory.getIfUnique(() -> AssigneeDirectory.NONE));
        // PRE-394: instance and definition lists run in a read-only transaction with a statement timeout.
        services.useReadTimeout(properties.query().statementTimeout());
        // AUD-10: held start arrivals expire and one definition holds a bounded number.
        services.useStartArrivalSettings(properties.parallelStart().toSettings());
        // #491: the host's outbox port for the messages a process sends; none when the host sends no messages.
        services.useMessageAdapter(messageAdapter.getIfUnique());
        // #499: the host's data stores, by the id of the BPMN dataStore each serves; two with one name are a wiring error.
        Map<String, io.github.dhluong90.bpm.process.spi.DataStoreAdapter> stores = new LinkedHashMap<>();
        dataStores.orderedStream().forEach(a -> {
            if (stores.putIfAbsent(a.name(), a) != null) {
                throw new IllegalStateException("Two DataStoreAdapter beans are named " + a.name());
            }
        });
        services.useDataStoreAdapters(stores);
        // PRE-180: approval asks the host's role list whether an escalation target role exists. The default accepts
        // every role; outside the dev profile that is worth a warning, because a misspelt role is then not caught.
        RoleCatalogue catalogue = roles.getIfUnique(() -> RoleCatalogue.ACCEPT_ALL);
        warnWhenRolesAreNotChecked(environment, catalogue);
        services.useRoleCatalogue(catalogue);
        // PRE-031: the host's forms, and where a form link may point.
        services.useFormRegistry(forms, properties.tasks().formLinkOrigins());
        // #92: how deep one call may sit above another.
        services.useCallDepth(properties.calls().maxDepth());
        // #496: most repetitions of one looped activity.
        services.useMaxLoopInstances(properties.multiInstance().max());
        // #445: an imported BPMN file's own diagram is stored as layout revision 1 of the version.
        BpmnLayoutReader layoutReader = new BpmnLayoutReader(reader.limits());
        services.useImportedLayouts(layoutStore, Map.of(SourceFormat.BPMN_XML, layoutReader::read));
        return services;
    }

    @Bean
    @ConditionalOnMissingBean
    JdbcCalendarStore processEngineCalendarStore(DataSource dataSource, ProcessEngineProperties properties) {
        return new JdbcCalendarStore(dataSource, properties.idempotency().lockTimeout());
    }

    /**
     * The stored business calendars and their four-eyes changes (#72, PD-131). The {@code single} approval is
     * refused here, before the bean exists, unless the dev profile is active on its own.
     */
    @Bean
    @ConditionalOnMissingBean
    Calendars processEngineCalendars(ObjectProvider<TenantResolver> tenants, ObjectProvider<CallerResolver> callers,
            ObjectProvider<Clock> clock, ProcessEngineTransactions transactions, JdbcCalendarStore store,
            ProcessEventPublisher publisher, ProcessEngineProperties properties, Environment environment) {
        if (properties.calendar().approval() == ProcessEngineProperties.CalendarApproval.SINGLE) {
            requireDevProfileOnly(environment, "horus.bpm.processes.calendar.approval=single");
            LOG.warn("Business calendar changes apply without a second person (horus.bpm.processes.calendar.approval"
                    + "=single, dev profile). Every change records this.");
        }
        Calendars calendars = new Calendars(tenants.getIfUnique(() -> () -> null), callers.getIfUnique(),
                clock.getIfUnique(Clock::systemUTC), transactions, store, publisher,
                Calendars.Approval.valueOf(properties.calendar().approval().name()), properties.idempotencyRetention(),
                properties.query().maxPageSize(), properties.approval().requireContentHash());
        java.util.Map<String, byte[]> cursorKeys = properties.cursor().decoded();
        if (!cursorKeys.isEmpty()) {
            calendars.useCursorKeys(properties.cursor().activeKeyId(), cursorKeys);
        }
        return calendars;
    }

    /** The step catalogue: what the host registered, for the modeller (#103, PRE-032). */
    @Bean
    @ConditionalOnMissingBean(io.github.dhluong90.bpm.process.api.StepCatalogue.class)
    io.github.dhluong90.bpm.process.api.StepCatalogue processEngineStepCatalogue(
            ObjectProvider<TenantResolver> tenants, ObjectProvider<CallerResolver> callers,
            ObjectProvider<Clock> clock, ProcessEngineTransactions transactions, JdbcCalendarStore store,
            ObjectProvider<ServiceTaskHandler> handlers, ObjectProvider<ServiceAdapter> adapters,
            ObjectProvider<AgentAdapter> agents, ProcessServices services, ObjectProvider<RoleCatalogue> roles) {
        return new io.github.dhluong90.bpm.process.internal.service.StepCatalogues(
                tenants.getIfUnique(() -> () -> null), callers.getIfUnique(), clock.getIfUnique(Clock::systemUTC),
                transactions, store, handlers.orderedStream().toList(), adapters.orderedStream().toList(),
                agents.orderedStream().toList(), services.decisionPort(),
                roles.getIfUnique(() -> RoleCatalogue.ACCEPT_ALL));
    }

    @Bean
    @ConditionalOnMissingBean(CalendarService.class)
    CalendarService processCalendarService(Calendars calendars) {
        return calendars;
    }

    /**
     * Working time for task due times (PRE-008): the stored calendars, unless the host supplies its own
     * {@link BusinessCalendar}.
     */
    @Bean
    @ConditionalOnMissingBean(BusinessCalendar.class)
    BusinessCalendar processEngineBusinessCalendar(Calendars calendars) {
        return calendars.businessCalendar();
    }

    /**
     * Warns, outside the {@code dev} profile, that escalation target roles are not checked at approval because the host
     * registered no {@link RoleCatalogue} (PRE-180).
     *
     * @param environment the environment
     * @param catalogue the catalogue in use
     * @return whether the warning was logged
     */
    static boolean warnWhenRolesAreNotChecked(Environment environment, RoleCatalogue catalogue) {
        if (catalogue != RoleCatalogue.ACCEPT_ALL || environment.matchesProfiles(DEV_PROFILE)) {
            return false;
        }
        LOG.warn("No RoleCatalogue bean is registered, so the roles that overdue tasks escalate to are not checked at "
                + "approval; a misspelt or retired role would escalate to a queue nobody watches. Register a "
                + "RoleCatalogue over your role list (PRE-180).");
        return true;
    }

    /**
     * Every removed key beside the current key that replaced it. A removed key stays refused from every source (property
     * file, command line, environment variable); add the next removal here and it is covered everywhere.
     */
    static final Map<String, String> REMOVED_KEYS = Map.of(REMOVED_AUTO_APPROVE,
            "horus.bpm.processes.definitions.auto-approve");

    /**
     * The removed keys that are set. A key in a property file or on the command line is refused as written. An
     * environment variable is refused when its name is a form of the removed key, such as
     * {@code horus.bpm.processes.definitions-auto-approve} or {@code HORUS.BPM.PROCESSES.DEFINITIONS-AUTO-APPROVE}. The one
     * exception is the name that is also the environment form of the current key: the removed key and its replacement
     * both reduce to {@code HORUS_BPM_PROCESSES_DEFINITIONS_AUTO_APPROVE}, so that exact name is read as the current key.
     *
     * @param environment the environment
     * @return the removed keys found, in the table's order
     */
    static List<String> removedKeysSet(Environment environment) {
        List<String> found = new ArrayList<>();
        for (Map.Entry<String, String> removed : REMOVED_KEYS.entrySet()) {
            if (removedKeySet(environment, removed.getKey(), removed.getValue())) {
                found.add(removed.getKey());
            }
        }
        return found;
    }

    private static boolean removedKeySet(Environment environment, String removed, String current) {
        if (!(environment instanceof ConfigurableEnvironment configurable)) {
            return environment.containsProperty(removed);
        }
        for (PropertySource<?> source : configurable.getPropertySources()) {
            if (source instanceof SystemEnvironmentPropertySource env && env.getSource() instanceof Map<?, ?> vars) {
                String shared = environmentForm(removed);
                boolean collides = shared.equals(environmentForm(current));
                for (Object name : vars.keySet()) {
                    String variable = String.valueOf(name);
                    if (environmentForm(variable).equals(shared) && !(collides && variable.equals(shared))) {
                        return true;
                    }
                }
            } else if (source.containsProperty(removed)) {
                return true;
            }
        }
        return false;
    }

    private static String environmentForm(String name) {
        return name.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }

    /**
     * Refuses, outside the {@code dev} profile, the relaxations of TR-SEC-11, and the removed auto-approve key on any
     * profile (PRE-022).
     *
     * @param environment the environment
     * @param properties the bound settings
     */
    static void requireDevOnlyRelaxations(Environment environment, ProcessEngineProperties properties) {
        List<String> removed = removedKeysSet(environment);
        if (!removed.isEmpty()) {
            throw new IllegalStateException(removed.get(0) + " was removed; use " + REMOVED_KEYS.get(removed.get(0))
                    + ", which is accepted only when the dev profile is active on its own (PRE-022). Remove the old "
                    + "key whatever its value.");
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
        if (properties.calendar().approval() == ProcessEngineProperties.CalendarApproval.SINGLE) {
            // PD-131: one person may change a business calendar only in the dev profile.
            requireDevProfileOnly(environment, "horus.bpm.processes.calendar.approval=single");
        }
        // PD-131: migrating running instances is four-eyes; one person may act alone only on the dev profile.
        if (MigrationPlan.Approval.of(properties.migration().approval()) == MigrationPlan.Approval.SINGLE) {
            requireDevProfileOnly(environment, "horus.bpm.processes.migration.approval=single");
            LOG.warn("Migration plans are executed by one person (horus.bpm.processes.migration.approval=single, dev "
                    + "profile): no second person approves moving running instances.");
        }
        if (!properties.approval().requireContentHash()) {
            // PD-139: an approval that names no reviewed content is a relaxation of maker-checker.
            requireDevProfileOnly(environment, "horus.bpm.processes.approval.require-content-hash=false");
            LOG.warn("Process definition approvals need not name the reviewed content (horus.bpm.processes.approval"
                    + ".require-content-hash=false, dev profile).");
        }
        if (properties.incidents().toSetting() == EngineSettings.ResolveApproval.SINGLE) {
            // PD-126: one person resolving an incident with an output of their choosing is a maker-checker gap.
            requireDevProfileOnly(environment, "horus.bpm.processes.incidents.resolve-approval=single");
            LOG.warn("Incidents are resolved by one person (horus.bpm.processes.incidents.resolve-approval=single, "
                    + "dev profile).");
        }
        if (properties.stats().rollupMinLag().compareTo(
                io.github.dhluong90.bpm.process.internal.service.NodeStatsSettings.DEFAULT_MIN_LAG) < 0) {
            // #410: a shorter write-lag window than the default lets the rollup miss history rows that commit late.
            requireDevProfileOnly(environment, "horus.bpm.processes.stats.rollup-min-lag below "
                    + io.github.dhluong90.bpm.process.internal.service.NodeStatsSettings.DEFAULT_MIN_LAG);
            LOG.warn("The hourly statistics rollup trusts a write-lag window shorter than PT5M (horus.bpm.processes"
                    + ".stats.rollup-min-lag, dev profile): a slow step transaction may be missed.");
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
    JdbcAgentReplayStore processEngineAgentReplayStore(DataSource dataSource) {
        return new JdbcAgentReplayStore(dataSource);
    }

    /**
     * Offline replay of recorded agent answers against a candidate guardrail or output schema (#98): through the
     * engine's own decision port, whose simulation writes nothing, and the result check's schema validator. Calls no
     * adapter.
     */
    @Bean
    @ConditionalOnMissingBean
    AgentReplays processEngineAgentReplays(ObjectProvider<TenantResolver> tenants,
            ObjectProvider<CallerResolver> callers, ObjectProvider<Clock> clock, ProcessEngineTransactions transactions,
            JdbcAgentReplayStore store, ProcessServices services, OutputSchemaValidator outputSchemas,
            ProcessEngineProperties properties) {
        return new AgentReplays(tenants.getIfUnique(() -> () -> null), callers.getIfUnique(),
                clock.getIfUnique(Clock::systemUTC), transactions, store, services.decisionPort(), outputSchemas,
                properties.replay().toSettings(), properties.query().maxRange());
    }

    @Bean
    @ConditionalOnMissingBean
    JdbcMigrationStore processEngineMigrationStore(DataSource dataSource) {
        return new JdbcMigrationStore(dataSource);
    }

    /**
     * Migration plans and their dry run (#74). A plan records the approval its execution needs, four-eyes unless the
     * dev profile allows {@code single} (PD-131); the dry run only reads.
     */
    @Bean
    @ConditionalOnMissingBean(MigrationService.class)
    Migrations processEngineMigrations(ObjectProvider<TenantResolver> tenants,
            ObjectProvider<CallerResolver> callers, ObjectProvider<Clock> clock, ProcessEngineTransactions transactions,
            JdbcProcessStore store, JdbcMigrationStore migrations, ProcessServices services,
            ProcessEventPublisher publisher, ProcessEngineProperties properties) {
        return new Migrations(tenants.getIfUnique(() -> () -> null), callers.getIfUnique(),
                clock.getIfUnique(Clock::systemUTC), transactions, store, migrations, services::storedModel, publisher,
                properties.migration().toSettings().withReasonMaxLength(properties.lifecycle().reasonMaxLength()),
                properties.idempotencyRetention());
    }

    /**
     * Executing a migration plan (#90): propose with a reason, approve as a second person. The proposal and the
     * approval repeat the dry run on the server.
     */
    @Bean
    @ConditionalOnMissingBean(io.github.dhluong90.bpm.process.api.MigrationExecutionService.class)
    io.github.dhluong90.bpm.process.internal.service.MigrationExecutions processEngineMigrationExecutions(
            ObjectProvider<TenantResolver> tenants, ObjectProvider<CallerResolver> callers,
            ObjectProvider<Clock> clock, ProcessEngineTransactions transactions, JdbcProcessStore store,
            JdbcMigrationStore migrationStore, Migrations migrations, ProcessEventPublisher publisher,
            ProcessEngineProperties properties) {
        return new io.github.dhluong90.bpm.process.internal.service.MigrationExecutions(
                tenants.getIfUnique(() -> () -> null), callers.getIfUnique(), clock.getIfUnique(Clock::systemUTC),
                transactions, store, migrationStore, migrations, publisher, properties.migration().toSettings(),
                properties.idempotencyRetention(), properties.approval().requireContentHash());
    }

    /**
     * The runner that moves the instances of an approved migration in batches (#90). It claims a plan under a lease, so
     * one replica works on a plan at a time, and a crashed replica's plan is taken over after the lease. It runs inside
     * the job poller, so {@code jobs.enabled=false} also stops it.
     */
    @Bean
    @ConditionalOnMissingBean
    io.github.dhluong90.bpm.process.internal.service.MigrationRunner processEngineMigrationRunner(
            ObjectProvider<Clock> clock, ProcessEngineTransactions transactions, JdbcProcessStore store,
            JdbcMigrationStore migrationStore, ProcessServices services, ProcessEventPublisher publisher,
            JobExecutor executor, ProcessEngineProperties properties) {
        return new io.github.dhluong90.bpm.process.internal.service.MigrationRunner(executor.replicaId(),
                clock.getIfUnique(Clock::systemUTC), transactions, store, migrationStore, services::storedModel,
                services, publisher, properties.migration().toSettings(),
                io.github.dhluong90.bpm.process.internal.service.MigrationRunner.Checkpoint.NONE);
    }

    @Bean
    @ConditionalOnMissingBean
    JdbcLayoutStore processEngineLayoutStore(DataSource dataSource) {
        return new JdbcLayoutStore(dataSource);
    }

    /** Diagram layout revisions, stored apart from the definition and its hash (#102). */
    @Bean
    @ConditionalOnMissingBean(io.github.dhluong90.bpm.process.api.LayoutService.class)
    io.github.dhluong90.bpm.process.api.LayoutService processEngineLayouts(ObjectProvider<TenantResolver> tenants,
            ObjectProvider<CallerResolver> callers, ObjectProvider<Clock> clock, ProcessEngineTransactions transactions,
            JdbcProcessStore store, JdbcLayoutStore layouts, ProcessServices services,
            ProcessEventPublisher publisher, ProcessEngineProperties properties) {
        return new io.github.dhluong90.bpm.process.internal.service.Layouts(tenants.getIfUnique(() -> () -> null),
                callers.getIfUnique(), clock.getIfUnique(Clock::systemUTC), transactions, store, layouts,
                services::storedModel, publisher, properties.idempotencyRetention());
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
    @ConditionalOnMissingBean
    InstanceActivityService processEngineInstanceActivities(ObjectProvider<TenantResolver> tenants,
            ObjectProvider<CallerResolver> callers, ProcessEngineTransactions transactions, JdbcProcessStore store,
            ProcessEngineProperties properties) {
        return new InstanceActivityService(tenants.getIfUnique(() -> () -> null), callers.getIfUnique(), transactions,
                store, store, properties.query().maxPageSize());
    }

    @Bean
    @ConditionalOnMissingBean(TaskService.class)
    TaskService processTaskService(ProcessServices services) {
        return services;
    }

    @Bean
    @ConditionalOnMissingBean(IncidentService.class)
    IncidentService processIncidentService(ProcessServices services) {
        return services;
    }

    @Bean
    @ConditionalOnMissingBean(io.github.dhluong90.bpm.process.api.SignalService.class)
    io.github.dhluong90.bpm.process.api.SignalService processSignalService(ProcessServices services) {
        return services;
    }

    @Bean
    @ConditionalOnMissingBean(ProcessQueryService.class)
    ProcessQueryService processQueryService(ProcessServices services, LiveNodeCountService liveCounts,
            NodeWindowStatsService windowStats, InstanceActivityService activities, JdbcProcessStore store,
            ObjectProvider<io.github.dhluong90.bpm.process.internal.service.PendingApprovalSource> pendingSources) {
        // #473: migration plans and calendar changes show in the pending approvals list when those features run.
        return new ProcessQueries(services, liveCounts, windowStats, activities, store)
                .withPendingSources(pendingSources.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    JdbcNodeStatsStore processEngineNodeStatsStore(DataSource dataSource) {
        return new JdbcNodeStatsStore(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    NodeWindowStatsService processEngineNodeWindowStats(ObjectProvider<TenantResolver> tenants,
            ObjectProvider<CallerResolver> callers, ObjectProvider<Clock> clock, ProcessEngineTransactions transactions,
            JdbcProcessStore store, JdbcNodeStatsStore stats, ProcessEngineProperties properties) {
        return new NodeWindowStatsService(tenants.getIfUnique(() -> () -> null), callers.getIfUnique(),
                clock.getIfUnique(Clock::systemUTC), transactions, store, stats, properties.query().maxRange());
    }

    /** The hourly rollup, one writer per slice across replicas (PRE-029). */
    @Bean
    @ConditionalOnMissingBean
    NodeStatsRollup processEngineNodeStatsRollup(ObjectProvider<Clock> clock, ProcessEngineTransactions transactions,
            JdbcNodeStatsStore stats, JobExecutor executor, ProcessEngineProperties properties) {
        return new NodeStatsRollup(clock.getIfUnique(Clock::systemUTC), transactions, stats,
                properties.stats().toRollup(), executor.replicaId());
    }

    @Bean
    @ConditionalOnMissingBean
    JdbcGaugeStore processEngineGaugeStore(DataSource dataSource) {
        return new JdbcGaugeStore(dataSource);
    }

    /**
     * The meters of PRE-084, bound to the host's registry when Micrometer is present. The engine hands its
     * measurements to this bean after commit.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.binder.MeterBinder")
    @ConditionalOnProperty(prefix = "horus.bpm.processes.metrics", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    static class ProcessEngineMetricsConfiguration {

        @Bean
        @ConditionalOnMissingBean(ProcessEngineMetrics.class)
        ProcessEngineMetrics processEngineMetrics(JdbcGaugeStore gauges, ObjectProvider<Clock> clock,
                ProcessServices services, ProcessEngineProperties properties) {
            ProcessEngineMetrics metrics = new ProcessEngineMetrics(gauges, clock.getIfUnique(Clock::systemUTC),
                    properties.metrics().definitionKeyTag());
            services.useMetrics(metrics);
            return metrics;
        }

        /** The gauges are read on {@code metrics.gauge-refresh}, once per replica, never per scrape. */
        @Bean
        ProcessMaintenanceScheduler.Task processEngineGaugeRefresh(ProcessEngineMetrics metrics,
                ProcessEngineProperties properties) {
            return new ProcessMaintenanceScheduler.Task("gauge-refresh", properties.metrics().gaugeRefresh(),
                    metrics::refreshGauges);
        }
    }

    /**
     * One span per step in the host's trace (#114, TR-OBS-03), bound only when the host has a Micrometer
     * {@code Tracer}. Without one the engine does nothing extra and needs no tracing dependency.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.tracing.Tracer")
    static class ProcessEngineTracingConfiguration {

        /** Binds after every singleton exists, so the host's tracer is found whatever the configuration order. */
        @Bean
        @ConditionalOnMissingBean(name = "processEngineTracing")
        org.springframework.beans.factory.SmartInitializingSingleton processEngineTracing(ProcessServices services,
                ObjectProvider<io.micrometer.tracing.Tracer> tracer,
                org.springframework.core.env.Environment environment) {
            return () -> {
                io.micrometer.tracing.Tracer bound = tracer.getIfAvailable();
                if (bound != null) {
                    services.useTracing(new ProcessEngineTracing(bound,
                            environment.getProperty("horus.bpm.tracing.tenant-attribute", Boolean.class, true)));
                }
            };
        }
    }

    /**
     * Periodic background work: the rollup on {@code stats.rollup-interval}, and any task another bean adds, such as
     * the gauge refresh of the metrics. This method names no Micrometer type, so a host without Micrometer loads it.
     */
    @Bean
    @ConditionalOnMissingBean
    ProcessMaintenanceScheduler processEngineMaintenance(ObjectProvider<Clock> clock, NodeStatsRollup rollup,
            ProcessServices services,
            ObjectProvider<ProcessMaintenanceScheduler.Task> tasks, ProcessEngineProperties properties) {
        ProcessMaintenanceScheduler scheduler = new ProcessMaintenanceScheduler(clock.getIfUnique(Clock::systemUTC));
        if (properties.stats().rollupEnabled()) {
            scheduler.add("node-stats-rollup", properties.stats().rollupInterval(), rollup::run);
        }
        scheduler.add("start-arrival-cleanup", properties.parallelStart().cleanupInterval(),
                services::purgeExpiredStartArrivals);
        tasks.orderedStream().forEach(scheduler::add);
        return scheduler;
    }

    @Bean
    @ConditionalOnMissingBean
    ProcessEngineInitializer processEngineInitializer(ProcessEngineSchemaMigrator migrator, ProcessServices services,
            ProcessEngineProperties properties, ResourceLoader resources, Environment environment) {
        // PD-146: the one start-up summary of unclassified agent step fields is logged outside the dev profile only
        return new ProcessEngineInitializer(migrator, services, properties, resources,
                !environment.matchesProfiles("dev"));
    }

    @Bean
    @ConditionalOnMissingBean(name = "processEngineModuleDescriptor")
    ModuleDescriptor processEngineModuleDescriptor(ProcessEngineProperties properties, Environment environment) {
        return ModuleDescriptor.builder("processes", ProcessEngineModule.engineVersion(), "v1")
                .settings(Map.of(ApproverMustDifferFrom.META_SETTING, ApproverMustDifferFrom.from(environment).value(),
                        RetireApproval.META_SETTING, RetireApproval.from(environment).value(),
                        RetireLastVersion.META_SETTING, RetireLastVersion.from(environment).value(),
                        "approval.require-content-hash", properties.approval().requireContentHash(),
                        "incidents.resolve-approval", properties.incidents().resolveApproval(),
                        MIGRATION_APPROVAL_SETTING, properties.migration().approval()))
                .permissions(PERMISSIONS)
                .rolePermissions(properties.security().rolePermissions())
                .build();
    }

    /**
     * Default caller and tenant from the host contract's {@code EngineAccess} (bpm-host-metadata); a host bean of
     * the same type replaces each. Without a host contract every call is refused as unauthenticated.
     */
    @Bean
    @ConditionalOnMissingBean
    CallerResolver processEngineCallerResolver(ObjectProvider<EngineAccess> access,
            ObjectProvider<io.github.dhluong90.bpm.host.spi.DisplayNames> names) {
        return EngineAccessResolvers.callers(access::getIfAvailable, names::getIfAvailable);
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
        AgentReplayController processEngineAgentReplayController(AgentReplays replays) {
            return new AgentReplayController(replays);
        }

        @Bean
        @ConditionalOnMissingBean
        MigrationController processEngineMigrationController(MigrationService migrations) {
            return new MigrationController(migrations);
        }

        @Bean
        @ConditionalOnMissingBean
        io.github.dhluong90.bpm.process.rest.MigrationExecutionController processEngineMigrationExecutionController(
                io.github.dhluong90.bpm.process.api.MigrationExecutionService executions) {
            return new io.github.dhluong90.bpm.process.rest.MigrationExecutionController(executions);
        }

        @Bean
        @ConditionalOnMissingBean
        io.github.dhluong90.bpm.process.rest.LayoutController processEngineLayoutController(
                io.github.dhluong90.bpm.process.api.LayoutService layouts) {
            return new io.github.dhluong90.bpm.process.rest.LayoutController(layouts);
        }

        @Bean
        @ConditionalOnMissingBean
        io.github.dhluong90.bpm.process.rest.EventScopeFilter processEngineEventScopeFilter() {
            return new io.github.dhluong90.bpm.process.rest.EventScopeFilter();
        }

        @Bean
        @ConditionalOnMissingBean
        IncidentController processEngineIncidentController(IncidentService incidents) {
            return new IncidentController(incidents);
        }

        @Bean
        @ConditionalOnMissingBean
        io.github.dhluong90.bpm.process.rest.SignalController processEngineSignalController(
                io.github.dhluong90.bpm.process.api.SignalService signals) {
            return new io.github.dhluong90.bpm.process.rest.SignalController(signals);
        }

        @Bean
        @ConditionalOnMissingBean
        io.github.dhluong90.bpm.process.rest.CalendarController processEngineCalendarController(
                CalendarService calendars) {
            return new io.github.dhluong90.bpm.process.rest.CalendarController(calendars);
        }

        @Bean
        @ConditionalOnMissingBean
        io.github.dhluong90.bpm.process.rest.StepCatalogueController processEngineStepCatalogueController(
                io.github.dhluong90.bpm.process.api.StepCatalogue catalogue) {
            return new io.github.dhluong90.bpm.process.rest.StepCatalogueController(catalogue);
        }

        @Bean
        @ConditionalOnMissingBean
        ProcessProblemDetails processEngineProblemDetails() {
            return new ProcessProblemDetails();
        }
    }
}
