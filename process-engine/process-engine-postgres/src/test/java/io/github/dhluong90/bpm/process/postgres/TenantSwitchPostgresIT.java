package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.InstanceNotFoundException;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.api.TaskNotFoundException;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * PRE-038 at the engine (#85): the host's tenant filter answers for {@code lan} with the tenant she switched to
 * ({@code allowed_tenants: [bank-a, bank-b]}). The engine reads the tenant only from its resolver, so once the resolver
 * says {@code bank-b}, case {@code P1} of {@code bank-a} is {@code 404 INSTANCE_NOT_FOUND}, its task and its
 * cancellation too, and no row changes for either tenant. Back in {@code bank-a} the case is still readable.
 */
class TenantSwitchPostgresIT {

    private final TestClock clock = TestClock.at("2027-01-04T03:00:00Z");

    @Test
    void PRE_038_engine_answers_404_for_the_original_tenants_instance_after_a_switch() {
        // Given case P1 of bank-a, started by lan with its credit review task
        TestEngine.reset();
        TestEngine engine = TestEngine.replica("pod-1", clock).build();
        for (String tenant : java.util.List.of("bank-a", "bank-b")) {
            engine.identity.as(tenant, "pipeline", "processes:deploy", "processes:read");
            engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                    TestEngine.resource("/bpmn/card-fee-review.bpmn"), null));
        }
        engine.identity.as("bank-a", "lan", "processes:start", "processes:read", "processes:cancel",
                "processes:task:work");
        ProcessInstance p1 = engine.services.start(new StartRequest("card-fee-review", "P1", Map.of(), null));
        String task = p1.tasks().get(0).id();
        assertThat(engine.services.instance(p1.id()).id()).isEqualTo(p1.id());
        Map<String, Integer> before = rows();

        // When the host's filter has switched lan to bank-b, she reads, works on and cancels what was P1
        engine.identity.as("bank-b", "lan", "processes:start", "processes:read", "processes:cancel",
                "processes:task:work");

        // Then 404 INSTANCE_NOT_FOUND and 404 TASK_NOT_FOUND, as if the case did not exist, and nothing is written
        assertThatThrownBy(() -> engine.services.instance(p1.id())).isInstanceOf(InstanceNotFoundException.class)
                .extracting("code").isEqualTo("INSTANCE_NOT_FOUND");
        assertThatThrownBy(() -> engine.services.task(task)).isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> engine.services.cancel(p1.id(), 0, "switched tenant", "k-switch-1"))
                .isInstanceOf(InstanceNotFoundException.class);
        assertThat(rows()).isEqualTo(before);

        // And in bank-a, P1 is unchanged
        engine.identity.as("bank-a", "lan", "processes:read");
        assertThat(engine.services.instance(p1.id()).status()).isEqualTo("RUNNING");
    }

    /** Rows per tenant of the tables a read or a refused write must leave alone. */
    private static Map<String, Integer> rows() {
        Map<String, Integer> counts = new java.util.TreeMap<>();
        for (String tenant : java.util.List.of("bank-a", "bank-b")) {
            for (String table : java.util.List.of("pre_instance", "pre_history", "pre_idempotency", "pre_task")) {
                counts.put(tenant + "." + table, TestEngine.jdbc().sql("SELECT count(*) FROM " + table
                        + " WHERE tenant_id = ?").param(tenant).query(Integer.class).single());
            }
        }
        return counts;
    }
}
