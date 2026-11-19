package io.github.dhluong90.bpm.process.bpmn.subset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.BpmnUnsafeXmlException;
import io.github.dhluong90.bpm.process.api.BpmnUnsupportedElementException;
import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.DefinitionTooLargeException;
import io.github.dhluong90.bpm.process.bpmn.xml.Limits;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorCatalog;
import io.github.dhluong90.bpm.process.internal.exec.CompiledProcess;
import io.github.dhluong90.bpm.process.internal.exec.ProcessCompiler;
import io.github.dhluong90.bpm.process.internal.model.bpmn.BusinessRuleTask;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ExclusiveGateway;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.model.horus.DecisionRef;
import io.github.dhluong90.bpm.process.internal.model.horus.InputMapping;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class SubsetBpmnReaderTest {

    private final SubsetBpmnReader reader = new SubsetBpmnReader(Limits.DEFAULTS);

    @Test
    void sample_reads_into_the_model_and_compiles() {
        Process process = reader.read(bytes("card-fee-review.bpmn"));

        assertThat(process.id()).isEqualTo("card-fee-review");
        CompiledProcess compiled = new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1", process);
        BusinessRuleTask price = (BusinessRuleTask) compiled.node("price-card").orElseThrow();
        assertThat(price.extensions().first(DecisionRef.class)).map(DecisionRef::ruleSet).contains("card-fee");
        assertThat(price.extensions().all(InputMapping.class)).extracting(InputMapping::name).containsExactly("tier", "txCount");
        assertThat(((ExclusiveGateway) compiled.node("fee-check").orElseThrow()).defaultFlow()).isEqualTo("fee-check__review");
        assertThat(compiled.flow("fee-check__auto").orElseThrow().conditionExpression().text()).isEqualTo("pricing.fee == 0 VND");
        assertThat(compiled.node("price-card").orElseThrow().incoming()).containsExactly("load-profile__price-card");
    }

    @Test
    void unsupported_elements_are_named_with_id_and_line() {
        assertThatThrownBy(() -> reader.read(bytes("unsupported.bpmn")))
                .isInstanceOf(BpmnUnsupportedElementException.class)
                .satisfies(e -> assertThat(((BpmnUnsupportedElementException) e).problems())
                        .extracting(DefinitionProblem::element, DefinitionProblem::elementId, DefinitionProblem::line)
                        .contains(org.assertj.core.groups.Tuple.tuple("boundaryEvent", "be-timeout", 6),
                                org.assertj.core.groups.Tuple.tuple("inclusiveGateway", "ig-1", 7),
                                org.assertj.core.groups.Tuple.tuple("endEvent with terminateEventDefinition", "end", 8)));
    }

    @Test
    void doctype_and_external_entities_are_refused_and_never_resolved() {
        assertThatThrownBy(() -> reader.read(bytes("xxe.bpmn"))).isInstanceOf(BpmnUnsafeXmlException.class);
    }

    @Test
    void a_condition_in_another_expression_language_is_refused() {
        assertThatThrownBy(() -> reader.read(bytes("feel-condition.bpmn")))
                .isInstanceOf(BpmnUnsupportedElementException.class)
                .hasMessageContaining("XPath");
    }

    @Test
    void size_and_depth_limits_apply() {
        assertThatThrownBy(() -> new SubsetBpmnReader(new Limits(100, 32, 5000, 0)).read(bytes("card-fee-review.bpmn")))
                .isInstanceOf(DefinitionTooLargeException.class);
        assertThatThrownBy(() -> new SubsetBpmnReader(new Limits(1_048_576, 2, 5000, 0)).read(bytes("card-fee-review.bpmn")))
                .isInstanceOf(BpmnUnsafeXmlException.class);
    }

    private static byte[] bytes(String name) {
        try (InputStream in = SubsetBpmnReaderTest.class.getResourceAsStream("/bpmn/" + name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
