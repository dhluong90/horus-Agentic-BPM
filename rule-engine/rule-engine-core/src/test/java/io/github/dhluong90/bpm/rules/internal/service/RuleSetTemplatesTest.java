package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.PermissionDeniedException;
import io.github.dhluong90.bpm.rules.api.RuleSetTemplate;
import io.github.dhluong90.bpm.rules.api.UnauthenticatedException;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Rule set templates (PD-112, RLE-197, RLE-198). The engine ships fee by tier, eligibility with reason codes, limit by
 * segment and blank; a bank adds {@code deposit-rate-by-term} as a file in its template folder.
 */
class RuleSetTemplatesTest {

    /** The host's template files by file name, in the order the starter reads its folder (by name). */
    final Map<String, byte[]> files = new LinkedHashMap<>();

    static final String DEPOSIT_RATE = """
            {
              "id": "deposit-rate-by-term",
              "labels": { "en": "Deposit rate by term", "vi": "Lãi suất tiền gửi theo kỳ hạn" },
              "descriptions": { "en": "Annual rate for each term.", "vi": "Lãi suất năm theo từng kỳ hạn." },
              "document": {
                "schemaVersion": 1,
                "hitPolicy": "first",
                "inputs": [ { "name": "termMonths", "type": "integer", "label": "Term in months" } ],
                "outputs": [ { "name": "annualRate", "type": "decimal", "label": "Annual rate" } ],
                "rules": [
                  { "id": "R1", "when": { "termMonths": ">= 12" }, "then": { "annualRate": "5.6" } },
                  { "id": "R2", "when": {}, "then": { "annualRate": "4.2" } }
                ]
              }
            }
            """;

    void file(String name, String content) {
        files.put(name, content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void RLE_197_the_four_built_in_templates_come_first_with_english_and_vietnamese_texts() {
        // Given a host with no template folder
        RuleSetTemplates catalogue = RuleSetTemplates.builtIn();

        // When the templates are listed
        List<RuleSetTemplate> templates = catalogue.list();

        // Then the four are there in order, each with both languages, a hit policy, inputs, outputs and first rows
        assertThat(templates).extracting(RuleSetTemplate::id)
                .containsExactly("fee-by-tier", "eligibility-with-reason", "limit-by-segment", "blank");
        assertThat(templates).allSatisfy(t -> {
            assertThat(t.source()).isEqualTo(RuleSetTemplate.BUILT_IN);
            assertThat(t.labels()).containsKeys("en", "vi");
            assertThat(t.descriptions()).containsKeys("en", "vi");
            assertThat(t.inputs()).isNotEmpty();
            assertThat(t.outputs()).isNotEmpty();
            assertThat(t.rows()).isNotEmpty();
        });
        RuleSetTemplate feeByTier = templates.getFirst();
        assertThat(feeByTier.hitPolicy()).isEqualTo("first");
        assertThat(feeByTier.inputs()).singleElement().satisfies(input -> {
            assertThat(input.name()).isEqualTo("tier");
            assertThat(input.type()).isEqualTo("string");
            assertThat(input.allowed()).containsExactly("GOLD", "PLATINUM", "CLASSIC");
        });
        assertThat(feeByTier.outputs()).containsExactly(new RuleSetTemplate.Output("fee", "money"));
        assertThat(feeByTier.rows().getFirst().when()).containsEntry("tier", "== 'GOLD'");
        assertThat(feeByTier.rows().getFirst().then()).containsEntry("fee", "0 VND");
        RuleSetTemplate blank = templates.getLast();
        assertThat(blank.inputs()).hasSize(1);
        assertThat(blank.outputs()).hasSize(1);
        assertThat(blank.rows()).singleElement().satisfies(row -> assertThat(row.when()).isEmpty());
        assertThat(templates.get(1).hitPolicy()).isEqualTo("first");
        assertThat(templates.get(2).hitPolicy()).isEqualTo("unique");
    }

    @Test
    void RLE_197_a_bank_template_is_listed_after_the_built_in_four() {
        // Given the bank adds deposit-rate-by-term to its template folder
        file("deposit-rate-by-term.json", DEPOSIT_RATE);

        // When the templates are listed
        List<RuleSetTemplate> templates = RuleSetTemplates.of(files, Set.of()).list();

        // Then it comes after the built-in four, marked as the host's, with its own texts in both languages
        assertThat(templates).hasSize(5);
        RuleSetTemplate added = templates.getLast();
        assertThat(added.id()).isEqualTo("deposit-rate-by-term");
        assertThat(added.source()).isEqualTo(RuleSetTemplate.HOST);
        assertThat(added.labels().get("vi")).isEqualTo("Lãi suất tiền gửi theo kỳ hạn");
        assertThat(added.descriptions().get("en")).isEqualTo("Annual rate for each term.");
        assertThat(added.rows()).extracting(RuleSetTemplate.Row::id).containsExactly("R1", "R2");
    }

    @Test
    void RLE_197_host_templates_follow_the_order_the_starter_read_them_in() {
        // Given two bank templates, read by file name
        file("a-first.json", DEPOSIT_RATE.replace("deposit-rate-by-term", "first-rate"));
        file("b-second.json", DEPOSIT_RATE.replace("deposit-rate-by-term", "second-rate"));

        // When listed with the blank template hidden
        List<RuleSetTemplate> templates = RuleSetTemplates.of(files, Set.of("blank")).list();

        // Then blank is gone, the other three built-in stay, and the host's follow in that order
        assertThat(templates).extracting(RuleSetTemplate::id).containsExactly("fee-by-tier",
                "eligibility-with-reason", "limit-by-segment", "first-rate", "second-rate");
    }

    @Test
    void RLE_197_a_template_becomes_a_rule_set_the_engine_accepts() {
        // Given the fee by tier template and a name Lan chose
        RuleSetTemplate template = RuleSetTemplates.builtIn().list().getFirst();

        // When the document is composed from the template the way the cockpit does, and imported
        ObjectNode document = EngineJson.NODES.objectNode();
        document.put("schemaVersion", 1);
        document.put("ruleSet", "debit-card-fee");
        document.put("hitPolicy", template.hitPolicy());
        ArrayNode inputs = document.putArray("inputs");
        template.inputs().forEach(i -> inputs.addObject().put("name", i.name()).put("type", i.type()));
        ArrayNode outputs = document.putArray("outputs");
        template.outputs().forEach(o -> outputs.addObject().put("name", o.name()).put("type", o.type()));
        ArrayNode rules = document.putArray("rules");
        template.rows().forEach(row -> {
            ObjectNode rule = rules.addObject().put("id", row.id());
            ObjectNode when = rule.putObject("when");
            row.when().forEach(when::put);
            ObjectNode then = rule.putObject("then");
            row.then().forEach((name, value) -> then.put(name, String.valueOf(value)));
        });
        InMemoryStores stores = new InMemoryStores();
        TestCaller caller = new TestCaller("bank-a", "lan");
        var versions = RuleEngines.create(new EngineContext(caller, caller,
                java.time.Clock.systemUTC(), stores, stores, EngineSettings.defaults()), stores, stores, stores)
                .versionService();
        var created = versions.importRuleSet(ImportRequest.yaml(
                EngineJson.JSON.writeValueAsString(document).getBytes(StandardCharsets.UTF_8)));

        // Then draft version 1 exists
        assertThat(created.version()).isEqualTo(1);
        assertThat(created.ruleSet()).isEqualTo("debit-card-fee");
    }

    @Test
    void RLE_197_listing_needs_rules_author() {
        // Given the catalogue and three callers: an author, a reader and nobody
        RuleSetTemplates catalogue = RuleSetTemplates.builtIn();
        TestCaller caller = new TestCaller("bank-a", "lan");
        DefaultRuleSetTemplateService service = new DefaultRuleSetTemplateService(caller, catalogue);

        // When Lan, who holds rules:author, lists
        caller.become("bank-a", "lan", Set.of("rules:author"));
        // Then she gets the four
        assertThat(service.list()).hasSize(4);

        // And Dan, who holds rules:read only, gets 403
        caller.become("bank-a", "dan", Set.of("rules:read"));
        assertThatThrownBy(service::list).isInstanceOf(PermissionDeniedException.class);

        // And a call with no caller is 401
        caller.become("bank-a", "", Set.of("rules:author"));
        assertThatThrownBy(service::list).isInstanceOf(UnauthenticatedException.class);
    }

    @Test
    void RLE_198_a_file_that_is_not_json_stops_the_start_and_names_itself() {
        file("deposit-rate-by-term.json", "{ not json");
        assertThatThrownBy(() -> RuleSetTemplates.of(files, Set.of())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Template file deposit-rate-by-term.json").hasMessageContaining("JSON");
    }

    @Test
    void RLE_198_a_template_without_a_vietnamese_label_is_refused() {
        file("deposit-rate-by-term.json", DEPOSIT_RATE.replace(
                "\"labels\": { \"en\": \"Deposit rate by term\", \"vi\": \"Lãi suất tiền gửi theo kỳ hạn\" }",
                "\"labels\": { \"en\": \"Deposit rate by term\" }"));
        assertThatThrownBy(() -> RuleSetTemplates.of(files, Set.of())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deposit-rate-by-term.json").hasMessageContaining("labels.vi");
    }

    @Test
    void RLE_198_a_document_that_the_engine_would_refuse_is_refused_at_start_up() {
        file("deposit-rate-by-term.json", DEPOSIT_RATE.replace("\"integer\"", "\"percentage\""));
        assertThatThrownBy(() -> RuleSetTemplates.of(files, Set.of())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deposit-rate-by-term.json").hasMessageContaining("not a valid rule set");
    }

    @Test
    void RLE_198_a_document_that_names_the_rule_set_is_refused() {
        file("deposit-rate-by-term.json", DEPOSIT_RATE.replace("\"schemaVersion\": 1,",
                "\"schemaVersion\": 1, \"ruleSet\": \"deposit-rate\","));
        assertThatThrownBy(() -> RuleSetTemplates.of(files, Set.of())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ruleSet name");
    }

    @Test
    void RLE_198_an_id_used_twice_is_refused_unless_the_built_in_one_is_hidden() {
        // Given a host file that reuses the built-in id blank
        file("blank.json", DEPOSIT_RATE.replace("deposit-rate-by-term", "blank"));

        // When the built-in is shown, the start fails and says how to fix it
        assertThatThrownBy(() -> RuleSetTemplates.of(files, Set.of())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'blank' is already used").hasMessageContaining("hide the built-in");

        // And when the host hides the built-in blank, its own takes the place
        assertThat(RuleSetTemplates.of(files, Set.of("blank")).list()).extracting(RuleSetTemplate::source)
                .containsExactly("BUILT_IN", "BUILT_IN", "BUILT_IN", "HOST");
    }

    @Test
    void RLE_198_a_hidden_id_that_is_not_built_in_is_refused() {
        assertThatThrownBy(() -> RuleSetTemplates.of(null, Set.of("fee-by-teir")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("horus.bpm.rules.templates.hidden").hasMessageContaining("fee-by-teir");
    }

    @Test
    void RLE_198_a_file_over_the_size_limit_is_refused() {
        file("big.json", " ".repeat(RuleSetTemplates.MAX_FILE_BYTES + 1));
        assertThatThrownBy(() -> RuleSetTemplates.of(files, Set.of())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("big.json").hasMessageContaining("larger than");
    }
}
