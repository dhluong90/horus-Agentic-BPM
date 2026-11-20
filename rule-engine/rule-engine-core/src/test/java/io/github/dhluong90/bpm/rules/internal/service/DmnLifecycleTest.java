package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.SelfApprovalForbiddenException;
import io.github.dhluong90.bpm.rules.api.SourceFormat;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.api.VersionNotFoundException;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.json.Hashes;
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
        assertThatThrownBy(() -> engines.versionService().approve("card-fee", 1, new WriteOptions(null, 1L, null)))
                .isInstanceOf(SelfApprovalForbiddenException.class);

        // When bob approves and a teller evaluates RLE-001's inputs
        caller.become("bank-a", "bob");
        engines.versionService().approve("card-fee", 1, new WriteOptions(null, 1L, null));
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

    @Test
    void TR_STD_25_a_document_over_the_size_limit_is_refused_as_too_large_before_decoding() {
        byte[] huge = new byte[io.github.dhluong90.bpm.rules.internal.read.RuleSetReader.MAX_SOURCE_BYTES + 1];
        java.util.Arrays.fill(huge, (byte) 0xFF); // not UTF-8 either: the size check comes first

        assertThatThrownBy(() -> engines.versionService().importRuleSet(new ImportRequest(SourceFormat.DMN_XML, huge, null)))
                .isInstanceOf(io.github.dhluong90.bpm.rules.api.PayloadTooLargeException.class)
                .hasMessageContaining("larger than 1048576 bytes");
        assertThat(stores.versions).isEmpty();
    }

    @Test
    void RLE_045_the_same_table_in_two_styles_has_one_canonical_hash_and_keeps_its_own_source() {
        // Given style A in bank-a and style B (normal form) in bank-b
        byte[] a = io.github.dhluong90.bpm.rules.internal.dmn.ruleset.StyleCheck.A.getBytes(StandardCharsets.UTF_8);
        byte[] b = io.github.dhluong90.bpm.rules.internal.dmn.ruleset.StyleCheck.B.getBytes(StandardCharsets.UTF_8);

        // When
        RuleSetVersion inA = engines.versionService().importRuleSet(ImportRequest.yaml(a));
        caller.become("bank-b", "alice");
        RuleSetVersion inB = engines.versionService().importRuleSet(ImportRequest.yaml(b));
        caller.become("bank-a", "alice");
        RuleSetVersion sourceA = engines.versionService().find("style-check", 1, true).orElseThrow();

        // Then
        assertThat(inA.canonicalHash()).isEqualTo(inB.canonicalHash());
        assertThat(inA.sourceHash()).isNotEqualTo(inB.sourceHash()).isEqualTo(Hashes.sha256(a));
        assertThat(sourceA.sourceDocument().getBytes(StandardCharsets.UTF_8)).isEqualTo(a);
        assertThat(sourceA.canonicalDocument()).contains("\">= 3\"").doesNotContain("x >= 3");
    }

    @Test
    void RLE_045_a_version_whose_stored_canonical_document_was_altered_is_not_served() {
        // Given card-fee published, then its canonical_json altered in the store (fee of R1 from 0 VND to 1 VND)
        engines.versionService().importRuleSet(ImportRequest.yaml(CardFee.yaml()));
        engines.versionService().requestPublish("card-fee", 1, WriteOptions.none());
        caller.become("bank-a", "bob");
        engines.versionService().approve("card-fee", 1, new WriteOptions(null, 1L, null));
        var stored = stores.versions.getFirst();
        stores.versions.set(0, new io.github.dhluong90.bpm.rules.internal.store.StoredVersion(stored.tenantId(), stored.id(),
                stored.ruleSetId(), stored.ruleSet(), stored.version(), stored.status(), stored.hitPolicy(),
                stored.effectiveFrom(), stored.canonicalJson().replace("\"0 VND\"", "\"1 VND\""), stored.canonicalHash(),
                stored.sourceFormat(), stored.sourceDocument(), stored.sourceHash(), stored.lockVersion(),
                stored.requestedBy(), stored.requestedAt(), stored.approvedBy(), stored.approvedAt(), stored.approvalComment(),
                stored.createdBy(), stored.createdAt()));
        caller.become("bank-a", "teller-01");

        // When
        DecisionResult result = engines.ruleEngine().evaluate("card-fee", DecisionInput.of(Map.of("tier", "GOLD", "txCount", 5)));

        // Then: refused, recorded once as EVALUATION_ERROR (the audit entry), no output
        assertThat(result.isError()).isTrue();
        assertThat(result.outcome()).isInstanceOfSatisfying(DecisionResult.Failed.class, failed -> {
            assertThat(failed.code()).isEqualTo("EVALUATION_ERROR");
            assertThat(failed.detail()).contains("Integrity check failed", "card-fee v1");
        });
        assertThat(result.outputs()).isEmpty();
        assertThat(stores.decisions).singleElement().satisfies(row -> assertThat(row.outcome()).isEqualTo("EVALUATION_ERROR"));
    }

    @Test
    void RLE_045_a_source_that_is_not_clean_utf_8_is_refused_and_nothing_is_stored() {
        byte[] yaml = CardFee.yaml();
        byte[] latin1 = new String(yaml, StandardCharsets.UTF_8).replace("Monthly", "Mont\u00e9ly").getBytes(StandardCharsets.ISO_8859_1);
        byte[] nul = new byte[yaml.length + 1];
        System.arraycopy(yaml, 0, nul, 0, yaml.length);
        byte[] bom = new byte[yaml.length + 3];
        bom[0] = (byte) 0xEF;
        bom[1] = (byte) 0xBB;
        bom[2] = (byte) 0xBF;
        System.arraycopy(yaml, 0, bom, 3, yaml.length);

        assertThatThrownBy(() -> engines.versionService().importRuleSet(ImportRequest.yaml(latin1))).hasMessageContaining("not valid UTF-8");
        assertThatThrownBy(() -> engines.versionService().importRuleSet(ImportRequest.yaml(nul))).hasMessageContaining("NUL");
        assertThatThrownBy(() -> engines.versionService().importRuleSet(ImportRequest.yaml(bom))).hasMessageContaining("byte order mark");
        assertThat(stores.versions).isEmpty();
    }
}
