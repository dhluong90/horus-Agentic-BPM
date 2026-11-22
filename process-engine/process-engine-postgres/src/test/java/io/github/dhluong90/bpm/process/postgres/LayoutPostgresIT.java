package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.DefinitionNotFoundException;
import io.github.dhluong90.bpm.process.api.DefinitionRef;
import io.github.dhluong90.bpm.process.api.DefinitionVersion;
import io.github.dhluong90.bpm.process.api.IdempotencyKeyReuseException;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.LayoutInvalidException;
import io.github.dhluong90.bpm.process.api.LayoutRevision;
import io.github.dhluong90.bpm.process.api.PermissionDeniedException;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.VersionConflictException;
import io.github.dhluong90.bpm.process.internal.json.CanonicalJson;
import io.github.dhluong90.bpm.process.internal.json.EngineJson;
import io.github.dhluong90.bpm.process.internal.service.Layouts;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Diagram layout stored apart from the definition (#102, PRE-026, PRE-024). KYC onboarding v2 is active and has
 * layout revisions; a layout is appended with {@code If-Match}, needs no approval and never changes the definition.
 */
class LayoutPostgresIT {

    private static final String KEY = "kyc-onboarding";
    private static final String[] PIPELINE = {"processes:deploy", "processes:read"};
    private static final String AUTHOR = "processes:author";

    private static final String V1 = """
            <bpmn:startEvent id="start" name="Application received"/>
            <bpmn:userTask id="verify-docs" name="Verify documents" horus:assignee="role:ops" horus:resultVariable="docs"/>
            <bpmn:userTask id="approve" name="Approve" horus:assignee="role:ops" horus:resultVariable="approval"/>
            <bpmn:endEvent id="end" name="Account opened"/>
            <bpmn:sequenceFlow id="start__verify" sourceRef="start" targetRef="verify-docs"/>
            <bpmn:sequenceFlow id="verify__approve" sourceRef="verify-docs" targetRef="approve"/>
            <bpmn:sequenceFlow id="approve__end" sourceRef="approve" targetRef="end"/>
            """;
    /** The same process with its attributes written in another order. */
    private static final String V1_REORDERED = """
            <bpmn:startEvent name="Application received" id="start"/>
            <bpmn:userTask horus:resultVariable="docs" horus:assignee="role:ops" name="Verify documents" id="verify-docs"/>
            <bpmn:userTask horus:resultVariable="approval" horus:assignee="role:ops" name="Approve" id="approve"/>
            <bpmn:endEvent name="Account opened" id="end"/>
            <bpmn:sequenceFlow targetRef="verify-docs" sourceRef="start" id="start__verify"/>
            <bpmn:sequenceFlow targetRef="approve" sourceRef="verify-docs" id="verify__approve"/>
            <bpmn:sequenceFlow targetRef="end" sourceRef="approve" id="approve__end"/>
            """;
    private static final String V2 = """
            <bpmn:startEvent id="start" name="Application received"/>
            <bpmn:userTask id="verify-docs" name="Verify documents" horus:assignee="role:ops" horus:resultVariable="docs"/>
            <bpmn:userTask id="approve" name="Approve" horus:assignee="role:credit-officer" horus:resultVariable="approval"/>
            <bpmn:endEvent id="end" name="Account opened"/>
            <bpmn:sequenceFlow id="start__verify" sourceRef="start" targetRef="verify-docs"/>
            <bpmn:sequenceFlow id="verify__approve" sourceRef="verify-docs" targetRef="approve"/>
            <bpmn:sequenceFlow id="approve__end" sourceRef="approve" targetRef="end"/>
            """;

    private final TestClock clock = TestClock.at("2027-03-01T02:00:00Z");
    private TestEngine engine;
    private Layouts layouts;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        TestEngine.reset();
        engine = TestEngine.replica("pod-1", clock).build();
        layouts = engine.layouts();
        jdbc = TestEngine.jdbc();
    }

    private static byte[] bpmn(String process, String diagram) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                                  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
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

    private static String diagram(int dx, int dy) {
        return """
                  <bpmndi:BPMNDiagram id="Diagram_kyc">
                    <bpmndi:BPMNPlane id="Plane_kyc" bpmnElement="kyc-onboarding">
                      <bpmndi:BPMNShape id="start_di" bpmnElement="start"><dc:Bounds x="%d" y="%d" width="36" height="36"/></bpmndi:BPMNShape>
                      <bpmndi:BPMNShape id="verify_di" bpmnElement="verify-docs"><dc:Bounds x="%d" y="%d" width="100" height="80"/></bpmndi:BPMNShape>
                      <bpmndi:BPMNShape id="approve_di" bpmnElement="approve"><dc:Bounds x="%d" y="%d" width="100" height="80"/></bpmndi:BPMNShape>
                    </bpmndi:BPMNPlane>
                  </bpmndi:BPMNDiagram>
                """.formatted(100 + dx, 100 + dy, 200 + dx, 80 + dy, 350 + dx, 80 + dy);
    }

    private DefinitionVersion importAs(String tenant, String process, String diagram) {
        engine.identity.as(tenant, "pipeline", PIPELINE);
        return engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML, bpmn(process, diagram), null));
    }

    private void as(String tenant, String caller, String... permissions) {
        engine.identity.as(tenant, caller, permissions);
    }

    private static String layout(int x) {
        return "{\"schemaVersion\":1,\"direction\":\"LR\",\"nodes\":{\"start\":{\"x\":" + x
                + ",\"y\":120},\"verify-docs\":{\"x\":" + (x + 160) + ",\"y\":100,\"width\":140,\"height\":80}}}";
    }

    private String definitionRow(String tenant) {
        return jdbc.sql("SELECT string_agg(version_num || ':' || status || ':' || definition_hash || ':' || lock_version "
                + "|| ':' || md5(definition_json::text), '|' ORDER BY version_num) FROM pre_definition "
                + "WHERE tenant_id = ?").param(tenant).query(String.class).single();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private static String independentHash(String definitionJson) throws Exception {
        // The published reference: remove the diagram part, print RFC 8785, hash with SHA-256.
        JsonNode node = EngineJson.JSON.readTree(definitionJson);
        if (node instanceof ObjectNode object) {
            object.remove("diagram");
        }
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(CanonicalJson.write(node).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    @Test
    void PRE_026_layout_revision_append_only_without_approval() {
        // Given kyc-onboarding v2 ACTIVE with layout revision 3; carol and dave hold processes:author
        importAs("bank-a", V1, "");
        DefinitionVersion v2 = importAs("bank-a", V2, "");
        assertThat(v2.status()).isEqualTo("ACTIVE");
        DefinitionRef ref = DefinitionRef.of(KEY, 2);
        as("bank-a", "carol", AUTHOR, "processes:read");
        for (int expected = 0; expected < 3; expected++) {
            layouts.saveLayout(ref, expected, layout(40 + expected), null);
        }
        String rowBefore = definitionRow("bank-a");
        long historyBefore = count("pre_definition_history");
        int eventsBefore = engine.events.size();

        // When carol saves with If-Match 3, dave then saves with If-Match 3, and carol saves a layout naming ghost-step
        LayoutRevision saved = layouts.saveLayout(ref, 3, layout(300), null);
        as("bank-a", "dave", AUTHOR);
        assertThatThrownBy(() -> layouts.saveLayout(ref, 3, layout(500), null))
                .isInstanceOf(VersionConflictException.class);
        as("bank-a", "carol", AUTHOR, "processes:read");
        assertThatThrownBy(() -> layouts.saveLayout(ref, 4,
                "{\"schemaVersion\":1,\"nodes\":{\"ghost-step\":{\"x\":1,\"y\":2}}}", null))
                .isInstanceOfSatisfying(LayoutInvalidException.class, e -> assertThat(e.problems())
                        .containsExactly("$.nodes.ghost-step names a step that is not in the definition"));

        // Then carol's save is revision 4 by carol, the definition is as it was and no approval was asked for
        assertThat(saved.revision()).isEqualTo(4);
        assertThat(saved.createdBy()).isEqualTo("carol");
        assertThat(saved.definitionHash()).isEqualTo(v2.definitionHash());
        assertThat(definitionRow("bank-a")).as("status, hash, lock version and content of every version")
                .isEqualTo(rowBefore);
        assertThat(count("pre_definition_history")).as("no lifecycle row, so no approval was requested")
                .isEqualTo(historyBefore);
        assertThat(engine.events.subList(eventsBefore, engine.events.size())).extracting(e -> e.type())
                .containsExactly("processes.definition.layout-saved.v1");
        // and revisions 1 to 4 remain readable, the refused saves left none
        for (int revision = 1; revision <= 4; revision++) {
            assertThat(layouts.layoutRevision(ref, revision)).isPresent();
        }
        assertThat(layouts.layoutRevision(ref, 5)).isEmpty();
        assertThat(layouts.layoutRevisions(ref, 0, 50)).extracting(LayoutRevision::revision)
                .containsExactly(1, 2, 3, 4);
        assertThat(layouts.latestLayout(ref)).get().satisfies(latest -> {
            assertThat(latest.revision()).isEqualTo(4);
            assertThat(latest.layoutJson()).contains("\"x\":300");
        });
    }

    @Test
    void PRE_026_two_saves_over_the_same_revision_at_once_make_exactly_one_revision() throws Exception {
        // Given v1 with revision 1, and two replicas whose authors both saw it
        importAs("bank-a", V1, "");
        DefinitionRef ref = DefinitionRef.of(KEY, 1);
        as("bank-a", "carol", AUTHOR);
        layouts.saveLayout(ref, 0, layout(40), null);
        TestEngine second = TestEngine.replica("pod-2", clock).build();
        second.identity.as("bank-a", "dave", AUTHOR);
        Layouts other = second.layouts();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // When both save over revision 1 together
            List<Callable<Object>> calls = List.of(() -> attempt(layouts, ref, 100, start),
                    () -> attempt(other, ref, 200, start));
            List<Future<Object>> futures = new ArrayList<>();
            calls.forEach(c -> futures.add(pool.submit(c)));
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) {
                results.add(f.get());
            }

            // Then one is revision 2 and the other is 409 VERSION_CONFLICT; no revision was lost or doubled
            assertThat(results).filteredOn(LayoutRevision.class::isInstance).hasSize(1);
            assertThat(results).filteredOn(VersionConflictException.class::isInstance).hasSize(1);
            assertThat(jdbc.sql("SELECT string_agg(revision::text, ',' ORDER BY revision) FROM pre_definition_layout")
                    .query(String.class).single()).isEqualTo("1,2");
        } finally {
            pool.shutdownNow();
            second.close();
        }
    }

    private static Object attempt(Layouts service, DefinitionRef ref, int x, CountDownLatch start) throws Exception {
        start.await();
        try {
            return service.saveLayout(ref, 1, layout(x), null);
        } catch (VersionConflictException e) {
            return e;
        }
    }

    @Test
    void PRE_026_the_table_refuses_to_change_or_delete_a_revision() {
        importAs("bank-a", V1, "");
        as("bank-a", "carol", AUTHOR);
        layouts.saveLayout(DefinitionRef.of(KEY, 1), 0, layout(40), null);

        assertThatThrownBy(() -> jdbc.sql("UPDATE pre_definition_layout SET layout_hash = repeat('0', 64)").update())
                .hasMessageContaining("pre_definition_layout is append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM pre_definition_layout").update())
                .hasMessageContaining("pre_definition_layout is append-only");
        assertThat(count("pre_definition_layout")).isEqualTo(1);
    }

    @Test
    void PRE_199_a_layout_is_validated_against_its_schema_and_the_steps_of_the_version() {
        importAs("bank-a", V1, "");
        DefinitionRef ref = DefinitionRef.of(KEY, 1);
        as("bank-a", "carol", AUTHOR);

        assertThatThrownBy(() -> layouts.saveLayout(ref, 0, "{\"schemaVersion\":2,\"nodes\":{\"start\":{\"x\":\"left\","
                + "\"y\":1,\"colour\":\"red\"}},\"lanes\":[{\"id\":\"Ops\",\"label\":\"Ops\",\"nodes\":[\"start\","
                + "\"start\",\"nowhere\"]}],\"edges\":{\"start->ghost\":[{\"x\":1,\"y\":2}]},\"extra\":1}", null))
                .isInstanceOfSatisfying(LayoutInvalidException.class, e -> assertThat(e.problems()).containsExactly(
                        "$.extra is not a layout member", "$.schemaVersion must be 1",
                        "$.nodes.start.colour is not a box member", "$.nodes.start.x must be a number within 100000",
                        "$.edges.start->ghost names a step that is not in the definition",
                        "$.lanes[0].id must be a lower-case kebab-case id",
                        "$.lanes[0].nodes[1] repeats a step of the lane",
                        "$.lanes[0].nodes[2] names a step that is not in the definition"));
        assertThat(count("pre_definition_layout")).isZero();
    }

    @Test
    void PRE_199_a_save_is_idempotent_and_a_key_is_not_reused_for_another_layout() {
        importAs("bank-a", V1, "");
        DefinitionRef ref = DefinitionRef.of(KEY, 1);
        as("bank-a", "carol", AUTHOR);

        LayoutRevision first = layouts.saveLayout(ref, 0, layout(40), "save-1");
        LayoutRevision repeat = layouts.saveLayout(ref, 0, layout(40), "save-1");

        assertThat(repeat).isEqualTo(first);
        assertThat(count("pre_definition_layout")).isEqualTo(1);
        assertThatThrownBy(() -> layouts.saveLayout(ref, 0, layout(90), "save-1"))
                .isInstanceOf(IdempotencyKeyReuseException.class);
    }

    @Test
    void PRE_199_saving_needs_the_author_permission_and_reading_the_read_permission() {
        importAs("bank-a", V1, "");
        DefinitionRef ref = DefinitionRef.of(KEY, 1);
        as("bank-a", "viewer", "processes:read");

        assertThatThrownBy(() -> layouts.saveLayout(ref, 0, layout(40), null))
                .isInstanceOf(PermissionDeniedException.class);
        as("bank-a", "nobody", "processes:start");
        assertThatThrownBy(() -> layouts.latestLayout(ref)).isInstanceOf(PermissionDeniedException.class);
        assertThat(count("pre_definition_layout")).isZero();
    }

    @Test
    void PRE_199_another_tenant_can_neither_read_nor_save_a_layout_and_nothing_is_written() {
        // Given bank-a's v1 with a layout, and a bank-b caller with every layout permission
        importAs("bank-a", V1, "");
        DefinitionRef ref = DefinitionRef.of(KEY, 1);
        as("bank-a", "carol", AUTHOR);
        layouts.saveLayout(ref, 0, layout(40), null);
        long layoutRows = count("pre_definition_layout");
        int events = engine.events.size();
        as("bank-b", "mallory", AUTHOR, "processes:read");

        // When bank-b reads, lists and saves bank-a's layout
        // Then each is DEFINITION_NOT_FOUND, as if the version did not exist, and no row or event is written
        assertThatThrownBy(() -> layouts.latestLayout(ref)).isInstanceOf(DefinitionNotFoundException.class);
        assertThatThrownBy(() -> layouts.layoutRevision(ref, 1)).isInstanceOf(DefinitionNotFoundException.class);
        assertThatThrownBy(() -> layouts.layoutRevisions(ref, 0, 10)).isInstanceOf(DefinitionNotFoundException.class);
        assertThatThrownBy(() -> layouts.saveLayout(ref, 1, layout(500), null))
                .isInstanceOf(DefinitionNotFoundException.class);
        assertThat(count("pre_definition_layout")).isEqualTo(layoutRows);
        assertThat(engine.events).hasSize(events);

        // and bank-b's own version has its own, separate layout history
        importAs("bank-b", V1, "");
        as("bank-b", "mallory", AUTHOR, "processes:read");
        assertThat(layouts.latestLayout(ref)).isEmpty();
        assertThat(layouts.saveLayout(ref, 0, layout(7), null).revision()).isEqualTo(1);
        as("bank-a", "carol", "processes:read");
        assertThat(layouts.latestLayout(ref)).get().satisfies(l -> assertThat(l.layoutJson()).contains("\"x\":40"));
    }

    @Test
    void PRE_024_content_hash_excludes_diagram_and_matches_git() throws Exception {
        // Given a BPMN file with a diagram part, and the same file with its attributes in another order and every box moved
        // (the engine reads BPMN until the v1 JSON reader ships; the diagram part is BPMNDI)
        TestEngine drafts = TestEngine.replica("pod-3", clock).autoApprove(false).build();
        try {
            drafts.identity.as("bank-a", "pipeline", PIPELINE);
            DefinitionVersion withDiagram = drafts.services.importDefinition(
                    new ImportRequest(SourceFormat.BPMN_XML, bpmn(V1, diagram(0, 0)), null));

            // When the moved and reordered file is imported again, and by another tenant, and a file without any diagram
            DefinitionVersion moved = drafts.services.importDefinition(
                    new ImportRequest(SourceFormat.BPMN_XML, bpmn(V1_REORDERED, diagram(480, 260)), null));
            drafts.identity.as("bank-b", "pipeline", PIPELINE);
            DefinitionVersion otherTenant = drafts.services.importDefinition(
                    new ImportRequest(SourceFormat.BPMN_XML, bpmn(V1_REORDERED, ""), null));

            // Then all carry the hash the published reference gives, and the same content is no new version
            String stored = jdbc.sql("SELECT definition_json::text FROM pre_definition WHERE tenant_id = 'bank-a'")
                    .query(String.class).single();
            assertThat(withDiagram.definitionHash()).isEqualTo(independentHash(stored));
            assertThat(moved.definitionHash()).isEqualTo(withDiagram.definitionHash());
            assertThat(moved.version()).as("a moved diagram is not a new version").isEqualTo(1);
            assertThat(otherTenant.definitionHash()).isEqualTo(withDiagram.definitionHash());
            assertThat(count("pre_definition")).isEqualTo(2);
            assertThat(stored).doesNotContainIgnoringCase("bounds").doesNotContainIgnoringCase("diagram")
                    .doesNotContain("bpmndi");
            // and a layout saved afterwards leaves the hash alone
            engine.identity.as("bank-a", "carol", AUTHOR);
            LayoutRevision saved = layouts.saveLayout(DefinitionRef.of(KEY, 1), 0, layout(1), null);
            assertThat(saved.definitionHash()).isEqualTo(withDiagram.definitionHash());
            assertThat(jdbc.sql("SELECT definition_hash FROM pre_definition WHERE tenant_id = 'bank-a'")
                    .query(String.class).single()).isEqualTo(withDiagram.definitionHash());
        } finally {
            drafts.close();
        }
    }

    @Test
    void PRE_026_a_layout_can_be_saved_for_a_draft() {
        TestEngine drafts = TestEngine.replica("pod-4", clock).autoApprove(false).build();
        try {
            drafts.identity.as("bank-a", "pipeline", PIPELINE);
            drafts.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML, bpmn(V1, ""), null));
            drafts.identity.as("bank-a", "carol", AUTHOR);
            Layouts draftLayouts = drafts.layouts();

            assertThat(draftLayouts.saveLayout(DefinitionRef.of(KEY, 1), 0, layout(10), null).revision()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT status FROM pre_definition").query(String.class).single()).isEqualTo("DRAFT");
        } finally {
            drafts.close();
        }
    }
}
