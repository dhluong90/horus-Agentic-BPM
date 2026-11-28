package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.HorusBpmException;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.PayloadTooLargeException;
import io.github.dhluong90.bpm.rules.api.PermissionDeniedException;
import io.github.dhluong90.bpm.rules.api.RuleSetNotFoundException;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.SourceFormat;
import io.github.dhluong90.bpm.rules.api.UnsupportedStandardException;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.json.Hashes;
import io.github.dhluong90.bpm.rules.internal.read.RuleSetReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** RLE-078 to RLE-081: a DMN decision table imports as a draft; what the release cannot import is refused by name. */
class DmnImportSpecTest {

    static final String MODELLER = "/rules/dmn/card-fee-modeller.dmn";
    static final List<String> CORPUS = List.of("modeller-a-loan-rate", "modeller-b-fee-flags", "modeller-c-overdraft");

    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "lan");
    final RuleEngines engines = RuleEngines.create(
            new EngineContext(caller, caller, Clock.fixed(Instant.parse("2026-12-15T02:00:00Z"), ZoneOffset.UTC),
                    stores, stores, EngineSettings.defaults().withRequireContentHash(false)),
            stores, stores, stores);

    @Test
    void RLE_078_a_modeller_decision_table_imports_as_a_draft_and_evaluates_as_expected() throws Exception {
        // Given the modeller table and a corpus of three tools, each with the outputs its author expects
        byte[] file = CardFee.resource(MODELLER);

        // When lan imports the table, then each corpus file, then the table again as v2
        RuleSetVersion v1 = engines.versionService().importRuleSet(new ImportRequest(SourceFormat.DMN_XML, file, null));

        // Then: a draft that keeps the file byte for byte, with the ids of the file
        assertThat(v1.status()).isEqualTo(VersionStatus.DRAFT);
        assertThat(v1.sourceFormat()).isEqualTo(SourceFormat.DMN_XML);
        RuleSetVersion stored = engines.versionService().find("card-fee", 1, true).orElseThrow();
        assertThat(stored.sourceDocument().getBytes(StandardCharsets.UTF_8)).isEqualTo(file);
        assertThat(stored.sourceHash()).isEqualTo(Hashes.sha256(file));
        assertThat(stored.sourceDocument()).contains("dmndi:DMNShape");
        String canonical = engines.versionService().export("card-fee", 1, SourceFormat.JSON);
        assertThat(canonical).contains("\"id\":\"DecisionRule_0fk2x7a\"", "\"description\":\"Gold customers pay nothing\"",
                "\"label\":\"Fee (VND)\",\"name\":\"fee\",\"type\":\"decimal\"");

        // And an export as DMN imports back as the next draft with the same hash
        String exported = engines.versionService().export("card-fee", 1, SourceFormat.DMN_XML);
        RuleSetVersion v2 = engines.versionService().importVersion("card-fee",
                new ImportRequest(SourceFormat.DMN_XML, exported.getBytes(StandardCharsets.UTF_8), null));
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.canonicalHash()).isEqualTo(v1.canonicalHash());

        // And, after a second person approves, the author's expected cases hold
        caller.become("bank-a", "bob");
        engines.versionService().requestPublish("card-fee", 1, WriteOptions.none());
        caller.become("bank-a", "carol");
        engines.versionService().approve("card-fee", 1, new WriteOptions(null, 1L, null));
        caller.become("bank-a", "teller-01");
        assertThat(decide("card-fee", Map.of("tier", "GOLD", "txCount", 5)).outputs()).containsEntry("fee", BigDecimal.ZERO);
        assertThat(decide("card-fee", Map.of("tier", "SILVER", "txCount", 3)).outputs())
                .containsEntry("fee", new BigDecimal("10000"));
        assertThat(decide("card-fee", Map.of("tier", "SILVER", "txCount", 2)).outputs())
                .containsEntry("fee", new BigDecimal("50000"));

        // And every corpus file imports and gives exactly the tool's expected outputs and matched rules
        for (String name : CORPUS) {
            caller.become("bank-a", "lan");
            byte[] dmn = CardFee.resource("/rules/dmn/modellers/" + name + ".dmn");
            RuleSetVersion imported = engines.versionService().importRuleSet(new ImportRequest(SourceFormat.DMN_XML, dmn, null));
            assertThat(imported.status()).isEqualTo(VersionStatus.DRAFT);
            String rounded = engines.versionService().export(imported.ruleSet(), 1, SourceFormat.DMN_XML);
            assertThat(engines.versionService().importVersion(imported.ruleSet(),
                    new ImportRequest(SourceFormat.DMN_XML, rounded.getBytes(StandardCharsets.UTF_8), null)).canonicalHash())
                    .as(name + " keeps its hash through export and import").isEqualTo(imported.canonicalHash());
            caller.become("bank-a", "bob");
            engines.versionService().requestPublish(imported.ruleSet(), 1, WriteOptions.none());
            caller.become("bank-a", "carol");
            engines.versionService().approve(imported.ruleSet(), 1, new WriteOptions(null, 1L, null));
            caller.become("bank-a", "teller-01");
            JsonNode cases = JsonMapper.builder().build().readTree(CardFee.resource("/rules/dmn/modellers/" + name + ".expected.json"));
            assertThat(cases.size()).isGreaterThanOrEqualTo(4);
            for (JsonNode c : cases) {
                DecisionResult result = decide(imported.ruleSet(), inputs(c.get("inputs")));
                assertThat(result.matchedRuleIds()).as(name + " " + c.get("inputs")).containsExactlyElementsOf(strings(c.get("rules")));
                if (c.get("outputs").isNull()) {
                    assertThat(result.outcome()).isInstanceOf(DecisionResult.NoMatch.class);
                } else {
                    c.get("outputs").properties().forEach(e ->
                            assertThat(normalise(result.outputs().get(e.getKey()))).as(name + " " + e.getKey()).isEqualTo(normalise(plain(e.getValue()))));
                }
            }
        }
    }

    @Test
    void RLE_079_unsupported_dmn_content_is_refused_by_name_with_line_and_stage() {
        // Given the modeller table, varied one feature at a time; every refusal stores nothing
        String base = new String(CardFee.resource(MODELLER), StandardCharsets.UTF_8);
        Map<String, String> byMessage = new LinkedHashMap<>();
        for (String policy : List.of("PRIORITY", "ANY", "RULE ORDER", "OUTPUT ORDER")) {
            byMessage.put("Hit policy " + policy + " arrives in stage S1", base.replace("hitPolicy=\"FIRST\"", "hitPolicy=\"" + policy + "\""));
        }
        byMessage.put("Hit policy COLLECT with aggregation SUM arrives in stage S1",
                base.replace("hitPolicy=\"FIRST\"", "hitPolicy=\"COLLECT\" aggregation=\"SUM\""));
        byMessage.put("Rule DecisionRule_0fk2x7a, output fee: 'age * 2'",
                base.replaceFirst("<text>0</text>", "<text>age * 2</text>"));
        byMessage.put("Input expression 'applicant.age' must be a plain input name",
                base.replace("<text>txCount</text>", "<text>applicant.age</text>"));
        byMessage.put("typeRef 'tAge' of input 'txCount' is not supported; item definitions arrive in stage S1",
                base.replace("typeRef=\"integer\"", "typeRef=\"tAge\""));
        byMessage.put("More than one <decision>", base.replace("</definitions>", "<decision id=\"second\" name=\"second\"/></definitions>"));
        byMessage.put("give the decision a kebab-case id such as card-fee", base.replace("id=\"card-fee\"", "id=\"Decision_1\""));
        for (String cell : List.of("? > 18", "abs(-20)", "!= 18", "> 18 and < 65", "date(\"2026-02-30\")")) {
            byMessage.put("Rule DecisionRule_1lh0wd3, input txCount: '" + cell.replace("\"", "&quot;") + "'",
                    base.replace("&gt;= 3", escape(cell)));
        }
        for (var entry : byMessage.entrySet()) {
            assertThatThrownBy(() -> engines.versionService().importRuleSet(
                    new ImportRequest(SourceFormat.DMN_XML, entry.getValue().getBytes(StandardCharsets.UTF_8), null)))
                    .as(entry.getKey())
                    .isInstanceOf(HorusBpmException.class)
                    .satisfies(e -> assertThat(e.getMessage().replace("&quot;", "\"")).contains(entry.getKey().replace("&quot;", "\"")).contains("(line "));
        }

        // And the elements of later stages are listed with element, id, line and stage
        assertUnsupported(base.replace("<decision ", "<businessKnowledgeModel id=\"bkm1\" name=\"b\"/><decision "), "businessKnowledgeModel", "bkm1", "S2");
        assertUnsupported(base.replace("<decision ", "<decision id=\"lit\" name=\"lit\"><literalExpression><text>1</text></literalExpression></decision><decision "),
                "literalExpression", "lit", "S1");
        assertThatThrownBy(() -> engines.versionService().importRuleSet(new ImportRequest(SourceFormat.DMN_XML,
                base.replace("<inputExpression ", "<inputExpression expressionLanguage=\"javascript\" ").getBytes(StandardCharsets.UTF_8), null)))
                .isInstanceOf(UnsupportedStandardException.class).hasMessageContaining("Expression language 'javascript' is not supported");
        assertThatThrownBy(() -> engines.versionService().importRuleSet(new ImportRequest(SourceFormat.DMN_XML,
                CardFee.resource("/rules/dmn/claim-case.cmmn"), null)))
                .isInstanceOf(UnsupportedStandardException.class).hasMessageContaining("CMMN is not supported");
        assertThat(stores.versions).isEmpty();
    }

    @Test
    void RLE_080_dmn_files_are_read_safely_within_limits_and_per_tenant() {
        // Given hostile files and a valid table
        String base = new String(CardFee.resource(MODELLER), StandardCharsets.UTF_8);
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE definitions [<!ENTITY secret SYSTEM \"file:///etc/passwd\">]>"
                + base.substring(base.indexOf("<definitions")).replace("name=\"DRD\"", "name=\"&secret;\"");
        String laughs = "<?xml version=\"1.0\"?><!DOCTYPE lolz [<!ENTITY lol \"lol\"><!ENTITY lol2 \"&lol;&lol;&lol;&lol;\">]>"
                + "<definitions xmlns=\"https://www.omg.org/spec/DMN/20230324/MODEL/\" name=\"&lol2;\"/>";
        String deep = base.replace("</definitions>", "<extensionElements>" + "<x:a xmlns:x=\"urn:x\">".repeat(40)
                + "</x:a>".repeat(40) + "</extensionElements></definitions>");
        String longCell = base.replace("&gt;= 3", "&gt;= " + "9".repeat(70_000));

        // When / Then: each is refused with its reason, and nothing is stored
        assertRefused(xxe, "DTDs are not allowed in a DMN document");
        assertRefused(laughs, "DTDs are not allowed in a DMN document");
        assertRefused(deep, "Elements nested deeper than 32");
        assertRefused(longCell, "Element text longer than 65536 characters");
        assertRefused("<definitions", "not well-formed XML");
        assertThatThrownBy(() -> engines.versionService().importRuleSet(new ImportRequest(SourceFormat.DMN_XML,
                new byte[RuleSetReader.MAX_SOURCE_BYTES + 1], null))).isInstanceOf(PayloadTooLargeException.class);
        assertThat(stores.versions).isEmpty();

        // And dan, who may only read, cannot import; binh of bank-b cannot see lan's import
        caller.become("bank-a", "dan", Set.of("rules:read"));
        assertThatThrownBy(() -> engines.versionService().importRuleSet(
                new ImportRequest(SourceFormat.DMN_XML, base.getBytes(StandardCharsets.UTF_8), null)))
                .isInstanceOf(PermissionDeniedException.class);
        assertThat(stores.versions).isEmpty();
        caller.become("bank-a", "lan");
        engines.versionService().importRuleSet(new ImportRequest(SourceFormat.DMN_XML, base.getBytes(StandardCharsets.UTF_8), null));
        caller.become("bank-b", "binh");
        assertThatThrownBy(() -> engines.versionService().find("card-fee", 1, false).orElseThrow(() -> new RuleSetNotFoundException("card-fee")))
                .isInstanceOf(RuleSetNotFoundException.class);
    }

    @Test
    void RLE_081_imported_model_records_its_error_mode_and_keeps_hash_identity() {
        // Given the modeller table and a YAML document with the same inputs, output, rule ids, labels and cells
        String yaml = """
                schemaVersion: 1
                ruleSet: card-fee
                hitPolicy: first
                inputs:
                  - {name: tier, type: string, label: Card tier}
                  - {name: txCount, type: integer, label: Transactions this month}
                outputs:
                  - {name: fee, type: decimal, label: Fee (VND)}
                rules:
                  - id: DecisionRule_0fk2x7a
                    description: Gold customers pay nothing
                    when: {tier: "== 'GOLD'"}
                    then: {fee: 0}
                  - id: DecisionRule_1lh0wd3
                    when: {txCount: ">= 3"}
                    then: {fee: 10000}
                  - id: DecisionRule_09rn3ah
                    when: {}
                    then: {fee: 50000}
                """;

        // When both are imported, in two tenants
        RuleSetVersion fromDmn = engines.versionService().importRuleSet(
                new ImportRequest(SourceFormat.DMN_XML, CardFee.resource(MODELLER), null));
        caller.become("bank-b", "lan");
        RuleSetVersion fromYaml = engines.versionService().importRuleSet(ImportRequest.yaml(yaml.getBytes(StandardCharsets.UTF_8)));

        // Then: strict is what both evaluate in, so one behaviour is one canonical hash (PD-125)
        assertThat(fromDmn.canonicalHash()).isEqualTo(fromYaml.canonicalHash());
        assertThat(engines.versionService().export("card-fee", 1, SourceFormat.JSON)).doesNotContain("errorMode");
        String base = new String(CardFee.resource(MODELLER), StandardCharsets.UTF_8);

        // And a file that declares strict keeps the hash, and one that declares spec is refused at its line
        String withMode = base.replace("<definitions ", "<definitions xmlns:horus=\"urn:horus-bpm:dmn:extensions:v1\" horus:errorMode=\"%s\" ");
        caller.become("bank-a", "lan");
        assertThat(engines.versionService().importVersion("card-fee", new ImportRequest(SourceFormat.DMN_XML,
                withMode.formatted("strict").getBytes(StandardCharsets.UTF_8), null)).canonicalHash())
                .isEqualTo(fromDmn.canonicalHash());
        assertRefused(withMode.formatted("spec"), "horus:errorMode spec is added in stage S1");
        assertRefused(withMode.formatted("lenient"), "horus:errorMode must be strict or spec");
    }

    @Test
    void RLE_224_a_dmn_rule_id_that_starts_with_an_underscore_is_mapped_and_exported_as_written() {
        // Given a table whose rules carry ids that start with an underscore
        String dmn = new String(CardFee.resource(MODELLER), StandardCharsets.UTF_8)
                .replace("DecisionRule_0fk2x7a", "_5f3a1").replace("DecisionRule_1lh0wd3", "_9c2b7");

        // When it is imported, exported and imported again
        RuleSetVersion v1 = engines.versionService().importRuleSet(
                new ImportRequest(SourceFormat.DMN_XML, dmn.getBytes(StandardCharsets.UTF_8), null));
        String canonical = engines.versionService().export("card-fee", 1, SourceFormat.JSON);
        String exported = engines.versionService().export("card-fee", 1, SourceFormat.DMN_XML);
        RuleSetVersion v2 = engines.versionService().importVersion("card-fee",
                new ImportRequest(SourceFormat.DMN_XML, exported.getBytes(StandardCharsets.UTF_8), null));

        // Then: mapped ids with the original in the description, the original id back in the export, one hash
        assertThat(canonical).contains("\"id\":\"r_5f3a1\"", "\"id\":\"r_9c2b7\"",
                "Gold customers pay nothing (DMN id _5f3a1)", "(DMN id _9c2b7)");
        assertThat(exported).contains("<rule id=\"_5f3a1\">", "<rule id=\"_9c2b7\">", "Gold customers pay nothing")
                .doesNotContain("r_5f3a1", "DMN id");
        assertThat(v2.canonicalHash()).isEqualTo(v1.canonicalHash());
        caller.become("bank-a", "bob");
        engines.versionService().requestPublish("card-fee", 1, WriteOptions.none());
        caller.become("bank-a", "carol");
        engines.versionService().approve("card-fee", 1, new WriteOptions(null, 1L, null));
        assertThat(decide("card-fee", Map.of("tier", "SILVER", "txCount", 3)).matchedRuleIds()).containsExactly("r_9c2b7");
    }

    @Test
    void RLE_224_two_ids_that_map_to_the_same_id_are_refused_with_a_named_code() {
        String dmn = new String(CardFee.resource(MODELLER), StandardCharsets.UTF_8)
                .replace("DecisionRule_0fk2x7a", "_a1").replace("DecisionRule_1lh0wd3", "r_a1");

        assertThatThrownBy(() -> engines.versionService().importRuleSet(
                new ImportRequest(SourceFormat.DMN_XML, dmn.getBytes(StandardCharsets.UTF_8), null)))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> {
                    assertThat(e.violations()).singleElement().satisfies(v -> {
                        assertThat(v.code()).isEqualTo("DMN_RULE_ID_COLLISION");
                        assertThat(v.message()).contains("'_a1'", "'r_a1'", "(line ");
                    });
                });
        assertThat(stores.versions).isEmpty();
        String space = dmn.replace("_a1\"", "a b\"");
        assertRefused(space.replace("r_a1", "x"), "must start with a letter");
    }

    // ------------------------------------------------------------------ helpers

    DecisionResult decide(String ruleSet, Map<String, ?> inputs) {
        return engines.ruleEngine().evaluate(ruleSet, DecisionInput.of(inputs));
    }

    void assertRefused(String dmn, String message) {
        assertThatThrownBy(() -> engines.versionService().importRuleSet(
                new ImportRequest(SourceFormat.DMN_XML, dmn.getBytes(StandardCharsets.UTF_8), null)))
                .isInstanceOf(ValidationFailedException.class).hasMessageContaining(message);
    }

    void assertUnsupported(String dmn, String element, String id, String stage) {
        assertThatThrownBy(() -> engines.versionService().importRuleSet(
                new ImportRequest(SourceFormat.DMN_XML, dmn.getBytes(StandardCharsets.UTF_8), null)))
                .isInstanceOfSatisfying(UnsupportedStandardException.class, e -> {
                    var first = e.elements().get(0);
                    assertThat(first.element()).isEqualTo(element);
                    assertThat(first.id()).isEqualTo(id);
                    assertThat(first.stage()).isEqualTo(stage);
                    assertThat(first.line()).isGreaterThan(0);
                });
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static Map<String, Object> inputs(JsonNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        node.properties().forEach(e -> {
            Object v = plain(e.getValue());
            out.put(e.getKey(), v instanceof String s && s.matches("\\d{4}-\\d{2}-\\d{2}") ? LocalDate.parse(s) : v);
        });
        return out;
    }

    static Object plain(JsonNode n) {
        if (n.isArray()) {
            List<Object> list = new ArrayList<>();
            n.forEach(x -> list.add(plain(x)));
            return list;
        }
        return n.isNumber() ? n.decimalValue() : n.isBoolean() ? (Object) n.booleanValue() : n.asString();
    }

    static Object normalise(Object value) {
        if (value instanceof BigDecimal d) {
            return d.stripTrailingZeros();
        }
        if (value instanceof Number n) {
            return new BigDecimal(n.toString()).stripTrailingZeros();
        }
        if (value instanceof List<?> l) {
            return l.stream().map(DmnImportSpecTest::normalise).toList();
        }
        return value;
    }

    static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(x -> out.add(x.asString()));
        return out;
    }
}
