package io.github.dhluong90.bpm.process.postgres;

import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.bpmn.subset.SubsetBpmnReader;
import io.github.dhluong90.bpm.process.bpmn.xml.Limits;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorCatalog;
import io.github.dhluong90.bpm.process.internal.expr.V1ExpressionEvaluator;
import io.github.dhluong90.bpm.process.internal.service.EngineDefaults;
import io.github.dhluong90.bpm.process.internal.service.EngineSettings;
import io.github.dhluong90.bpm.process.internal.service.JobExecutor;
import io.github.dhluong90.bpm.process.internal.service.JobSettings;
import io.github.dhluong90.bpm.process.internal.service.LocalServiceInvoker;
import io.github.dhluong90.bpm.process.internal.service.ProcessServices;
import io.github.dhluong90.bpm.process.spi.CallerResolver;
import io.github.dhluong90.bpm.process.spi.ProcessEventPublisher;
import io.github.dhluong90.bpm.process.spi.ServiceTaskHandler;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One copy of a host, for tests: the engine services and a job executor on a shared PostgreSQL database, with its
 * own replica id, as a separate JVM would have. Every replica of a test shares one {@link TestClock}.
 */
final class TestEngine {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(TestImages.postgres());
    private static volatile DataSource dataSource;

    /** The caller and tenant of this replica's calls; tests change them between calls. */
    static final class Identity implements CallerResolver {
        volatile String tenant = "bank-a";
        volatile String caller = "pipeline";
        volatile Set<String> permissions = Set.of("processes:deploy", "processes:start", "processes:read",
                "processes:task:work");
        volatile Set<String> roles = Set.of();
        volatile Set<String> groups = Set.of();
        /** The host directory's names of users, as a host's {@code DisplayNames} would give them (#442). */
        volatile java.util.Map<String, String> names = java.util.Map.of();

        @Override
        public java.util.Optional<String> displayNameOf(String userId) {
            return java.util.Optional.ofNullable(names.get(userId));
        }

        @Override
        public Set<String> currentRoles() {
            return roles;
        }

        @Override
        public Set<String> currentGroups() {
            return groups;
        }

        @Override
        public String currentCallerId() {
            return caller;
        }

        @Override
        public Set<String> currentPermissions() {
            return permissions;
        }

        Identity as(String tenantId, String callerId, String... perms) {
            tenant = tenantId;
            caller = callerId;
            permissions = Set.of(perms);
            roles = Set.of();
            groups = Set.of();
            return this;
        }
    }

    /** The Rule Engine's view of the security context, which tests set to whatever the thread would hold. */
    static final class RuleIdentity implements io.github.dhluong90.bpm.rules.spi.TenantResolver,
            io.github.dhluong90.bpm.rules.spi.CallerResolver {
        volatile String tenant;
        volatile String caller;

        @Override
        public String currentTenantId() {
            return tenant;
        }

        @Override
        public String currentCallerId() {
            return caller;
        }

        @Override
        public Set<String> currentPermissions() {
            return caller == null ? Set.of() : Set.of("rules:author", "rules:approve", "rules:evaluate", "rules:read");
        }

        RuleIdentity as(String tenantId, String callerId) {
            tenant = tenantId;
            caller = callerId;
            return this;
        }
    }

    final ProcessServices services;
    /** The stored business calendars of this replica (#72), whose working time sets task due times. */
    final io.github.dhluong90.bpm.process.internal.service.Calendars calendars;
    /** The task list and the other read models of this replica (PRE-030). */
    final io.github.dhluong90.bpm.process.api.ProcessQueryService queries;
    final JobExecutor jobs;
    /** Async step executor of this replica; its pools are closed by {@link #close()}. */
    final io.github.dhluong90.bpm.process.internal.service.AsyncStepExecutor async;
    final Identity identity = new Identity();
    final RuleIdentity ruleIdentity = new RuleIdentity();
    /** The Rule Engine of this replica on the same database and transaction manager, or null without one. */
    final io.github.dhluong90.bpm.rules.internal.service.RuleEngines rules;
    final List<ProcessEventPublisher.Event> events = new CopyOnWriteArrayList<>();
    /** The decision port the engine of this replica calls, or empty without a Rule Engine. */
    final Optional<io.github.dhluong90.bpm.process.internal.service.DecisionPort> decisions;
    private final DataSource replicaDataSource;
    private final ProcessEngineTransactions replicaTransactions;
    private final TestClock replicaClock;
    private final io.github.dhluong90.bpm.process.spi.OutputSchemaValidator replicaSchemas;
    private final ProcessEventPublisher replicaPublisher;

    private TestEngine(Builder b) {
        DataSource ds = b.dataSource != null ? b.dataSource : dataSource();
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(ds);
        ProcessEngineTransactions tx = new ProcessEngineTransactions(transactionManager);
        this.replicaDataSource = ds;
        this.replicaTransactions = tx;
        this.replicaClock = b.clock;
        this.replicaSchemas = b.outputSchemas;
        JdbcJobStore jobStore = new JdbcJobStore(ds);
        SubsetBpmnReader reader = new SubsetBpmnReader(Limits.DEFAULTS);
        JobSettings jobSettings = JobSettings.defaults(b.replicaId).withTimerLease(b.lease);
        jobSettings = new JobSettings(jobSettings.replicaId(), jobSettings.timerLease(), b.batchSize,
                jobSettings.maxAttempts(), b.retryBackoff);
        EngineSettings settings = new EngineSettings(b.autoApprove, EngineDefaults.MAX_NODES, EngineDefaults.MAX_BYTES,
                EngineDefaults.IDEMPOTENCY_RETENTION, EngineDefaults.SLOW_CALL_WARNING, EngineDefaults.MAX_PAGE_SIZE,
                EngineDefaults.MAX_RANGE, EngineDefaults.MAX_REASON_LENGTH, b.makerChecker, b.approverRule,
                b.retireLastVersion, b.requireContentHash, b.resolveApproval, b.warnUnclassified);
        Identity id = identity;
        ProcessEventPublisher publisher = b.publisher != null ? b.publisher : events::add;
        this.replicaPublisher = publisher;
        if (b.withRuleEngine) {
            migrateRuleEngine(ds, transactionManager, b.clock.instant());
            this.rules = io.github.dhluong90.bpm.rules.postgres.PostgresRuleEngines.create(ds,
                    new io.github.dhluong90.bpm.rules.postgres.RuleEngineTransactions(transactionManager), ruleIdentity,
                    ruleIdentity, b.clock, io.github.dhluong90.bpm.rules.internal.service.EngineSettings.defaults().withRequireContentHash(false));
        } else {
            this.rules = null;
        }
        // The decision port is the process starter's, supplied by its tests: only that class may claim the Rule
        // Engine's tenant-scoped entry point (ADR-0027, #336). Without one, a business rule task is not executable.
        this.decisions = b.decisionPort == null ? Optional.empty() : Optional.of(b.decisionPort.apply(rules));
        this.calendars = new io.github.dhluong90.bpm.process.internal.service.Calendars(() -> id.tenant, id, b.clock,
                tx, new JdbcCalendarStore(ds), publisher, b.calendarApproval);
        JdbcProcessStore processStore = new JdbcProcessStore(ds);
        this.services = new ProcessServices(() -> id.tenant, id, b.clock, tx, processStore,
                Map.of(SourceFormat.BPMN_XML, reader),
                Map.of(SourceFormat.BPMN_XML, new io.github.dhluong90.bpm.process.bpmn.subset.SubsetBpmnWriter()::write),
                new LocalServiceInvoker(b.handlers.stream().collect(Collectors.toMap(ServiceTaskHandler::name, h -> h)),
                        decisions, Duration.ofSeconds(5)),
                publisher, settings, BehaviorCatalog.registry(), new V1ExpressionEvaluator(100), jobStore, jobSettings,
                b.asyncSettings, java.util.stream.Stream.concat(b.adapters.stream()
                        .map(io.github.dhluong90.bpm.process.spi.ServiceAdapter::name), b.extraAdapterNames.stream())
                        .collect(Collectors.toSet()), b.agents.stream()
                        .map(io.github.dhluong90.bpm.process.spi.AgentAdapter::name).collect(Collectors.toSet()),
                b.outputSchemas, b.inputMasker, b.labelLimits, b.memoryLimits, calendars.businessCalendar());
        if (b.roleCatalogue != null) {
            services.useRoleCatalogue(b.roleCatalogue);
        }
        if (b.forms != null) {
            services.useFormRegistry(b.forms, b.formLinkOrigins);
        }
        services.useMessageAdapter(b.messageAdapter);
        Map<String, io.github.dhluong90.bpm.process.spi.DataStoreAdapter> stores = new java.util.LinkedHashMap<>();
        b.dataStores.forEach(a -> stores.put(a.name(), a));
        services.useDataStoreAdapters(stores);
        if (b.importedLayouts) {
            // #445: an imported BPMN file's own diagram becomes layout revision 1, as in the starter.
            this.services.useImportedLayouts(new JdbcLayoutStore(ds), Map.of(SourceFormat.BPMN_XML,
                    new io.github.dhluong90.bpm.process.bpmn.subset.BpmnLayoutReader(Limits.DEFAULTS)::read));
        }
        this.queries = new io.github.dhluong90.bpm.process.internal.service.ProcessQueries(services,
                new io.github.dhluong90.bpm.process.internal.service.LiveNodeCountService(() -> id.tenant, id, b.clock,
                        tx, processStore, io.github.dhluong90.bpm.process.internal.service.LiveStatsSettings.defaults()),
                new io.github.dhluong90.bpm.process.internal.service.NodeWindowStatsService(() -> id.tenant, id, b.clock,
                        tx, processStore, new JdbcNodeStatsStore(ds), Duration.ofDays(31)),
                new io.github.dhluong90.bpm.process.internal.service.InstanceActivityService(() -> id.tenant, id, tx,
                        processStore, processStore, 200),
                processStore);
        this.jobs = new JobExecutor(jobStore, tx, b.clock, jobSettings, services.jobHandlers());
        List<io.github.dhluong90.bpm.process.internal.service.AsyncStepRunner> runners = new java.util.ArrayList<>();
        runners.add(new io.github.dhluong90.bpm.process.internal.service.ServiceStepRunner(b.adapters.stream()
                .collect(Collectors.toMap(io.github.dhluong90.bpm.process.spi.ServiceAdapter::name, a -> a))));
        if (b.runners.stream().noneMatch(r -> io.github.dhluong90.bpm.process.internal.store.JobStore.COMPENSATION
                .equals(r.jobKind()))) {
            // #67: the undos of a compensation walk, on the same adapters.
            runners.add(new io.github.dhluong90.bpm.process.internal.service.ServiceStepRunner(b.adapters.stream()
                    .collect(Collectors.toMap(io.github.dhluong90.bpm.process.spi.ServiceAdapter::name, a -> a)),
                    io.github.dhluong90.bpm.process.internal.store.JobStore.COMPENSATION));
        }
        if (!b.agents.isEmpty()) {
            runners.add(new io.github.dhluong90.bpm.process.internal.service.AgentStepRunner(b.agents.stream()
                    .collect(Collectors.toMap(io.github.dhluong90.bpm.process.spi.AgentAdapter::name, a -> a))));
        }
        runners.addAll(b.runners);
        this.async = new io.github.dhluong90.bpm.process.internal.service.AsyncStepExecutor(jobStore, tx, b.clock,
                jobSettings, b.asyncSettings, runners, services,
                java.util.concurrent.Executors.newFixedThreadPool(
                        io.github.dhluong90.bpm.process.internal.service.AsyncStepExecutor.threadsNeeded(
                                b.asyncSettings, jobSettings, runners.stream().map(
                                        io.github.dhluong90.bpm.process.internal.service.AsyncStepRunner::jobKind)
                                        .toList())),
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor());
    }

    /**
     * The offline agent replay of this replica (#98), over the same decision port, transactions and clock.
     *
     * @param settings replay settings
     * @return the replay service; its worker is {@code pollOnce()}
     */
    io.github.dhluong90.bpm.process.internal.service.AgentReplays agentReplays(
            io.github.dhluong90.bpm.process.internal.service.ReplaySettings settings) {
        Identity id = identity;
        return new io.github.dhluong90.bpm.process.internal.service.AgentReplays(() -> id.tenant, id, replicaClock,
                replicaTransactions, new JdbcAgentReplayStore(replicaDataSource), decisions, replicaSchemas, settings,
                io.github.dhluong90.bpm.process.internal.service.EngineDefaults.MAX_RANGE);
    }

    /**
     * The migration plans of this replica (#74), on the same store, transactions, clock and event publisher.
     *
     * @param settings migration settings
     * @return the migration service
     */
    io.github.dhluong90.bpm.process.internal.service.Migrations migrations(
            io.github.dhluong90.bpm.process.internal.service.MigrationSettings settings) {
        Identity id = identity;
        return new io.github.dhluong90.bpm.process.internal.service.Migrations(() -> id.tenant, id, replicaClock,
                replicaTransactions, new JdbcProcessStore(replicaDataSource), new JdbcMigrationStore(replicaDataSource),
                services::storedModel, replicaPublisher, settings, EngineDefaults.IDEMPOTENCY_RETENTION);
    }

    /**
     * The diagram layout service of this replica (#102), on the same store, transactions, clock and event publisher.
     *
     * @return the layout service
     */
    io.github.dhluong90.bpm.process.internal.service.Layouts layouts() {
        Identity id = identity;
        return new io.github.dhluong90.bpm.process.internal.service.Layouts(() -> id.tenant, id, replicaClock,
                replicaTransactions, new JdbcProcessStore(replicaDataSource), new JdbcLayoutStore(replicaDataSource),
                services::storedModel, replicaPublisher, EngineDefaults.IDEMPOTENCY_RETENTION);
    }

    /**
     * The proposal and approval of migration executions of this replica (#90).
     *
     * @param settings migration settings
     * @return the execution service
     */
    io.github.dhluong90.bpm.process.internal.service.MigrationExecutions migrationExecutions(
            io.github.dhluong90.bpm.process.internal.service.MigrationSettings settings) {
        Identity id = identity;
        JdbcProcessStore processStore = new JdbcProcessStore(replicaDataSource);
        JdbcMigrationStore migrationStore = new JdbcMigrationStore(replicaDataSource);
        io.github.dhluong90.bpm.process.internal.service.Migrations plans =
                new io.github.dhluong90.bpm.process.internal.service.Migrations(() -> id.tenant, id, replicaClock,
                        replicaTransactions, processStore, migrationStore, services::storedModel, replicaPublisher,
                        settings, EngineDefaults.IDEMPOTENCY_RETENTION);
        return new io.github.dhluong90.bpm.process.internal.service.MigrationExecutions(() -> id.tenant, id,
                replicaClock, replicaTransactions, processStore, migrationStore, plans, replicaPublisher, settings,
                EngineDefaults.IDEMPOTENCY_RETENTION);
    }

    /**
     * The migration runner of this replica (#90), which holds plans under this replica's id.
     *
     * @param settings migration settings
     * @param owner the lease holder, normally the replica id
     * @param checkpoint a hook after each instance, which tests use to stop the runner as a crash would
     * @return the runner
     */
    io.github.dhluong90.bpm.process.internal.service.MigrationRunner migrationRunner(
            io.github.dhluong90.bpm.process.internal.service.MigrationSettings settings, String owner,
            io.github.dhluong90.bpm.process.internal.service.MigrationRunner.Checkpoint checkpoint) {
        return new io.github.dhluong90.bpm.process.internal.service.MigrationRunner(owner, replicaClock,
                replicaTransactions, new JdbcProcessStore(replicaDataSource),
                new JdbcMigrationStore(replicaDataSource), services::storedModel, services, replicaPublisher, settings,
                checkpoint);
    }

    /** Stops this replica's adapter executor. */
    void close() {
        async.close();
    }

    static Builder replica(String replicaId, TestClock clock) {
        return new Builder(replicaId, clock);
    }

    static final class Builder {
        private final String replicaId;
        private final TestClock clock;
        private Duration lease = Duration.ofSeconds(60);
        private int batchSize = 32;
        private Duration retryBackoff = Duration.ofSeconds(10);
        private boolean autoApprove = true;
        private boolean makerChecker = true;
        private boolean requireContentHash = true;
        private boolean warnUnclassified = true;
        private EngineSettings.ResolveApproval resolveApproval = EngineSettings.ResolveApproval.FOUR_EYES;
        private EngineSettings.ApproverRule approverRule = EngineSettings.ApproverRule.MAKERS;
        private EngineSettings.RetireLastVersionRule retireLastVersion = EngineSettings.RetireLastVersionRule.CONFIRM;
        private List<ServiceTaskHandler> handlers = List.of();
        private ProcessEventPublisher publisher;
        private boolean withRuleEngine;
        private java.util.function.Function<io.github.dhluong90.bpm.rules.internal.service.RuleEngines,
                io.github.dhluong90.bpm.process.internal.service.DecisionPort> decisionPort;
        private List<io.github.dhluong90.bpm.process.spi.ServiceAdapter> adapters = List.of();
        private List<io.github.dhluong90.bpm.process.internal.service.AsyncStepRunner> runners = List.of();
        private List<io.github.dhluong90.bpm.process.spi.AgentAdapter> agents = List.of();
        private java.util.Set<String> extraAdapterNames = java.util.Set.of();
        private io.github.dhluong90.bpm.process.internal.service.AsyncSettings asyncSettings =
                io.github.dhluong90.bpm.process.internal.service.AsyncSettings.DEFAULTS;
        private DataSource dataSource;
        private io.github.dhluong90.bpm.process.spi.RoleCatalogue roleCatalogue;
        private io.github.dhluong90.bpm.process.spi.FormRegistry forms;
        private List<String> formLinkOrigins = List.of();
        private boolean importedLayouts;
        private io.github.dhluong90.bpm.process.spi.MessageAdapter messageAdapter;
        private final java.util.List<io.github.dhluong90.bpm.process.spi.DataStoreAdapter> dataStores =
                new java.util.ArrayList<>();
        private io.github.dhluong90.bpm.process.internal.service.Calendars.Approval calendarApproval =
                io.github.dhluong90.bpm.process.internal.service.Calendars.Approval.FOUR_EYES;
        private io.github.dhluong90.bpm.process.spi.OutputSchemaValidator outputSchemas =
                new io.github.dhluong90.bpm.process.internal.service.JsonSchemaSubsetValidator();
        private io.github.dhluong90.bpm.process.spi.InputMasker inputMasker =
                io.github.dhluong90.bpm.process.spi.InputMasker.REDACT;
        private io.github.dhluong90.bpm.process.internal.service.AgentLabelLimits labelLimits =
                io.github.dhluong90.bpm.process.internal.service.AgentLabelLimits.DEFAULTS;
        private io.github.dhluong90.bpm.process.internal.service.AgentMemoryLimits memoryLimits =
                io.github.dhluong90.bpm.process.internal.service.AgentMemoryLimits.DEFAULTS;

        private Builder(String replicaId, TestClock clock) {
            this.replicaId = replicaId;
            this.clock = clock;
        }

        /** The host's role list that approval asks about escalation targets (PRE-180); none by default. */
        Builder roleCatalogue(io.github.dhluong90.bpm.process.spi.RoleCatalogue value) {
            this.roleCatalogue = value;
            return this;
        }

        /** The host's task forms and the origins a form link may point to besides relative links (PRE-031). */
        Builder forms(io.github.dhluong90.bpm.process.spi.FormRegistry registry, String... linkOrigins) {
            this.forms = registry;
            this.formLinkOrigins = List.of(linkOrigins);
            return this;
        }

        /** Stores the diagram of an imported BPMN file as layout revision 1, as the starter does (#445). */
        Builder importedLayouts() {
            importedLayouts = true;
            return this;
        }

        Builder lease(Duration value) {
            lease = value;
            return this;
        }

        Builder batchSize(int value) {
            batchSize = value;
            return this;
        }

        Builder retryBackoff(Duration value) {
            retryBackoff = value;
            return this;
        }

        Builder autoApprove(boolean value) {
            autoApprove = value;
            return this;
        }

        Builder retireLastVersion(EngineSettings.RetireLastVersionRule value) {
            retireLastVersion = value;
            return this;
        }

        Builder approverRule(EngineSettings.ApproverRule value) {
            approverRule = value;
            return this;
        }

        Builder makerChecker(boolean value) {
            makerChecker = value;
            return this;
        }

        /** {@code approval.require-content-hash} (PD-139); {@code false} is the dev-profile relaxation. */
        Builder requireContentHash(boolean value) {
            requireContentHash = value;
            return this;
        }

        Builder warnUnclassified(boolean value) {
            warnUnclassified = value;
            return this;
        }

        /** {@code incidents.resolve-approval} (PD-126); {@code SINGLE} is the dev-profile relaxation. */
        Builder resolveApproval(EngineSettings.ResolveApproval value) {
            resolveApproval = value;
            return this;
        }

        Builder handlers(ServiceTaskHandler... value) {
            handlers = List.of(value);
            return this;
        }

        Builder adapters(io.github.dhluong90.bpm.process.spi.ServiceAdapter... value) {
            adapters = List.of(value);
            return this;
        }

        /**
         * Adds runners of other async kinds, for example a stub agent runner that answers a rewritten job.
         *
         * @param adapterNames the adapter names those runners call, which completer roles may name
         * @param value the runners
         */
        Builder runners(java.util.Set<String> adapterNames,
                io.github.dhluong90.bpm.process.internal.service.AsyncStepRunner... value) {
            runners = List.of(value);
            extraAdapterNames = java.util.Set.copyOf(adapterNames);
            return this;
        }

        /** The host's agent adapters, run by the engine's agent runner (#52). */
        Builder agents(io.github.dhluong90.bpm.process.spi.AgentAdapter... value) {
            agents = List.of(value);
            return this;
        }

        Builder async(io.github.dhluong90.bpm.process.internal.service.AsyncSettings value) {
            asyncSettings = value;
            return this;
        }

        /** Uses this data source (already migrated through {@link TestEngine#dataSource()}) instead of the shared one. */
        Builder dataSource(DataSource value) {
            dataSource = value;
            return this;
        }

        /** Who applies a calendar change: four eyes by default, single as the dev profile allows. */
        Builder calendarApproval(io.github.dhluong90.bpm.process.internal.service.Calendars.Approval value) {
            calendarApproval = value;
            return this;
        }

        Builder inputMasker(io.github.dhluong90.bpm.process.spi.InputMasker value) {
            inputMasker = value;
            return this;
        }

        Builder labelLimits(io.github.dhluong90.bpm.process.internal.service.AgentLabelLimits value) {
            labelLimits = value;
            return this;
        }

        Builder memoryLimits(io.github.dhluong90.bpm.process.internal.service.AgentMemoryLimits value) {
            memoryLimits = value;
            return this;
        }

        Builder outputSchemas(io.github.dhluong90.bpm.process.spi.OutputSchemaValidator value) {
            outputSchemas = value;
            return this;
        }

        /** The host's outbox port for the messages a process sends (#491); none by default. */
        Builder messageAdapter(io.github.dhluong90.bpm.process.spi.MessageAdapter value) {
            messageAdapter = value;
            return this;
        }

        /** The host's data stores (#499), by name; none by default. */
        Builder dataStores(io.github.dhluong90.bpm.process.spi.DataStoreAdapter... value) {
            dataStores.addAll(java.util.List.of(value));
            return this;
        }

        Builder publisher(ProcessEventPublisher value) {
            publisher = value;
            return this;
        }

        /** Adds the real Rule Engine on the same database, joined to the engine's transactions. */
        Builder withRuleEngine() {
            withRuleEngine = true;
            return this;
        }

        /**
         * The decision port of this replica, built over the real Rule Engine (null without {@link #withRuleEngine()}).
         * The process starter's tests pass its own port, the one class allowed to claim the tenant-scoped entry point.
         */
        Builder decisionPort(java.util.function.Function<io.github.dhluong90.bpm.rules.internal.service.RuleEngines,
                io.github.dhluong90.bpm.process.internal.service.DecisionPort> value) {
            decisionPort = value;
            return this;
        }

        TestEngine build() {
            return new TestEngine(this);
        }
    }

    /** The shared database, migrated once per JVM. */
    static synchronized DataSource dataSource() {
        if (dataSource == null) {
            POSTGRES.start();
            PGSimpleDataSource ds = new PGSimpleDataSource();
            ds.setUrl(POSTGRES.getJdbcUrl());
            ds.setUser(POSTGRES.getUsername());
            ds.setPassword(POSTGRES.getPassword());
            new ProcessEngineSchemaMigrator(ds).migrate();
            dataSource = ds;
        }
        return dataSource;
    }

    private static boolean ruleEngineMigrated;

    /** Migrates the Rule Engine's tables once, and makes sure the decision log has partitions around {@code now}. */
    private static synchronized void migrateRuleEngine(DataSource ds, DataSourceTransactionManager transactionManager,
            java.time.Instant now) {
        if (!ruleEngineMigrated) {
            new io.github.dhluong90.bpm.rules.postgres.RuleEngineSchemaMigrator(ds).migrate();
            ruleEngineMigrated = true;
        }
        new io.github.dhluong90.bpm.rules.postgres.DecisionLogPartitions(ds,
                new io.github.dhluong90.bpm.rules.postgres.RuleEngineTransactions(transactionManager))
                .ensureCurrentAndNextMonth(now);
    }

    /**
     * A HikariCP pool on the shared database, sized like a small host.
     *
     * @param maxConnections the pool size
     * @param connectionTimeout how long a borrower waits before the pool times out
     * @return the pool; close it after the test
     */
    static com.zaxxer.hikari.HikariDataSource pool(int maxConnections, Duration connectionTimeout) {
        dataSource();
        com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(maxConnections);
        config.setMinimumIdle(maxConnections);
        config.setConnectionTimeout(connectionTimeout.toMillis());
        config.setPoolName("host-pool-" + maxConnections);
        return new com.zaxxer.hikari.HikariDataSource(config);
    }

    static JdbcClient jdbc() {
        return JdbcClient.create(dataSource());
    }

    /**
     * A new, empty schema in the shared database, for tests that run the migrations themselves.
     *
     * @param schema the schema name, unique per test
     * @return a data source whose connections use only that schema
     */
    static DataSource emptySchema(String schema) {
        dataSource();
        jdbc().sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE").update();
        jdbc().sql("CREATE SCHEMA " + schema).update();
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        ds.setCurrentSchema(schema);
        return ds;
    }

    /** Empties every engine table, so a sweep across tenants sees only the current test's jobs. */
    static void reset() {
        // The append-only triggers of pre_definition_history refuse TRUNCATE; the test superuser switches ordinary
        // triggers off for this one statement batch, on one connection.
        jdbc().sql("SET session_replication_role = replica; "
                + "TRUNCATE pre_signal, pre_start_arrival, pre_event_subscription, pre_compensation_stack, pre_node_stats_hourly, pre_engine_watermark, pre_migration_instance, pre_migration_history, pre_migration, pre_definition_layout, pre_calendar_change, pre_holiday, pre_calendar, pre_step_result, pre_agent_replay_case, pre_agent_replay, pre_incident, pre_timer, pre_history, "
                + "pre_task, pre_token, pre_instance, pre_idempotency, "
                + "pre_definition_history, pre_definition CASCADE; "
                + "SET session_replication_role = origin").update();
    }

    static byte[] resource(String path) {
        try (InputStream in = TestEngine.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("No test resource " + path);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
