package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.bpmn.subset.SubsetBpmnReader;
import io.github.dhluong90.bpm.process.bpmn.xml.Limits;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorCatalog;
import io.github.dhluong90.bpm.process.internal.expr.V1ExpressionEvaluator;
import io.github.dhluong90.bpm.process.internal.service.EngineSettings;
import io.github.dhluong90.bpm.process.internal.service.LocalServiceInvoker;
import io.github.dhluong90.bpm.process.internal.service.ProcessServices;
import io.github.dhluong90.bpm.process.spi.CallerResolver;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PRE-304 on a real PostgreSQL with two replicas, each with its own data source, store and kernel cache, on one
 * database. A cross-border payment of 150,000,000 VND is screened by two reviews at once; the AML review and the
 * sanctions review are completed at the same moment, one on each replica, and the inclusive join of
 * {@code payment-screening.bpmn} must release the payment exactly once every time. Iterations:
 * {@code -Dbpm.join.iterations} (default 300).
 */
@Testcontainers
class InclusiveJoinPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    private static final int ITERATIONS = Integer.getInteger("bpm.join.iterations", 300);
    private static final Caller CALLER = new Caller();
    private static DataSource db;
    private static ProcessServices replicaOne;
    private static ProcessServices replicaTwo;
    private static ExecutorService pods;

    @BeforeAll
    static void twoReplicas() {
        db = dataSource();
        new ProcessEngineSchemaMigrator(db).migrate();
        replicaOne = replica(dataSource());
        replicaTwo = replica(dataSource());
        CALLER.as("bank-a", "officer-lan");
        replicaOne.importAtStartup("bank-a", ImportRequest.bpmn(bpmn("payment-screening.bpmn")));
        pods = Executors.newFixedThreadPool(2);
    }

    @AfterAll
    static void stop() {
        pods.shutdownNow();
    }

    @Test
    void PRE_304_inclusive_join_prevents_concurrent_lost_wakeup() throws Exception {
        Map<String, Integer> firedBy = new LinkedHashMap<>();
        for (int i = 0; i < ITERATIONS; i++) {
            // Given a payment waiting at both reviews
            CALLER.as("bank-a", "officer-lan");
            ProcessInstance instance = replicaOne.start(StartRequest.of("payment-screening")
                    .withBusinessKey("PAY-2027-%06d".formatted(i)).withIdempotencyKey("start-" + i)
                    .withVariables(Map.of("amount", Money.ofMinor("VND", 150_000_000L),
                            "crossBorder", true)));
            ProcessInstance.Task aml = task(instance, "aml-review");
            ProcessInstance.Task sanctions = task(instance, "sanctions-review");

            // When pod 1 completes the AML review and pod 2 the sanctions review at the same moment
            CyclicBarrier sameMoment = new CyclicBarrier(2);
            int n = i;
            Future<?> podOne = pods.submit(() -> completeAt(sameMoment, replicaOne, "officer-an", aml, "a-" + n));
            Future<?> podTwo = pods.submit(() -> completeAt(sameMoment, replicaTwo, "officer-binh", sanctions, "b-" + n));
            podOne.get(30, TimeUnit.SECONDS);
            podTwo.get(30, TimeUnit.SECONDS);

            // Then the payment is released once: one release task, no token left waiting, both arrivals spent
            assertThat(count("SELECT count(*) FROM pre_task WHERE tenant_id = 'bank-a' AND instance_id = ? "
                    + "AND node_id = 'release'", instance.id())).as("iteration %d", i).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM pre_token WHERE tenant_id = 'bank-a' AND instance_id = ? "
                    + "AND current_node_id = 'settle' AND status = 'JOINED'", instance.id())).as("iteration %d", i)
                    .isZero();
            assertThat(count("SELECT count(*) FROM pre_token WHERE tenant_id = 'bank-a' AND instance_id = ? "
                    + "AND current_node_id = 'settle' AND status = 'COMPLETED'", instance.id())).as("iteration %d", i)
                    .isEqualTo(2);
            assertThat(count("SELECT count(*) FROM pre_history WHERE tenant_id = 'bank-a' AND instance_id = ? "
                    + "AND node_id = 'settle' AND event_type = 'JOIN_FIRED'", instance.id())).as("iteration %d", i)
                    .isEqualTo(1);
            firedBy.merge(JdbcClient.create(db).sql("SELECT actor_id FROM pre_history WHERE tenant_id = 'bank-a' "
                            + "AND instance_id = ? AND node_id = 'settle' AND event_type = 'JOIN_FIRED'")
                    .param(instance.id()).query(String.class).single(), 1, Integer::sum);
        }
        // Which pod's transaction saw the set complete; both orders show the two transactions really raced.
        System.out.printf("PRE-304: %d iterations, join fired by %s%n", ITERATIONS, firedBy);
        assertThat(firedBy.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(ITERATIONS);
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private static void completeAt(CyclicBarrier barrier, ProcessServices replica, String officer,
            ProcessInstance.Task task, String key) {
        CALLER.as("bank-a", officer);
        // Completing needs a claim (PRE-051); the claim comes before the barrier, so only the completions race.
        long lockVersion = replica.claim(task.id(), task.lockVersion(), key + "-claim").lockVersion();
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        replica.complete(task.id(), Map.of("done", true), lockVersion, key);
    }

    private static ProcessInstance.Task task(ProcessInstance instance, String nodeId) {
        return instance.openTasks().stream().filter(t -> t.nodeId().equals(nodeId)).findFirst().orElseThrow();
    }

    private static long count(String sql, String instanceId) {
        return JdbcClient.create(db).sql(sql).param(instanceId).query(Long.class).single();
    }

    private static DataSource dataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }

    /** One replica: its own data source, transaction manager, store and kernel cache. */
    private static ProcessServices replica(DataSource ds) {
        SubsetBpmnReader reader = new SubsetBpmnReader(Limits.DEFAULTS);
        return new ProcessServices(CALLER::tenant, CALLER, Clock.systemUTC(),
                new ProcessEngineTransactions(new DataSourceTransactionManager(ds)), new JdbcProcessStore(ds),
                Map.of(SourceFormat.BPMN_XML, reader::read),
                new LocalServiceInvoker(Map.of(), Optional.empty(), Duration.ofSeconds(5)), event -> { },
                new EngineSettings(true, 500, 1_048_576, Duration.ofDays(1), Duration.ofMillis(200)),
                BehaviorCatalog.registry(), new V1ExpressionEvaluator(100));
    }

    private static byte[] bpmn(String name) {
        try (InputStream in = InclusiveJoinPostgresIT.class.getResourceAsStream("/bpmn/" + name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The authenticated context per thread, as each request thread of a host has its own. */
    private static final class Caller implements CallerResolver {
        private final ThreadLocal<String[]> current = ThreadLocal.withInitial(() -> new String[2]);

        void as(String tenantId, String callerId) {
            current.set(new String[] {tenantId, callerId});
        }

        String tenant() {
            return current.get()[0];
        }

        @Override
        public String currentCallerId() {
            return current.get()[1];
        }

        @Override
        public Set<String> currentPermissions() {
            return Set.of("processes:start", "processes:read", "processes:task:work", "processes:deploy");
        }

        @Override
        public Set<String> currentRoles() {
            return Set.of("AML_OFFICER", "SANCTIONS_OFFICER", "PAYMENT_OFFICER");
        }
    }
}
