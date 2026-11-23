package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.api.DefinitionExport;
import io.github.dhluong90.bpm.process.api.DefinitionRef;
import io.github.dhluong90.bpm.process.api.DefinitionVersion;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.MessageService.MessageDelivery;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Pools, lanes and message flows (#497, PRE-296 to PRE-298) on PostgreSQL: one file with two executable pools and a
 * black box is two definitions of the tenant, a replay of the import changes nothing, the lane names the work group
 * of the user task, and the export keeps the message flows.
 */
class CollaborationPostgresIT {

    private static final String[] PIPELINE = {"processes:deploy", "processes:start", "processes:read"};
    private static final String[] CONSUMER = {"processes:message:correlate", "processes:read"};

    private final TestClock clock = TestClock.at(Instant.parse("2027-03-01T02:00:00Z"));
    private TestEngine engine;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        TestEngine.reset();
        engine = TestEngine.replica("pod-1", clock).build();
        jdbc = TestEngine.jdbc();
    }

    @AfterEach
    void stop() {
        engine.close();
    }

    private byte[] file() throws IOException {
        try (var in = CollaborationPostgresIT.class.getResourceAsStream("/bpmn/order-payment-pools.bpmn")) {
            return in.readAllBytes();
        }
    }

    @Test
    void PRE_296_two_pools_become_two_definitions_and_a_replay_stores_nothing_more() throws IOException {
        engine.identity.as("bank-a", "pipeline", PIPELINE);

        DefinitionVersion first = engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML, file(), "imp-1"));
        DefinitionVersion replay = engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML, file(), "imp-1"));

        assertThat(first.key()).isEqualTo("order-desk");
        assertThat(first.alsoImported()).containsExactly(new DefinitionRef("payment-desk", 1));
        assertThat(replay.alsoImported()).isEqualTo(first.alsoImported());
        assertThat(jdbc.sql("SELECT string_agg(key_name, ',' ORDER BY key_name) FROM pre_definition WHERE tenant_id = 'bank-a'")
                .query(String.class).single()).isEqualTo("order-desk,payment-desk");
        // the other tenant has neither
        assertThat(jdbc.sql("SELECT count(*) FROM pre_definition WHERE tenant_id = 'bank-b'").query(Long.class).single())
                .isZero();
    }

    @Test
    void PRE_297_the_lane_names_the_work_group_of_a_task_started_by_the_message_start_of_its_pool()
            throws IOException {
        engine.identity.as("bank-a", "pipeline", PIPELINE);
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML, file(), null));
        engine.identity.as("bank-a", "payments-consumer", CONSUMER);

        MessageDelivery delivery = engine.services.correlate("payment-requested", "ORD-90001", Map.of(), "pay-1");

        engine.identity.as("bank-a", "pipeline", PIPELINE);
        ProcessInstance instance = engine.services.instance(delivery.instanceId());
        assertThat(instance.definitionKey()).isEqualTo("payment-desk");
        assertThat(instance.tasks()).singleElement().satisfies(t -> {
            assertThat(t.nodeId()).isEqualTo("confirm-payment");
            assertThat(t.assignee()).isEqualTo("group:back-office");
        });
    }

    @Test
    void PRE_298_the_export_of_a_pool_keeps_the_message_flows_and_the_lanes() throws IOException {
        engine.identity.as("bank-a", "pipeline", PIPELINE);
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML, file(), null));

        DefinitionExport export = engine.services.exportDefinition("payment-desk", 1, SourceFormat.BPMN_XML);

        assertThat(export.document()).contains("<bpmn:messageFlow id=\"mf-request\"")
                .contains("<bpmn:messageFlow id=\"mf-audit\"").contains("<bpmn:lane id=\"lane-back-office\"")
                .contains("<bpmn:participant id=\"pool-auditor\" name=\"Auditor\"/>");
    }
}
