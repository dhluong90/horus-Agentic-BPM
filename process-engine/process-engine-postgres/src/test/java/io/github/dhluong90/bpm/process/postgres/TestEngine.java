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
            return this;
        }
    }

    final ProcessServices services;
    final JobExecutor jobs;
    final Identity identity = new Identity();
    final List<ProcessEventPublisher.Event> events = new CopyOnWriteArrayList<>();

    private TestEngine(Builder b) {
        DataSource ds = dataSource();
        ProcessEngineTransactions tx = new ProcessEngineTransactions(new DataSourceTransactionManager(ds));
        JdbcJobStore jobStore = new JdbcJobStore(ds);
        SubsetBpmnReader reader = new SubsetBpmnReader(Limits.DEFAULTS);
        JobSettings jobSettings = JobSettings.defaults(b.replicaId).withTimerLease(b.lease);
        jobSettings = new JobSettings(jobSettings.replicaId(), jobSettings.timerLease(), b.batchSize,
                jobSettings.maxAttempts(), b.retryBackoff);
        EngineSettings settings = new EngineSettings(b.autoApprove, EngineDefaults.MAX_NODES, EngineDefaults.MAX_BYTES,
                EngineDefaults.IDEMPOTENCY_RETENTION, EngineDefaults.SLOW_CALL_WARNING);
        Identity id = identity;
        ProcessEventPublisher publisher = b.publisher != null ? b.publisher : events::add;
        this.services = new ProcessServices(() -> id.tenant, id, b.clock, tx, new JdbcProcessStore(ds),
                Map.of(SourceFormat.BPMN_XML, reader::read),
                new LocalServiceInvoker(b.handlers.stream().collect(Collectors.toMap(ServiceTaskHandler::name, h -> h)),
                        Optional.empty(), Duration.ofSeconds(5)),
                publisher, settings, BehaviorCatalog.registry(), new V1ExpressionEvaluator(100), jobStore, jobSettings);
        this.jobs = new JobExecutor(jobStore, tx, b.clock, jobSettings, services.jobHandlers());
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
        private List<ServiceTaskHandler> handlers = List.of();
        private ProcessEventPublisher publisher;

        private Builder(String replicaId, TestClock clock) {
            this.replicaId = replicaId;
            this.clock = clock;
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

        Builder handlers(ServiceTaskHandler... value) {
            handlers = List.of(value);
            return this;
        }

        Builder publisher(ProcessEventPublisher value) {
            publisher = value;
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

    static JdbcClient jdbc() {
        return JdbcClient.create(dataSource());
    }

    /** Empties every engine table, so a sweep across tenants sees only the current test's jobs. */
    static void reset() {
        jdbc().sql("TRUNCATE pre_timer, pre_history, pre_task, pre_token, pre_instance, pre_idempotency, pre_definition "
                + "CASCADE").update();
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
