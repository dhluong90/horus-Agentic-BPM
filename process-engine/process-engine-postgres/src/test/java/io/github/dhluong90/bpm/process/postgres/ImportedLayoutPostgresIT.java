package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.api.DefinitionRef;
import io.github.dhluong90.bpm.process.api.DefinitionVersion;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.LayoutRevision;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.internal.json.EngineJson;
import io.github.dhluong90.bpm.process.internal.service.Layouts;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * A BPMN import writes the file's own diagram as layout revision 1 of the version it creates (#445, PRE-215), apart from
 * the definition: the hash does not depend on it, an import without a diagram stores none, each import has its own
 * revision 1, and a later change to the layout is a new revision that needs no approval.
 */
class ImportedLayoutPostgresIT {

    private static final String KEY = "kyc-onboarding";
    private static final String[] PIPELINE = {"processes:deploy", "processes:read"};

    private static final String V1 = """
            <bpmn:startEvent id="start" name="Application received"/>
            <bpmn:userTask id="verify-docs" name="Verify documents" horus:assignee="role:ops" horus:resultVariable="docs"/>
            <bpmn:endEvent id="end" name="Account opened"/>
            <bpmn:sequenceFlow id="start__verify" sourceRef="start" targetRef="verify-docs"/>
            <bpmn:sequenceFlow id="verify__end" sourceRef="verify-docs" targetRef="end"/>
            """;
    private static final String V2 = V1.replace("role:ops", "role:credit-officer");

    private final TestClock clock = TestClock.at("2027-03-01T02:00:00Z");
    private TestEngine engine;
    private JdbcClient jdbc;
    private JdbcLayoutStore store;

    @BeforeEach
    void setUp() {
        TestEngine.reset();
        engine = TestEngine.replica("pod-1", clock).importedLayouts().build();
        jdbc = TestEngine.jdbc();
        store = new JdbcLayoutStore(TestEngine.dataSource());
    }

    private static byte[] bpmn(String process, String diagram) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                                  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                                  xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                                  xmlns:horus="urn:horus-bpm:bpmn:extensions:v1"
                                  id="Definitions_kyc" targetNamespace="urn:horus-bpm:examples"
                                  expressionLanguage="urn:horus-bpm:expr:v1">
                  <bpmn:process id="kyc-onboarding" name="KYC onboarding" isExecutable="true">
                %s
                  </bpmn:process>
                %s
                </bpmn:definitions>
                """.formatted(process, diagram).getBytes(StandardCharsets.UTF_8);
    }

    /** A diagram with a shape for each step, one waypoint list, and a shape of something that is not a step. */
    private static String diagram(int dx) {
        return """
                  <bpmndi:BPMNDiagram id="Diagram_kyc">
                    <bpmndi:BPMNPlane id="Plane_kyc" bpmnElement="kyc-onboarding">
                      <bpmndi:BPMNShape id="start_di" bpmnElement="start"><dc:Bounds x="%d" y="100" width="36" height="36"/></bpmndi:BPMNShape>
                      <bpmndi:BPMNShape id="verify_di" bpmnElement="verify-docs"><dc:Bounds x="%d" y="80" width="100.5" height="80"/></bpmndi:BPMNShape>
                      <bpmndi:BPMNShape id="end_di" bpmnElement="end"><dc:Bounds x="%d" y="100" width="36" height="36"/></bpmndi:BPMNShape>
                      <bpmndi:BPMNShape id="ghost_di" bpmnElement="ghost-step"><dc:Bounds x="1" y="1" width="10" height="10"/></bpmndi:BPMNShape>
                      <bpmndi:BPMNEdge id="start__verify_di" bpmnElement="start__verify">
                        <di:waypoint x="%d" y="118"/><di:waypoint x="%d" y="118"/>
                      </bpmndi:BPMNEdge>
                    </bpmndi:BPMNPlane>
                  </bpmndi:BPMNDiagram>
                """.formatted(100 + dx, 200 + dx, 400 + dx, 136 + dx, 200 + dx);
    }

    private DefinitionVersion importAs(String tenant, String process, String diagram) {
        engine.identity.as(tenant, "pipeline", PIPELINE);
        return engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML, bpmn(process, diagram), null));
    }

    private String definitionId(String tenant, int version) {
        return jdbc.sql("SELECT id FROM pre_definition WHERE tenant_id = ? AND key_name = ? AND version_num = ?")
                .params(tenant, KEY, version).query(String.class).single();
    }

    @Test
    void PRE_215_a_bpmn_import_stores_its_diagram_as_layout_revision_1_apart_from_the_definition() {
        // Given a BPMN file with a diagram of three steps, one flow with two waypoints, and a shape of an unknown element
        DefinitionVersion v1 = importAs("bank-a", V1, diagram(0));
        String id = definitionId("bank-a", 1);

        // When the version is read through its layout
        LayoutRevision revision = store.find("bank-a", id, 1).orElseThrow();
        JsonNode layout = EngineJson.JSON.readTree(revision.layoutJson());

        // Then revision 1 holds the three steps and the flow, and not the unknown element
        assertThat(store.latestRevision("bank-a", id)).isEqualTo(1);
        assertThat(layout.get("nodes").propertyNames()).containsExactlyInAnyOrder("start", "verify-docs", "end");
        assertThat(layout.get("nodes").get("verify-docs").get("x").asDouble()).isEqualTo(200.0);
        assertThat(layout.get("nodes").get("verify-docs").get("width").asDouble()).isEqualTo(100.5);
        assertThat(layout.get("edges").get("start->verify-docs")).hasSize(2);
        // and it is stored as the file's own: source BPMN, the importer, the hash of the definition it was drawn for
        assertThat(revision.source()).isEqualTo("BPMN");
        assertThat(revision.createdBy()).isEqualTo("pipeline");
        assertThat(revision.definitionHash()).isEqualTo(v1.definitionHash());
        // and the definition does not carry the diagram, so the same file without one has the same hash
        DefinitionVersion plain = importAs("bank-b", V1, "");
        assertThat(plain.definitionHash()).isEqualTo(v1.definitionHash());
        assertThat(store.latestRevision("bank-b", definitionId("bank-b", 1))).as("no diagram, no layout").isZero();
    }

    @Test
    void PRE_215_each_import_has_its_own_revision_1_and_a_later_layout_change_needs_no_approval() {
        // Given two imports of the same process with different content and different diagrams
        importAs("bank-a", V1, diagram(0));
        importAs("bank-a", V2, diagram(50));
        String first = definitionId("bank-a", 1);
        String second = definitionId("bank-a", 2);

        // When the layout of version 1 is changed in the cockpit
        String hashBefore = jdbc.sql("SELECT status || definition_hash || lock_version FROM pre_definition WHERE "
                + "tenant_id = 'bank-a' AND id = ?").param(first).query(String.class).single();
        engine.identity.as("bank-a", "mai", "processes:author", "processes:read");
        Layouts layouts = engine.layouts();
        String moved = "{\"schemaVersion\":1,\"nodes\":{\"start\":{\"x\":5,\"y\":5}}}";
        LayoutRevision saved = layouts.saveLayout(new DefinitionRef(KEY, 1), 1, moved, "save-1");

        // Then both versions have their own revision 1, the save is revision 2, and no approval was requested
        assertThat(EngineJson.JSON.readTree(store.find("bank-a", first, 1).orElseThrow().layoutJson())
                .get("nodes").get("start").get("x").asInt()).isEqualTo(100);
        assertThat(EngineJson.JSON.readTree(store.find("bank-a", second, 1).orElseThrow().layoutJson())
                .get("nodes").get("start").get("x").asInt()).isEqualTo(150);
        assertThat(saved.revision()).isEqualTo(2);
        assertThat(store.latestRevision("bank-a", second)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status || definition_hash || lock_version FROM pre_definition WHERE "
                + "tenant_id = 'bank-a' AND id = ?").param(first).query(String.class).single()).isEqualTo(hashBefore);
        assertThat(jdbc.sql("SELECT count(*) FROM pre_definition_history WHERE tenant_id = 'bank-a' AND definition_id = ? "
                + "AND action IN ('REQUESTED','APPROVED')").param(first).query(Long.class).single()).isZero();
    }

    @Test
    void PRE_215_a_definition_stored_before_layouts_existed_still_reads_without_a_layout() {
        // Given a version imported by an engine that did not store layouts
        TestEngine plainEngine = TestEngine.replica("pod-2", clock).build();
        plainEngine.identity.as("bank-a", "pipeline", PIPELINE);
        plainEngine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML, bpmn(V1, diagram(0)), null));

        // When it is queried through its layout
        engine.identity.as("bank-a", "mai", "processes:author", "processes:read");

        // Then it has no revision and the layout read answers empty
        assertThat(engine.layouts().latestLayout(new DefinitionRef(KEY, 1))).isEmpty();
        assertThat(store.latestRevision("bank-a", definitionId("bank-a", 1))).isZero();
    }
}
