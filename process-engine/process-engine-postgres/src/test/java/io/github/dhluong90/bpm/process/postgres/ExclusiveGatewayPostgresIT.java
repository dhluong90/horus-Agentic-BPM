package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.Money;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.InstanceNotFoundException;
import io.github.dhluong90.bpm.process.api.NoEffectiveVersionException;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.api.StepFailedException;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PRE-004 through the persistence shell on a real PostgreSQL: the loan-origination BPMN document is imported, each
 * amount takes exactly one branch, a start that reaches a gateway with no true condition and no default is refused
 * with every row rolled back, and a caller of another tenant reaches nothing.
 */
@Testcontainers
class ExclusiveGatewayPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(System.getProperty("bpm.test.postgres.image", "postgres:16-alpine"));

    private static final List<String> TABLES = List.of("pre_instance", "pre_token", "pre_task", "pre_history",
            "pre_idempotency");

    private static DataSource db;
    private static ProcessServices engine;
    private static final Caller CALLER = new Caller();

    @BeforeAll
    static void migrateAndImport() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        db = ds;
        new ProcessEngineSchemaMigrator(db).migrate();
        SubsetBpmnReader reader = new SubsetBpmnReader(Limits.DEFAULTS);
        engine = new ProcessServices(CALLER::tenant, CALLER,
                Clock.fixed(Instant.parse("2027-01-04T09:00:00Z"), ZoneOffset.UTC),
                new ProcessEngineTransactions(new DataSourceTransactionManager(db)), new JdbcProcessStore(db),
                Map.of(SourceFormat.BPMN_XML, reader::read),
                new LocalServiceInvoker(Map.of(), Optional.empty(), Duration.ofSeconds(5)), event -> { },
                new EngineSettings(true, 500, 1_048_576, Duration.ofDays(1), Duration.ofMillis(200)),
                BehaviorCatalog.registry(), new V1ExpressionEvaluator(100));
        engine.importAtStartup("bank-a", ImportRequest.bpmn(bpmn("loan-origination.bpmn")));
        engine.importAtStartup("bank-a", ImportRequest.bpmn(bpmn("loan-origination-strict.bpmn")));
    }

    @Test
    void PRE_004_imported_definition_sends_150_million_VND_to_senior_approval_only() {
        CALLER.as("bank-a", "officer-lan");

        ProcessInstance instance = engine.start(StartRequest.of("loan-origination").withBusinessKey("LN-2027-000123")
                .withVariables(Map.of("amount", Money.ofMinor("VND", 150_000_000))).withIdempotencyKey("k-senior"));

        assertThat(instance.tokens()).filteredOn(t -> "FLOW".equals(t.kind())).singleElement()
                .satisfies(t -> assertThat(t.nodeId()).isEqualTo("senior-approval"));
        assertThat(instance.openTasks()).extracting(ProcessInstance.Task::nodeId).containsExactly("senior-approval");
        assertThat(gatewayFlows(instance.id())).containsExactly("amount-check__senior");
    }

    @Test
    void PRE_004_imported_definition_sends_20_million_VND_down_the_default_branch() {
        CALLER.as("bank-a", "officer-lan");

        ProcessInstance instance = engine.start(StartRequest.of("loan-origination").withBusinessKey("LN-2027-000124")
                .withVariables(Map.of("amount", Money.ofMinor("VND", 20_000_000))).withIdempotencyKey("k-default"));

        assertThat(instance.openTasks()).extracting(ProcessInstance.Task::nodeId).containsExactly("auto-approve");
        assertThat(gatewayFlows(instance.id())).containsExactly("amount-check__auto");
    }

    @Test
    void PRE_004_no_true_condition_and_no_default_refuses_the_start_and_writes_nothing() {
        // Given the definition without a default branch; When 70,000,000 VND arrives, which no branch accepts
        CALLER.as("bank-a", "officer-lan");
        Map<String, Long> before = counts("bank-a");
        StartRequest request = StartRequest.of("loan-origination-strict").withBusinessKey("LN-2027-000125")
                .withVariables(Map.of("amount", Money.ofMinor("VND", 70_000_000))).withIdempotencyKey("k-no-path");

        // Then the start fails at the gateway with NO_PATH
        assertThatThrownBy(() -> engine.start(request))
                .isInstanceOf(StepFailedException.class)
                .satisfies(e -> {
                    assertThat(((StepFailedException) e).code()).isEqualTo("STEP_FAILED");
                    assertThat(((StepFailedException) e).nodeId()).isEqualTo("amount-check");
                    assertThat(((StepFailedException) e).reason()).isEqualTo("NO_PATH");
                });
        // And no instance, token, task, history or idempotency row survives
        assertThat(counts("bank-a")).isEqualTo(before);
    }

    @Test
    void PRE_004_after_a_refused_start_the_same_key_starts_once_the_amount_is_corrected() {
        CALLER.as("bank-a", "officer-lan");
        StartRequest refused = StartRequest.of("loan-origination-strict").withBusinessKey("LN-2027-000126")
                .withVariables(Map.of("amount", Money.ofMinor("VND", 60_000_000))).withIdempotencyKey("k-retry");
        assertThatThrownBy(() -> engine.start(refused)).isInstanceOf(StepFailedException.class);

        // The claim was rolled back with the start, so the corrected request with the same key is not a key reuse
        ProcessInstance started = engine.start(StartRequest.of("loan-origination-strict")
                .withBusinessKey("LN-2027-000126").withVariables(Map.of("amount", Money.ofMinor("VND", 40_000_000)))
                .withIdempotencyKey("k-retry"));

        assertThat(started.openTasks()).extracting(ProcessInstance.Task::nodeId).containsExactly("auto-approve");
    }

    @Test
    void TR_TEN_01_a_caller_of_another_tenant_reaches_nothing_and_writes_nothing() {
        // Given an instance of bank-a waiting at senior approval
        CALLER.as("bank-a", "officer-lan");
        ProcessInstance own = engine.start(StartRequest.of("loan-origination").withBusinessKey("LN-2027-000127")
                .withVariables(Map.of("amount", Money.ofMinor("VND", 120_000_000))).withIdempotencyKey("k-tenant"));
        String taskId = own.openTasks().get(0).id();
        Map<String, Long> beforeA = counts("bank-a");

        // When a caller of bank-b, holding the same role, starts, reads and completes
        CALLER.as("bank-b", "officer-minh");
        Map<String, Long> beforeB = counts("bank-b");

        // Then each is refused as if the data did not exist, and neither tenant gains a row
        assertThatThrownBy(() -> engine.start(StartRequest.of("loan-origination").withBusinessKey("LN-2027-000127")
                .withVariables(Map.of("amount", Money.ofMinor("VND", 120_000_000)))))
                .isInstanceOf(NoEffectiveVersionException.class);
        assertThatThrownBy(() -> engine.instance(own.id())).isInstanceOf(InstanceNotFoundException.class);
        assertThatThrownBy(() -> engine.complete(taskId, Map.of("approved", true), 0, "k-b-complete"))
                .isInstanceOf(TaskNotFoundException.class);
        assertThat(counts("bank-a")).isEqualTo(beforeA);
        assertThat(counts("bank-b")).isEqualTo(beforeB);
        CALLER.as("bank-a", "officer-lan");
        assertThat(engine.instance(own.id()).openTasks()).singleElement()
                .satisfies(t -> assertThat(t.status()).isEqualTo("OPEN"));
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private static List<String> gatewayFlows(String instanceId) {
        return JdbcClient.create(db).sql("SELECT details->>'flow' FROM pre_history WHERE tenant_id = 'bank-a' AND "
                        + "instance_id = ? AND node_id = 'amount-check' AND event_type = 'NODE_COMPLETED'")
                .param(instanceId).query(String.class).list();
    }

    private static Map<String, Long> counts(String tenant) {
        JdbcClient jdbc = JdbcClient.create(db);
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        for (String table : TABLES) {
            out.put(table, jdbc.sql("SELECT count(*) FROM " + table + " WHERE tenant_id = ?").param(tenant)
                    .query(Long.class).single());
        }
        return out;
    }

    private static byte[] bpmn(String name) {
        try (InputStream in = ExclusiveGatewayPostgresIT.class.getResourceAsStream("/bpmn/" + name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The authenticated context of the test: tenant and caller, with the credit officer roles. */
    private static final class Caller implements CallerResolver {
        private volatile String tenant;
        private volatile String caller;

        void as(String tenantId, String callerId) {
            this.tenant = tenantId;
            this.caller = callerId;
        }

        String tenant() {
            return tenant;
        }

        @Override
        public String currentCallerId() {
            return caller;
        }

        @Override
        public Set<String> currentPermissions() {
            return Set.of("processes:start", "processes:read", "processes:task:work", "processes:deploy");
        }

        @Override
        public Set<String> currentRoles() {
            return Set.of("CREDIT_OFFICER", "SENIOR_CREDIT_OFFICER");
        }
    }
}
