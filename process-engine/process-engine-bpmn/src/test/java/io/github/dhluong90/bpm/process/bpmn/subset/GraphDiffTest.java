package io.github.dhluong90.bpm.process.bpmn.subset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.dhluong90.bpm.process.api.GraphDiff;
import io.github.dhluong90.bpm.process.bpmn.xml.Limits;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.service.GraphDiffs;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The graph diff of two versions (PRE-025, PRE-176), on the models the BPMN reader builds: KYC onboarding v1
 * {@code start -> verify-docs -> approve -> end}; v2 adds a fraud check, gives the approval to a credit officer and
 * moves every box.
 */
class GraphDiffTest {

    private static final SubsetBpmnReader READER = new SubsetBpmnReader(Limits.DEFAULTS);
    private static final GraphDiff.Side V1 = new GraphDiff.Side(1, "ACTIVE", "a".repeat(64));
    private static final GraphDiff.Side V2 = new GraphDiff.Side(2, "PENDING_APPROVAL", "b".repeat(64));

    private static final String V1_XML = definitions("""
            <bpmn:startEvent id="start" name="Application received"/>
            <bpmn:userTask id="verify-docs" name="Verify documents" horus:assignee="role:ops" horus:resultVariable="verify_docs"/>
            <bpmn:userTask id="approve" name="Approve" horus:assignee="role:ops" horus:resultVariable="approve"/>
            <bpmn:endEvent id="end" name="Account opened"/>
            <bpmn:sequenceFlow id="start__verify" sourceRef="start" targetRef="verify-docs"/>
            <bpmn:sequenceFlow id="verify__approve" sourceRef="verify-docs" targetRef="approve"/>
            <bpmn:sequenceFlow id="approve__end" sourceRef="approve" targetRef="end"/>
            """, """
            <bpmndi:BPMNShape id="start_di" bpmnElement="start"><dc:Bounds x="100" y="100" width="36" height="36"/></bpmndi:BPMNShape>
            <bpmndi:BPMNShape id="verify_di" bpmnElement="verify-docs"><dc:Bounds x="200" y="80" width="100" height="80"/></bpmndi:BPMNShape>
            <bpmndi:BPMNShape id="approve_di" bpmnElement="approve"><dc:Bounds x="350" y="80" width="100" height="80"/></bpmndi:BPMNShape>
            <bpmndi:BPMNShape id="end_di" bpmnElement="end"><dc:Bounds x="500" y="100" width="36" height="36"/></bpmndi:BPMNShape>
            """);

    private static final String V2_XML = definitions("""
            <bpmn:startEvent id="start" name="Application received"/>
            <bpmn:userTask id="verify-docs" name="Verify documents" horus:assignee="role:ops" horus:resultVariable="verify_docs"/>
            <bpmn:serviceTask id="fraud-check" name="Fraud check" horus:handler="fraud.screen"/>
            <bpmn:userTask id="approve" name="Approve" horus:assignee="role:credit-officer" horus:resultVariable="approve"/>
            <bpmn:endEvent id="end" name="Account opened"/>
            <bpmn:sequenceFlow id="start__verify" sourceRef="start" targetRef="verify-docs"/>
            <bpmn:sequenceFlow id="verify__fraud" sourceRef="verify-docs" targetRef="fraud-check"/>
            <bpmn:sequenceFlow id="fraud__approve" sourceRef="fraud-check" targetRef="approve"/>
            <bpmn:sequenceFlow id="approve__end" sourceRef="approve" targetRef="end"/>
            """, """
            <bpmndi:BPMNShape id="start_di" bpmnElement="start"><dc:Bounds x="140" y="300" width="36" height="36"/></bpmndi:BPMNShape>
            <bpmndi:BPMNShape id="verify_di" bpmnElement="verify-docs"><dc:Bounds x="260" y="280" width="100" height="80"/></bpmndi:BPMNShape>
            <bpmndi:BPMNShape id="fraud_di" bpmnElement="fraud-check"><dc:Bounds x="420" y="280" width="100" height="80"/></bpmndi:BPMNShape>
            <bpmndi:BPMNShape id="approve_di" bpmnElement="approve"><dc:Bounds x="580" y="280" width="100" height="80"/></bpmndi:BPMNShape>
            <bpmndi:BPMNShape id="end_di" bpmnElement="end"><dc:Bounds x="740" y="300" width="36" height="36"/></bpmndi:BPMNShape>
            """);

    private static String definitions(String process, String shapes) {
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
                  <bpmndi:BPMNDiagram id="Diagram_kyc">
                    <bpmndi:BPMNPlane id="Plane_kyc" bpmnElement="kyc-onboarding">
                %s
                    </bpmndi:BPMNPlane>
                  </bpmndi:BPMNDiagram>
                </bpmn:definitions>
                """.formatted(process, shapes);
    }

    private static Process read(String xml) {
        return READER.read(xml.getBytes(StandardCharsets.UTF_8));
    }

    private static GraphDiff diff(String base, String target) {
        return GraphDiffs.diff("kyc-onboarding", V1, read(base), V2, read(target));
    }

    @Test
    void PRE_025_graph_diff_reports_nodes_and_edges() {
        GraphDiff diff = diff(V1_XML, V2_XML);

        assertThat(diff.nodesAdded()).containsExactly(new GraphDiff.Node("fraud-check", "Fraud check", "serviceTask"));
        assertThat(diff.nodesRemoved()).isEmpty();
        assertThat(diff.nodesChanged()).extracting(GraphDiff.NodeChange::id, GraphDiff.NodeChange::path,
                GraphDiff.NodeChange::from, GraphDiff.NodeChange::to)
                .containsExactly(tuple("approve", "/assignee/role", "ops", "credit-officer"));
        assertThat(diff.nodesChanged().get(0).name()).isEqualTo("Approve");
        assertThat(diff.edgesRemoved()).extracting(GraphDiff.Edge::from, GraphDiff.Edge::to, GraphDiff.Edge::kind)
                .containsExactly(tuple("verify-docs", "approve", "next"));
        assertThat(diff.edgesAdded()).extracting(GraphDiff.Edge::from, GraphDiff.Edge::to, GraphDiff.Edge::kind)
                .containsExactly(tuple("fraud-check", "approve", "next"), tuple("verify-docs", "fraud-check", "next"));
        assertThat(diff.edgesAdded()).extracting(GraphDiff.Edge::id, GraphDiff.Edge::fromName, GraphDiff.Edge::toName)
                .containsExactly(tuple("fraud__approve", "Fraud check", "Approve"),
                        tuple("verify__fraud", "Verify documents", "Fraud check"));
        // Every box moved, and nothing about positions is reported.
        assertThat(diff.toString()).doesNotContain("Bounds").doesNotContain("x=").doesNotContain("<");
        assertThat(diff.truncated()).isFalse();
        assertThat(diff.identical()).isFalse();
    }

    @Test
    void PRE_176_the_same_versions_always_give_the_same_diff_whatever_the_element_order_or_layout() {
        // The same v2 with its flows written first and every box moved again.
        String reordered = definitions("""
                <bpmn:sequenceFlow id="approve__end" sourceRef="approve" targetRef="end"/>
                <bpmn:sequenceFlow id="fraud__approve" sourceRef="fraud-check" targetRef="approve"/>
                <bpmn:sequenceFlow id="verify__fraud" sourceRef="verify-docs" targetRef="fraud-check"/>
                <bpmn:sequenceFlow id="start__verify" sourceRef="start" targetRef="verify-docs"/>
                <bpmn:startEvent id="start" name="Application received"/>
                <bpmn:userTask id="verify-docs" name="Verify documents" horus:assignee="role:ops" horus:resultVariable="verify_docs"/>
                <bpmn:serviceTask id="fraud-check" name="Fraud check" horus:handler="fraud.screen"/>
                <bpmn:userTask id="approve" name="Approve" horus:assignee="role:credit-officer" horus:resultVariable="approve"/>
                <bpmn:endEvent id="end" name="Account opened"/>
                """, """
                <bpmndi:BPMNShape id="start_di" bpmnElement="start"><dc:Bounds x="1" y="1" width="36" height="36"/></bpmndi:BPMNShape>
                """);

        GraphDiff first = diff(V1_XML, V2_XML);

        assertThat(diff(V1_XML, V2_XML)).isEqualTo(first);
        assertThat(diff(V1_XML, reordered)).isEqualTo(first);
        assertThat(diff(V2_XML, reordered).identical()).as("layout and element order change nothing").isTrue();
    }

    @Test
    void PRE_176_swapping_the_versions_gives_the_mirror_image() {
        GraphDiff forward = diff(V1_XML, V2_XML);
        GraphDiff back = diff(V2_XML, V1_XML);

        assertThat(back.nodesRemoved()).isEqualTo(forward.nodesAdded());
        assertThat(back.nodesAdded()).isEqualTo(forward.nodesRemoved());
        assertThat(back.edgesAdded()).extracting(GraphDiff.Edge::from, GraphDiff.Edge::to, GraphDiff.Edge::kind)
                .isEqualTo(forward.edgesRemoved().stream().map(e -> tuple(e.from(), e.to(), e.kind())).toList());
        assertThat(back.nodesChanged()).extracting(GraphDiff.NodeChange::id, GraphDiff.NodeChange::path,
                GraphDiff.NodeChange::from, GraphDiff.NodeChange::to)
                .containsExactly(tuple("approve", "/assignee/role", "credit-officer", "ops"));
    }

    @Test
    void PRE_176_branches_are_links_by_kind_and_a_changed_condition_is_a_value_of_the_gateway() {
        String gateway = """
                <bpmn:startEvent id="start"/>
                <bpmn:exclusiveGateway id="amount-check" name="Large loan?" default="amount-check__auto"/>
                <bpmn:userTask id="committee" name="Credit committee" horus:assignee="role:committee" horus:resultVariable="committee"/>
                <bpmn:userTask id="auto" name="Officer approval" horus:assignee="role:officer" horus:resultVariable="auto"/>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="start__check" sourceRef="start" targetRef="amount-check"/>
                <bpmn:sequenceFlow id="amount-check__committee" sourceRef="amount-check" targetRef="committee">
                  <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression" language="urn:horus-bpm:expr:v1">%s</bpmn:conditionExpression>
                </bpmn:sequenceFlow>
                <bpmn:sequenceFlow id="amount-check__auto" sourceRef="amount-check" targetRef="%s"/>
                <bpmn:sequenceFlow id="committee__end" sourceRef="committee" targetRef="end"/>
                <bpmn:sequenceFlow id="auto__end" sourceRef="auto" targetRef="end"/>
                """;
        String before = definitions(gateway.formatted("amount > 500000000 VND", "auto"), "");
        String after = definitions(gateway.formatted("amount > 1000000000 VND", "committee"), "");

        GraphDiff diff = diff(before, after);

        assertThat(diff.nodesChanged()).extracting(GraphDiff.NodeChange::id, GraphDiff.NodeChange::path,
                GraphDiff.NodeChange::from, GraphDiff.NodeChange::to)
                .containsExactly(tuple("amount-check", "/branches/0/when", "amount > 500000000 VND",
                        "amount > 1000000000 VND"));
        assertThat(diff.edgesRemoved()).extracting(GraphDiff.Edge::from, GraphDiff.Edge::to, GraphDiff.Edge::kind)
                .containsExactly(tuple("amount-check", "auto", "default"));
        assertThat(diff.edgesAdded()).extracting(GraphDiff.Edge::from, GraphDiff.Edge::to, GraphDiff.Edge::kind)
                .containsExactly(tuple("amount-check", "committee", "default"));
        assertThat(diff.edgesAdded().get(0).id()).isEqualTo("amount-check__auto");
    }

    @Test
    void PRE_176_at_most_a_thousand_changed_values_are_listed_and_the_rest_set_truncated() {
        // 600 review steps, each renamed and given to another role: 1,200 changed values.
        StringBuilder before = new StringBuilder("<bpmn:startEvent id=\"start\"/>");
        StringBuilder after = new StringBuilder("<bpmn:startEvent id=\"start\"/>");
        String previous = "start";
        for (int i = 0; i < 600; i++) {
            String id = "review-" + String.format("%03d", i);
            String task = "<bpmn:userTask id=\"%s\" name=\"%s %d\" horus:assignee=\"role:%s\" horus:resultVariable=\"r%d\"/>";
            before.append(task.formatted(id, "Review", i, "ops", i));
            after.append(task.formatted(id, "Check", i, "risk", i));
            String flow = "<bpmn:sequenceFlow id=\"%s__%s\" sourceRef=\"%s\" targetRef=\"%s\"/>"
                    .formatted(previous, id, previous, id);
            before.append(flow);
            after.append(flow);
            previous = id;
        }
        String end = "<bpmn:endEvent id=\"end\"/><bpmn:sequenceFlow id=\"last__end\" sourceRef=\"%s\" targetRef=\"end\"/>"
                .formatted(previous);
        before.append(end);
        after.append(end);

        GraphDiff diff = diff(definitions(before.toString(), ""), definitions(after.toString(), ""));

        assertThat(diff.nodesChanged()).hasSize(GraphDiff.MAX_CHANGES);
        assertThat(diff.truncated()).isTrue();
        assertThat(diff.nodesChanged().get(0)).extracting(GraphDiff.NodeChange::id, GraphDiff.NodeChange::path)
                .containsExactly("review-000", "/assignee/role");
        List<String> order = diff.nodesChanged().stream().map(c -> c.id() + c.path()).toList();
        assertThat(order).isSorted();
    }
}
