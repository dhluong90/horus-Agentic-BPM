package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.dhluong90.bpm.process.api.DefinitionIntegrityException;
import io.github.dhluong90.bpm.process.api.DefinitionNotFoundException;
import io.github.dhluong90.bpm.process.api.DefinitionRef;
import io.github.dhluong90.bpm.process.api.GraphDiff;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.InputInvalidException;
import io.github.dhluong90.bpm.process.api.NoEffectiveVersionException;
import io.github.dhluong90.bpm.process.api.PermissionDeniedException;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The graph diff over stored versions on PostgreSQL (PRE-025, PRE-176): read from the stored, hash-checked
 * definitions of the caller's tenant, of any lifecycle status, and never written.
 */
class DefinitionDiffPostgresIT {

    private static final String[] PIPELINE = {"processes:deploy", "processes:read"};
    private static final String V1 = """
            <bpmn:startEvent id="start" name="Application received"/>
            <bpmn:userTask id="verify-docs" name="Verify documents" horus:assignee="role:ops" horus:resultVariable="docs"/>
            <bpmn:userTask id="approve" name="Approve" horus:assignee="role:ops" horus:resultVariable="approval"/>
            <bpmn:endEvent id="end" name="Account opened"/>
            <bpmn:sequenceFlow id="start__verify" sourceRef="start" targetRef="verify-docs"/>
            <bpmn:sequenceFlow id="verify__approve" sourceRef="verify-docs" targetRef="approve"/>
            <bpmn:sequenceFlow id="approve__end" sourceRef="approve" targetRef="end"/>
            """;
    private static final String V2 = """
            <bpmn:startEvent id="start" name="Application received"/>
            <bpmn:userTask id="verify-docs" name="Verify documents" horus:assignee="role:ops" horus:resultVariable="docs"/>
            <bpmn:intermediateCatchEvent id="fraud-check" name="Fraud screening window">
              <bpmn:timerEventDefinition id="fraud-check-timer">
                <bpmn:timeDuration xsi:type="bpmn:tFormalExpression">PT4H</bpmn:timeDuration>
              </bpmn:timerEventDefinition>
            </bpmn:intermediateCatchEvent>
            <bpmn:userTask id="approve" name="Approve" horus:assignee="role:credit-officer" horus:resultVariable="approval"/>
            <bpmn:endEvent id="end" name="Account opened"/>
            <bpmn:sequenceFlow id="start__verify" sourceRef="start" targetRef="verify-docs"/>
            <bpmn:sequenceFlow id="verify__fraud" sourceRef="verify-docs" targetRef="fraud-check"/>
            <bpmn:sequenceFlow id="fraud__approve" sourceRef="fraud-check" targetRef="approve"/>
            <bpmn:sequenceFlow id="approve__end" sourceRef="approve" targetRef="end"/>
            """;

    private final TestClock clock = TestClock.at("2027-03-01T02:00:00Z");
    private TestEngine engine;

    @BeforeEach
    void setUp() {
        TestEngine.reset();
        engine = TestEngine.replica("pod-1", clock).autoApprove(false).build();
    }

    private static byte[] bpmn(String process) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                  xmlns:horus="urn:horus-bpm:bpmn:extensions:v1"
                                  id="Definitions_kyc" targetNamespace="urn:horus-bpm:examples"
                                  expressionLanguage="urn:horus-bpm:expr:v1">
                  <bpmn:process id="kyc-onboarding" name="KYC onboarding" isExecutable="true">
                %s
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(process).getBytes(StandardCharsets.UTF_8);
    }

    private void importAs(String tenant, String process) {
        engine.identity.as(tenant, "pipeline", PIPELINE);
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML, bpmn(process), null));
    }

    private Map<String, Object> rows(JdbcClient jdbc) {
        return Map.of("definitions", jdbc.sql("SELECT count(*) || md5(string_agg(t::text, '|' ORDER BY t::text)) "
                        + "FROM pre_definition t").query(String.class).single(),
                "history", jdbc.sql("SELECT count(*) FROM pre_definition_history").query(Long.class).single(),
                "events", engine.events.size());
    }

    @Test
    void PRE_025_the_diff_of_two_stored_drafts_lists_the_added_step_the_changed_role_and_the_links() {
        importAs("bank-a", V1);
        importAs("bank-a", V2);
        JdbcClient jdbc = TestEngine.jdbc();
        Map<String, Object> before = rows(jdbc);
        engine.identity.as("bank-a", "approver-minh", "processes:read");

        GraphDiff diff = engine.services.diff(DefinitionRef.of("kyc-onboarding", 1), DefinitionRef.of("kyc-onboarding", 2));

        assertThat(diff.base().status()).isEqualTo("DRAFT");
        assertThat(diff.target().version()).isEqualTo(2);
        assertThat(diff.nodesAdded()).containsExactly(new GraphDiff.Node("fraud-check", "Fraud screening window",
                "timer"));
        assertThat(diff.nodesChanged()).extracting(GraphDiff.NodeChange::id, GraphDiff.NodeChange::path,
                GraphDiff.NodeChange::from, GraphDiff.NodeChange::to)
                .containsExactly(tuple("approve", "/assignee/role", "ops", "credit-officer"));
        assertThat(diff.edgesRemoved()).extracting(GraphDiff.Edge::id).containsExactly("verify__approve");
        assertThat(diff.edgesAdded()).extracting(GraphDiff.Edge::id).containsExactly("fraud__approve", "verify__fraud");
        assertThat(rows(jdbc)).as("a diff writes nothing and publishes nothing").isEqualTo(before);
    }

    @Test
    void PRE_176_another_tenants_versions_are_not_found_and_nothing_is_written() {
        importAs("bank-a", V1);
        importAs("bank-a", V2);
        importAs("bank-b", V1);
        JdbcClient jdbc = TestEngine.jdbc();
        Map<String, Object> before = rows(jdbc);

        engine.identity.as("bank-b", "approver-binh", "processes:read");

        assertThatThrownBy(() -> engine.services.diff(DefinitionRef.of("kyc-onboarding", 1),
                DefinitionRef.of("kyc-onboarding", 2))).isInstanceOf(DefinitionNotFoundException.class);
        assertThat(engine.services.diff(DefinitionRef.of("kyc-onboarding", 1), DefinitionRef.of("kyc-onboarding", 1))
                .identical()).as("its own version").isTrue();
        assertThatThrownBy(() -> engine.services.diffFromEffective(DefinitionRef.of("kyc-onboarding", 1)))
                .as("bank-b has only a draft").isInstanceOf(NoEffectiveVersionException.class);
        assertThat(rows(jdbc)).isEqualTo(before);
    }

    @Test
    void PRE_176_a_diff_needs_the_read_permission_one_key_and_an_untampered_source() {
        importAs("bank-a", V1);
        importAs("bank-a", V2);

        engine.identity.as("bank-a", "clerk-an", "processes:task:work");
        assertThatThrownBy(() -> engine.services.diff(DefinitionRef.of("kyc-onboarding", 1),
                DefinitionRef.of("kyc-onboarding", 2))).isInstanceOf(PermissionDeniedException.class);
        engine.identity.as("bank-a", "approver-minh", "processes:read");
        assertThatThrownBy(() -> engine.services.diff(DefinitionRef.of("loan-review", 1),
                DefinitionRef.of("kyc-onboarding", 2))).isInstanceOf(InputInvalidException.class);

        TestEngine.jdbc().sql("UPDATE pre_definition SET source_document = replace(source_document, 'role:ops', "
                + "'role:anyone') WHERE tenant_id = 'bank-a' AND version_num = 1").update();
        assertThatThrownBy(() -> engine.services.diff(DefinitionRef.of("kyc-onboarding", 1),
                DefinitionRef.of("kyc-onboarding", 2))).isInstanceOf(DefinitionIntegrityException.class);
    }
}
