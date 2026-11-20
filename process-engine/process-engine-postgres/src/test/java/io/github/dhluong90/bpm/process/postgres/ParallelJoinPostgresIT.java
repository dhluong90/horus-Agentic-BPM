package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.api.TaskNotFoundException;
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
import java.util.List;
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
 * PRE-005 and PRE-044 on a real PostgreSQL with two replicas: two engine instances, each with its own data source,
 * transaction manager, store and compiled-kernel cache, on one database. The credit bureau and fraud check tasks of
 * {@code loan-checks.bpmn} are completed at the same moment, one on each replica, and the join must produce exactly
 * one underwriting token every time. Iterations: {@code -Dbpm.join.iterations} (default 1,000).
 */
@Testcontainers
class ParallelJoinPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(System.getProperty("bpm.test.postgres.image", "postgres:16-alpine"));

    private static final int ITERATIONS = Integer.getInteger("bpm.join.iterations", 1_000);
    private static final List<String> TABLES = List.of("pre_instance", "pre_token", "pre_task", "pre_history",
            "pre_idempotency");

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
        replicaOne.importAtStartup("bank-a", ImportRequest.bpmn(bpmn("loan-checks.bpmn")));
        pods = Executors.newFixedThreadPool(2);
    }

    @AfterAll
    static void stop() {
        pods.shutdownNow();
    }

    @Test
    void PRE_005_parallel_gateway_join_prevents_concurrent_lost_wakeup() throws Exception {
        Map<String, Integer> firedBy = new LinkedHashMap<>();
        for (int i = 0; i < ITERATIONS; i++) {
            // Given an instance waiting at both checks
            CALLER.as("bank-a", "officer-lan");
            ProcessInstance instance = replicaOne.start(StartRequest.of("loan-checks")
                    .withBusinessKey("LN-2027-%06d".formatted(i)).withIdempotencyKey("start-" + i));
            ProcessInstance.Task bureau = task(instance, "credit-bureau");
            ProcessInstance.Task fraud = task(instance, "fraud-check");

            // When pod 1 completes the credit bureau check and pod 2 the fraud check at the same moment
            CyclicBarrier sameMoment = new CyclicBarrier(2);
            int n = i;
            Future<?> podOne = pods.submit(() -> completeAt(sameMoment, replicaOne, "analyst-an", bureau, "a-" + n));
            Future<?> podTwo = pods.submit(() -> completeAt(sameMoment, replicaTwo, "analyst-binh", fraud, "b-" + n));
            podOne.get(30, TimeUnit.SECONDS);
            podTwo.get(30, TimeUnit.SECONDS);

            // Then exactly one token, and one task, are at underwrite, and both branch tokens are spent
            assertThat(liveTokensAt(instance.id(), "underwrite")).as("iteration %d", i).isEqualTo(1);
            assertThat(liveTokensAt(instance.id(), "join-checks")).as("iteration %d", i).isZero();
            assertThat(count("SELECT count(*) FROM pre_task WHERE tenant_id = 'bank-a' AND instance_id = ? "
                    + "AND node_id = 'underwrite'", instance.id())).as("iteration %d", i).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM pre_token WHERE tenant_id = 'bank-a' AND instance_id = ? "
                    + "AND current_node_id = 'join-checks' AND status = 'COMPLETED'", instance.id()))
                    .as("iteration %d", i).isEqualTo(2);
            assertThat(count("SELECT count(*) FROM pre_history WHERE tenant_id = 'bank-a' AND instance_id = ? "
                    + "AND node_id = 'join-checks' AND event_type = 'NODE_COMPLETED'", instance.id()))
                    .as("iteration %d", i).isEqualTo(1);
            firedBy.merge(joinActor(instance.id()), 1, Integer::sum);
        }
        // Which pod's transaction saw the set complete; both orders show the two transactions really raced.
        System.out.printf("PRE-005: %d iterations, join fired by %s%n", ITERATIONS, firedBy);
        assertThat(firedBy.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(ITERATIONS);
    }

    @Test
    void PRE_044_two_instances_started_at_the_same_moment_share_no_token() throws Exception {
        CyclicBarrier sameMoment = new CyclicBarrier(2);
        Future<ProcessInstance> first = pods.submit(() -> startAt(sameMoment, replicaOne, "LN-2027-900001"));
        Future<ProcessInstance> second = pods.submit(() -> startAt(sameMoment, replicaTwo, "LN-2027-900002"));
        ProcessInstance a = first.get(30, TimeUnit.SECONDS);
        ProcessInstance b = second.get(30, TimeUnit.SECONDS);

        List<String> tokensOfA = tokenIds(a.id());
        List<String> tokensOfB = tokenIds(b.id());
        assertThat(tokensOfA).hasSize(3).doesNotContainAnyElementsOf(tokensOfB);
        assertThat(tokensOfB).hasSize(3);
        // Every flow token of an instance hangs under that instance's own root scope token
        for (ProcessInstance instance : List.of(a, b)) {
            List<ProcessInstance.Token> scopes = instance.tokens().stream().filter(t -> "SCOPE".equals(t.kind())).toList();
            assertThat(scopes).hasSize(1);
            ProcessInstance.Token root = scopes.get(0);
            assertThat(instance.tokens()).filteredOn(t -> "FLOW".equals(t.kind()))
                    .extracting(ProcessInstance.Token::scopeTokenId).containsOnly(root.id());
        }
    }

    @Test
    void TR_TEN_01_a_caller_of_another_tenant_cannot_complete_a_branch_and_nothing_is_written() {
        CALLER.as("bank-a", "officer-lan");
        ProcessInstance instance = replicaOne.start(StartRequest.of("loan-checks").withBusinessKey("LN-2027-900003")
                .withIdempotencyKey("start-tenant"));
        ProcessInstance.Task bureau = task(instance, "credit-bureau");
        Map<String, Long> beforeA = counts("bank-a");
        Map<String, Long> beforeB = counts("bank-b");

        CALLER.as("bank-b", "analyst-minh");
        assertThatThrownBy(() -> replicaTwo.complete(bureau.id(), Map.of("score", 640), bureau.lockVersion(), "b-x"))
                .isInstanceOf(TaskNotFoundException.class);

        assertThat(counts("bank-a")).isEqualTo(beforeA);
        assertThat(counts("bank-b")).isEqualTo(beforeB);
        assertThat(count("SELECT count(*) FROM pre_token WHERE tenant_id = 'bank-a' AND instance_id = ? "
                + "AND status = 'JOINED'", instance.id())).isZero();
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private static void completeAt(CyclicBarrier barrier, ProcessServices replica, String analyst,
            ProcessInstance.Task task, String key) {
        CALLER.as("bank-a", analyst);
        // Completing needs a claim (PRE-051); the claim comes before the barrier, so only the completions race.
        long lockVersion = replica.claim(task.id(), task.lockVersion(), key + "-claim").lockVersion();
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        replica.complete(task.id(), Map.of("done", true), lockVersion, key);
    }

    private static ProcessInstance startAt(CyclicBarrier barrier, ProcessServices replica, String businessKey)
            throws Exception {
        CALLER.as("bank-a", "officer-lan");
        barrier.await(10, TimeUnit.SECONDS);
        return replica.start(StartRequest.of("loan-checks").withBusinessKey(businessKey)
                .withIdempotencyKey("start-" + businessKey));
    }

    private static ProcessInstance.Task task(ProcessInstance instance, String nodeId) {
        return instance.openTasks().stream().filter(t -> t.nodeId().equals(nodeId)).findFirst().orElseThrow();
    }

    private static long liveTokensAt(String instanceId, String nodeId) {
        return count("SELECT count(*) FROM pre_token WHERE tenant_id = 'bank-a' AND instance_id = ? AND current_node_id = '"
                + nodeId + "' AND status NOT IN ('COMPLETED','CANCELLED','COMPENSATED')", instanceId);
    }

    private static String joinActor(String instanceId) {
        return JdbcClient.create(db).sql("SELECT actor_id FROM pre_history WHERE tenant_id = 'bank-a' AND instance_id = ? "
                        + "AND node_id = 'join-checks' AND event_type = 'NODE_COMPLETED'")
                .param(instanceId).query(String.class).single();
    }

    private static List<String> tokenIds(String instanceId) {
        return JdbcClient.create(db).sql("SELECT id FROM pre_token WHERE tenant_id = 'bank-a' AND instance_id = ?")
                .param(instanceId).query(String.class).list();
    }

    private static long count(String sql, String instanceId) {
        return JdbcClient.create(db).sql(sql).param(instanceId).query(Long.class).single();
    }

    private static Map<String, Long> counts(String tenant) {
        JdbcClient jdbc = JdbcClient.create(db);
        Map<String, Long> out = new LinkedHashMap<>();
        for (String table : TABLES) {
            out.put(table, jdbc.sql("SELECT count(*) FROM " + table + " WHERE tenant_id = ?").param(tenant)
                    .query(Long.class).single());
        }
        return out;
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
        try (InputStream in = ParallelJoinPostgresIT.class.getResourceAsStream("/bpmn/" + name)) {
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
            return Set.of("CREDIT_ANALYST", "FRAUD_ANALYST", "UNDERWRITER");
        }
    }
}
