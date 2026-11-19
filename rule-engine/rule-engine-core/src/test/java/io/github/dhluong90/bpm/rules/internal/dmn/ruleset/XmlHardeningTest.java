package io.github.dhluong90.bpm.rules.internal.dmn.ruleset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.horus.bpm.rules.api.SourceFormat;
import com.horus.bpm.rules.api.ValidationFailedException;
import com.horus.bpm.rules.internal.read.RuleSetReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.xml.stream.XMLResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Findings M2, L1, L2 and L3 of the security review of the DMN import. */
class XmlHardeningTest {

    static final String CELL_TABLE = DmnRuleSetTest.table(
            "<input><inputExpression typeRef=\"string\"><text>merchant</text></inputExpression></input>",
            "<rule id=\"R1\"><inputEntry><text>\"café\"</text></inputEntry><outputEntry><text>true</text></outputEntry></rule>");

    // ------------------------------------------------------------------ M2: the encoding the parser reads is the stored one

    @ParameterizedTest
    @ValueSource(strings = {"ISO-8859-1", "windows-1252", "US-ASCII", "UTF-16"})
    void TR_STD_25_an_xml_declaration_with_an_encoding_other_than_utf_8_is_refused(String encoding) {
        byte[] file = ("<?xml version=\"1.0\" encoding=\"" + encoding + "\"?>" + CELL_TABLE).getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> RuleSetReader.read(SourceFormat.DMN_XML, file))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("The XML declaration names the encoding '" + encoding + "'; DMN files must be UTF-8");
    }

    @ParameterizedTest
    @ValueSource(strings = {"<?xml version=\"1.0\" encoding=\"UTF-8\"?>", "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
        "<?xml version=\"1.0\"?>", "", "﻿<?xml version=\"1.0\" encoding=\"UTF-8\"?>"})
    void TR_STD_25_utf_8_documents_parse_to_exactly_the_stored_text(String prolog) {
        byte[] file = (prolog + CELL_TABLE).getBytes(StandardCharsets.UTF_8);

        String canonical = RuleSetReader.read(SourceFormat.DMN_XML, file).canonicalJson();

        assertThat(canonical).contains("\"merchant\":\"== 'café'\"");
    }

    @Test
    void TR_STD_25_bytes_that_are_not_utf_8_are_refused() {
        byte[] latin1 = CELL_TABLE.getBytes(StandardCharsets.ISO_8859_1);

        assertThatThrownBy(() -> RuleSetReader.read(SourceFormat.DMN_XML, latin1))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("not valid UTF-8");
    }

    // ------------------------------------------------------------------ L2: the factory settings alone stop entities

    @ParameterizedTest
    @ValueSource(strings = {
        "<!DOCTYPE d [<!ENTITY x SYSTEM \"http://127.0.0.1:9/x\">]><d>&x;</d>",
        "<!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><d>&x;</d>",
        "<!DOCTYPE d SYSTEM \"http://127.0.0.1:9/d.dtd\"><d/>",
        "<!DOCTYPE d [<!ENTITY % p SYSTEM \"http://127.0.0.1:9/p\"> %p;]><d/>",
        "<!DOCTYPE d [<!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;\">]><d>&b;</d>",
        "<!DOCTYPE d [<!ENTITY i \"internal\">]><d>&i;</d>",
    })
    void TR_STD_25_with_the_doctype_check_bypassed_no_entity_is_expanded_and_nothing_is_fetched(String document) {
        // Given a resolver that records every call, and the DOCTYPE refusal switched off
        List<String> calls = new ArrayList<>();
        XMLResolver recorder = (publicId, systemId, baseUri, namespace) -> {
            calls.add(systemId);
            return null;
        };

        // When
        XmlTree tree = null;
        try {
            tree = XmlTree.parse(document.getBytes(StandardCharsets.UTF_8), false, recorder);
        } catch (ValidationFailedException refused) {
            assertThat(refused.getMessage()).contains("not well-formed XML");
        }

        // Then: either refused as undeclared, or parsed with the entity left out; never resolved
        assertThat(calls).isEmpty();
        if (tree != null) {
            assertThat(tree.text()).doesNotContain("root:", "aaaaaaaaaa", "internal");
        }
    }

    @Test
    void TR_STD_25_the_doctype_is_refused_on_sight_by_default() {
        assertThatThrownBy(() -> XmlTree.parse("<!DOCTYPE d><d/>".getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("DTDs are not allowed in a DMN document");
    }

    // ------------------------------------------------------------------ limits at their boundary

    @Test
    void TR_STD_25_fifty_thousand_elements_parse_and_fifty_thousand_and_one_are_refused() {
        assertThat(XmlTree.parse(elements(XmlTree.MAX_ELEMENTS)).children()).hasSize(XmlTree.MAX_ELEMENTS - 1);
        assertThatThrownBy(() -> XmlTree.parse(elements(XmlTree.MAX_ELEMENTS + 1)))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("Document has more than 50000 elements");
    }

    @Test
    void TR_STD_25_attribute_values_are_bounded() {
        String ok = "<d a=\"" + "x".repeat(XmlTree.MAX_ATTRIBUTE) + "\"/>";
        String tooLong = "<d a=\"" + "x".repeat(XmlTree.MAX_ATTRIBUTE + 1) + "\"/>";

        assertThat(XmlTree.parse(ok.getBytes(StandardCharsets.UTF_8)).attribute("a")).isPresent();
        assertThatThrownBy(() -> XmlTree.parse(tooLong.getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("Attribute 'a' of <d> is longer than 4096 characters");
    }

    // ------------------------------------------------------------------ L1: refusal details are bounded and escaped

    @Test
    void TR_STD_04_refusal_details_cut_long_values_and_escape_control_characters() {
        String hostile = "Decision_" + "A".repeat(3_000) + "&#13;&#10;FORGED LOG LINE";
        String dmn = DmnRuleSetTest.table("<input><inputExpression typeRef=\"number\"><text>age</text></inputExpression></input>",
                "<rule id=\"R1\"><inputEntry><text>-</text></inputEntry><outputEntry><text>true</text></outputEntry></rule>")
                .replace("id=\"rate-check\" name=\"rate-check\"", "id=\"" + hostile + "\" name=\"x&#13;&#10;y\"")
                .replace("id=\"d\" name=\"d\"", "id=\"Definitions_1\" name=\"DRD\"");

        assertThatThrownBy(() -> RuleSetReader.read(SourceFormat.DMN_XML, dmn.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ValidationFailedException.class)
                .satisfies(e -> {
                    String detail = e.getMessage();
                    assertThat(detail).hasSizeLessThan(400).doesNotContain("\r", "\n").contains("…", "x\\u000D\\u000Ay");
                });
        assertThat(Quote.of("tab\there ")).isEqualTo("'tab\\u0009here\\u2028'");
        assertThat(Quote.of("é".repeat(100))).isEqualTo("'" + "é".repeat(80) + "…'");
    }

    @Test
    void TR_STD_04_a_hostile_cell_is_bounded_in_the_detail() {
        String cell = "\"" + "z".repeat(2_000) + "\" and \r\n x";
        String dmn = DmnRuleSetTest.table("<input><inputExpression typeRef=\"string\"><text>s</text></inputExpression></input>",
                "<rule id=\"R1\"><inputEntry><text>" + DmnRuleSetTest.escape(cell) + "</text></inputEntry>"
                        + "<outputEntry><text>true</text></outputEntry></rule>");

        assertThatThrownBy(() -> RuleSetReader.read(SourceFormat.DMN_XML, dmn.getBytes(StandardCharsets.UTF_8)))
                .satisfies(e -> assertThat(e.getMessage()).hasSizeLessThan(400).doesNotContain("\r", "\n")
                        .startsWith("Rule R1, input s: '\"zzz"));
    }

    static byte[] elements(int count) {
        StringBuilder xml = new StringBuilder("<d>");
        for (int i = 1; i < count; i++) {
            xml.append("<e/>");
        }
        return xml.append("</d>").toString().getBytes(StandardCharsets.UTF_8);
    }
}
