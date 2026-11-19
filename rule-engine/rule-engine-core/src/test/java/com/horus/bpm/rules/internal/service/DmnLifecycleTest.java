package com.horus.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.horus.bpm.rules.api.DecisionInput;
import com.horus.bpm.rules.api.DecisionResult;
import com.horus.bpm.rules.api.ImportRequest;
import com.horus.bpm.rules.api.InputInvalidException;
import com.horus.bpm.rules.api.RuleSetVersion;
import com.horus.bpm.rules.api.SelfApprovalForbiddenException;
import com.horus.bpm.rules.api.SourceFormat;
import com.horus.bpm.rules.api.ValidationFailedException;
import com.horus.bpm.rules.api.VersionNotFoundException;
import com.horus.bpm.rules.api.VersionStatus;
import com.horus.bpm.rules.api.WriteOptions;
import com.horus.bpm.rules.internal.json.Hashes;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** DMN import and export through the version service: same draft, same four eyes, same hash rules as YAML. */
class DmnLifecycleTest {

    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "alice");
    final RuleEngines engines = RuleEngines.create(
            new EngineContext(caller, caller, Clock.fixed(Instant.parse("2026-12-15T02:00:00Z"), ZoneOffset.UTC),
                    stores, stores, EngineSettings.defaults()),
            stores, stores, stores);

    @Test
    void TR_STD_01_a_dmn_import_is_a_draft_that_needs_a_second_person_and_then_evaluates() {
        // Given the hand-written modeller table of card-fee
        byte[] file = CardFee.resource("/rules/dmn/card-fee-modeller.dmn");

        // When alice imports it and requests publication
        RuleSetVersion draft = engines.versionService().importRuleSet(
                new ImportRequest(SourceFormat.DMN_XML, file, "dmn-k-1"));
        RuleSetVersion replay = engines.versionService().importRuleSet(
                new ImportRequest(SourceFormat.DMN_XML, file, "dmn-k-1"));
        engines.versionService().requestPublish("card-fee", 1, WriteOptions.none());

        // Then the draft keeps the DMN file byte for byte, and alice cannot approve her own import
        assertThat(draft.status()).isEqualTo(VersionStatus.DRAFT);
        assertThat(replay.version()).isEqualTo(1);
        RuleSetVersion stored = engines.versionService().find("card-fee", 1, true).orElseThrow();
        assertThat(stored.sourceFormat()).isEqualTo(SourceFormat.DMN_XML);
        assertThat(stored.sourceDocument().getBytes(StandardCharsets.UTF_8)).isEqualTo(file);
        assertThat(stored.sourceHash()).isEqualTo(Hashes.sha256(file));
        assertThatThrownBy(() -> engines.versionService().approve("card-fee", 1, WriteOptions.none()))
                .isInstanceOf(SelfApprovalForbiddenException.class);

        // When bob approves and a teller evaluates RLE-001's inputs
        caller.become("bank-a", "bob");
        engines.versionService().approve("card-fee", 1, WriteOptions.none());
        caller.become("bank-a", "teller-01");
        DecisionResult result = engines.ruleEngine().evaluate("card-fee", DecisionInput.of(Map.of("tier", "GOLD", "txCount", 5)));

        // Then
        assertThat(result.matchedRuleIds()).containsExactly("DecisionRule_0fk2x7a");
        assertThat(result.outputs()).containsExactly(Map.entry("fee", BigDecimal.ZERO));
    }

    @Test
    void TR_STD_03_card_fee_yaml_exported_as_dmn_imports_as_the_next_draft_with_the_same_hash() {
        // Given card-fee.yaml v1
        RuleSetVersion v1 = engines.versionService().importRuleSet(ImportRequest.yaml(CardFee.yaml()));

        // When
        String dmn = engines.versionService().export("card-fee", 1, SourceFormat.DMN_XML);
        RuleSetVersion v2 = engines.versionService().importVersion("card-fee",
                new ImportRequest(SourceFormat.DMN_XML, dmn.getBytes(StandardCharsets.UTF_8), null));

        // Then
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.status()).isEqualTo(VersionStatus.DRAFT);
        assertThat(v2.canonicalHash()).isEqualTo(v1.canonicalHash()).isEqualTo(Hashes.sha256(CardFee.CANONICAL));
        assertThat(engines.versionService().export("card-fee", 1, SourceFormat.JSON)).isEqualTo(CardFee.CANONICAL);
        assertThat(engines.versionService().export("card-fee", 2, SourceFormat.DMN_XML)).isEqualTo(dmn);
        assertThat(dmn).doesNotContain("bank-a", "alice", "DRAFT", "lockVersion");
    }

    @Test
    void TR_STD_04_a_refused_dmn_file_stores_nothing_and_export_errors_are_specific() {
        byte[] bad = """
                <definitions xmlns="https://www.omg.org/spec/DMN/20230324/MODEL/" name="kyc-risk" namespace="urn:x">
                  <decision id="kyc-risk" name="kyc-risk">
                    <decisionTable hitPolicy="FIRST">
                      <input><inputExpression typeRef="number"><text>score</text></inputExpression></input>
                      <output name="risk" typeRef="string"/>
                      <rule id="K1"><inputEntry><text>? &gt; 700</text></inputEntry><outputEntry><text>"LOW"</text></outputEntry></rule>
                    </decisionTable>
                  </decision>
                </definitions>
                """.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> engines.versionService().importRuleSet(new ImportRequest(SourceFormat.DMN_XML, bad, null)))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("Rule K1, input score: '? > 700'")
                .hasMessageContaining("(line 6)");
        assertThat(stores.versions).isEmpty();

        engines.versionService().importRuleSet(ImportRequest.yaml(CardFee.yaml()));
        assertThatThrownBy(() -> engines.versionService().export("card-fee", 9, SourceFormat.DMN_XML))
                .isInstanceOf(VersionNotFoundException.class);
        assertThatThrownBy(() -> engines.versionService().export("card-fee", 1, SourceFormat.YAML))
                .isInstanceOf(InputInvalidException.class);
    }
}
