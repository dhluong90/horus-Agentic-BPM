package io.github.dhluong90.bpm.process.bpmn.subset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.dhluong90.bpm.process.api.GraphDiff;
import io.github.dhluong90.bpm.process.bpmn.xml.Limits;
import io.github.dhluong90.bpm.process.internal.json.CanonicalProcessWriter;
import io.github.dhluong90.bpm.process.internal.json.Hashes;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.service.GraphDiffs;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The default flow of an activity is part of the model that is hashed, diffed and exported (PRE-373): two versions of
 * a credit check that differ only in which outgoing flow is the default are two different definitions.
 */
class ActivityDefaultFlowDefinitionTest {

    private static final SubsetBpmnReader READER = new SubsetBpmnReader(Limits.DEFAULTS);
    private static final GraphDiff.Side V1 = new GraphDiff.Side(1, "ACTIVE", "a".repeat(64));
    private static final GraphDiff.Side V2 = new GraphDiff.Side(2, "PENDING_APPROVAL", "b".repeat(64));

    /** A credit check that routes grade 700 and above to auto-approve; the {@code default} attribute is a parameter. */
    private static String creditCheck(String defaultAttribute) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:horus="urn:horus-bpm:bpmn:extensions:v1" id="d" targetNamespace="urn:t"
                                  expressionLanguage="urn:horus-bpm:expr:v1">
                  <bpmn:process id="credit" isExecutable="true">
                    <bpmn:startEvent id="start"/>
                    <bpmn:serviceTask id="grade" horus:handler="credit-grade" %s/>
                    <bpmn:serviceTask id="auto-approve" horus:handler="approve"/>
                    <bpmn:serviceTask id="manual-review" horus:handler="review"/>
                    <bpmn:endEvent id="end"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="grade"/>
                    <bpmn:sequenceFlow id="to-approve" sourceRef="grade" targetRef="auto-approve">
                      <bpmn:conditionExpression>score &gt;= 700</bpmn:conditionExpression>
                    </bpmn:sequenceFlow>
                    <bpmn:sequenceFlow id="to-review" sourceRef="grade" targetRef="manual-review"/>
                    <bpmn:sequenceFlow id="f4" sourceRef="auto-approve" targetRef="end"/>
                    <bpmn:sequenceFlow id="f5" sourceRef="manual-review" targetRef="end"/>
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(defaultAttribute);
    }

    private static Process read(String xml) {
        return READER.read(xml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void PRE_373_two_models_that_differ_only_in_the_default_flow_of_an_activity_have_different_hashes() {
        String withDefault = CanonicalProcessWriter.canonicalText(read(creditCheck("default=\"to-review\"")));
        String withoutDefault = CanonicalProcessWriter.canonicalText(read(creditCheck("")));
        String otherDefault = CanonicalProcessWriter.canonicalText(read(creditCheck("default=\"to-approve\"")));

        assertThat(withDefault).isNotEqualTo(withoutDefault).isNotEqualTo(otherDefault);
        assertThat(Hashes.sha256(withDefault))
                .isNotEqualTo(Hashes.sha256(withoutDefault));
        // A model with no default keeps its canonical text: no member is written for it.
        assertThat(withoutDefault).doesNotContain("defaultFlow");
    }

    @Test
    void PRE_373_the_canonical_json_and_the_bpmn_export_keep_the_default_flow_of_an_activity() {
        Process model = read(creditCheck("default=\"to-review\""));

        JsonNode grade = null;
        for (JsonNode step : CanonicalProcessWriter.write(model).get("steps")) {
            if ("grade".equals(step.get("id").asString())) {
                grade = step;
            }
        }
        assertThat(grade).isNotNull();
        assertThat(grade.get("defaultFlow").asString()).isEqualTo("to-review");

        // Exported and read again, the default is still there, and the hash is the same
        byte[] exported = new SubsetBpmnWriter().write(model);
        Process again = READER.read(exported);
        assertThat(CanonicalProcessWriter.canonicalText(again)).isEqualTo(CanonicalProcessWriter.canonicalText(model));
    }

    @Test
    void PRE_373_the_graph_diff_shows_a_changed_default_flow_of_an_activity() {
        GraphDiff diff = GraphDiffs.diff("credit", V1, read(creditCheck("")), V2,
                read(creditCheck("default=\"to-review\"")));

        assertThat(diff.identical()).isFalse();
        assertThat(diff.edgesRemoved()).extracting(GraphDiff.Edge::id, GraphDiff.Edge::kind)
                .containsExactly(tuple("to-review", "next"));
        assertThat(diff.edgesAdded()).extracting(GraphDiff.Edge::id, GraphDiff.Edge::kind)
                .containsExactly(tuple("to-review", "default"));
    }
}
