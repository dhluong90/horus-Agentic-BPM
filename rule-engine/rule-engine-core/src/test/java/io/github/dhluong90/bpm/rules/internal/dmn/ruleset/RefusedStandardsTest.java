package io.github.dhluong90.bpm.rules.internal.dmn.ruleset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.dhluong90.bpm.rules.api.SourceFormat;
import io.github.dhluong90.bpm.rules.api.UnsupportedElement;
import io.github.dhluong90.bpm.rules.api.UnsupportedStandardException;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.internal.read.RuleSetReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The rule engine refuses a CMMN file, DMN elements outside the current stage and other expression languages by name. */
class RefusedStandardsTest {

    private static final String DMN15 = "<definitions xmlns=\"https://www.omg.org/spec/DMN/20230324/MODEL/\" id=\"d\" name=\"d\">";
    private static final String TABLE = "<decision id=\"fee-table\" name=\"t\"><decisionTable hitPolicy=\"FIRST\">"
            + "<input><inputExpression typeRef=\"string\"><text>tier</text></inputExpression></input>"
            + "<output name=\"fee\" typeRef=\"number\"/>"
            + "<rule id=\"R1\"><inputEntry><text>-</text></inputEntry><outputEntry><text>0</text></outputEntry></rule>"
            + "</decisionTable></decision>";

    @Test
    void RLE_060_a_cmmn_file_is_refused_by_name_with_its_namespace_and_line() {
        // Given claim-case.cmmn, CMMN 1.1, with a casePlanModel on line 4
        byte[] file = DmnRuleSetTest.resource("/rules/dmn/claim-case.cmmn");

        // When it is read as a rule set version, Then it is CMMN_UNSUPPORTED at the root, line 1
        assertThatThrownBy(() -> RuleSetReader.read(SourceFormat.DMN_XML, file))
                .isInstanceOfSatisfying(UnsupportedStandardException.class, e -> {
                    assertThat(e.code()).isEqualTo("CMMN_UNSUPPORTED");
                    assertThat(e).hasMessage("CMMN is not supported; model the case as a BPMN process");
                    assertThat(e.namespace()).isEqualTo("http://www.omg.org/spec/CMMN/20151109/MODEL");
                    assertThat(e.line()).isEqualTo(1);
                    assertThat(e.elements()).isEmpty();
                });
    }

    @Test
    void RLE_060_the_reader_stops_at_the_root_so_no_other_check_runs() {
        // Given a CMMN root followed by text that is not well-formed and an element beyond every limit
        String file = "<definitions xmlns=\"http://www.omg.org/spec/CMMN/20121101/MODEL\"><case><unclosed>";

        // Then the refusal is still CMMN_UNSUPPORTED, not a malformed-XML validation failure
        assertThatThrownBy(() -> RuleSetReader.read(SourceFormat.DMN_XML, file.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(UnsupportedStandardException.class,
                        e -> assertThat(e.code()).isEqualTo("CMMN_UNSUPPORTED"));
    }

    @Test
    void RLE_060_a_root_in_another_unknown_namespace_is_a_validation_failure_naming_it() {
        assertThatThrownBy(() -> RuleSetReader.read(SourceFormat.DMN_XML,
                "<definitions xmlns=\"http://www.wfmc.org/2008/XPDL2.2\"/>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("http://www.wfmc.org/2008/XPDL2.2");
    }

    @Test
    void RLE_060_a_dtd_is_refused_before_the_namespace_is_looked_at() {
        String file = "<?xml version=\"1.0\"?><!DOCTYPE definitions [<!ENTITY x \"y\">]>"
                + "<definitions xmlns=\"http://www.omg.org/spec/CMMN/20151109/MODEL\"/>";

        assertThatThrownBy(() -> RuleSetReader.read(SourceFormat.DMN_XML, file.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("DTDs are not allowed");
    }

    @Test
    void RLE_061_dmn_elements_outside_the_stage_are_listed_with_id_line_and_stage_in_document_order() {
        // Given fees.dmn: a supported table, a wire to a decision (line 40), a knowledge model (55), a decision
        // service (70) and a context boxed expression (81)
        byte[] file = DmnRuleSetTest.resource("/rules/dmn/fees.dmn");

        assertThatThrownBy(() -> RuleSetReader.read(SourceFormat.DMN_XML, file))
                .isInstanceOfSatisfying(UnsupportedStandardException.class, e -> {
                    assertThat(e.code()).isEqualTo("DMN_UNSUPPORTED_ELEMENT");
                    assertThat(e.elements()).extracting(UnsupportedElement::element, UnsupportedElement::id,
                            UnsupportedElement::line, UnsupportedElement::stage).containsExactly(
                            tuple("informationRequirement", "risk-adj", 40, "S1"),
                            tuple("businessKnowledgeModel", "bkm-1", 55, "S2"),
                            tuple("decisionService", "ds-1", 70, "S2"),
                            tuple("context", "adjust", 81, "S2"));
                    assertThat(e.truncated()).isFalse();
                    assertThat(e.getMessage()).doesNotContain("sprint", "release");
                });
    }

    @Test
    void RLE_061_a_wire_to_input_data_is_not_a_refusal() {
        String file = DMN15 + "<inputData id=\"tier-in\" name=\"tier\"/>" + TABLE.replace("</decisionTable>",
                "</decisionTable><informationRequirement><requiredInput href=\"#tier-in\"/></informationRequirement>")
                + "</definitions>";

        assertThat(RuleSetReader.read(SourceFormat.DMN_XML, file.getBytes(StandardCharsets.UTF_8)).model().name())
                .isEqualTo("fee-table");
    }

    @Test
    void RLE_061_at_most_100_entries_are_listed_and_the_rest_set_the_truncated_flag() {
        StringBuilder file = new StringBuilder(DMN15).append(TABLE);
        for (int i = 0; i < 130; i++) {
            file.append("<knowledgeSource id=\"ks").append(i).append("\"/>");
        }
        file.append("</definitions>");

        assertThatThrownBy(() -> RuleSetReader.read(SourceFormat.DMN_XML, file.toString().getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(UnsupportedStandardException.class, e -> {
                    assertThat(e.elements()).hasSize(100);
                    assertThat(e.truncated()).isTrue();
                });
    }

    @Test
    void RLE_061_ids_lose_control_characters_and_are_cut_at_200_characters() {
        String id = "x".repeat(250) + "&#13;&#10;y";
        String file = DMN15 + TABLE + "<businessKnowledgeModel id=\"a&#13;&#10;b\"/><decisionService id=\"" + id + "\"/>"
                + "</definitions>";

        assertThatThrownBy(() -> RuleSetReader.read(SourceFormat.DMN_XML, file.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(UnsupportedStandardException.class, e -> {
                    assertThat(e.elements().get(0).id()).isEqualTo("ab");
                    assertThat(e.elements().get(1).id()).hasSize(200).doesNotContain("\r", "\n");
                });
    }

    @Test
    void RLE_061_an_unknown_expression_language_is_refused_with_its_line_and_stage() {
        String file = DMN15 + "\n" + TABLE.replace("<inputExpression ", "<inputExpression expressionLanguage=\"urn:groovy\" ")
                + "</definitions>";

        assertThatThrownBy(() -> RuleSetReader.read(SourceFormat.DMN_XML, file.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(UnsupportedStandardException.class, e -> {
                    assertThat(e.code()).isEqualTo("EXPRESSION_LANGUAGE_UNSUPPORTED");
                    assertThat(e.elements()).extracting(UnsupportedElement::element, UnsupportedElement::line,
                            UnsupportedElement::stage).containsExactly(tuple("inputExpression", 2, "not planned"));
                });
    }
}
