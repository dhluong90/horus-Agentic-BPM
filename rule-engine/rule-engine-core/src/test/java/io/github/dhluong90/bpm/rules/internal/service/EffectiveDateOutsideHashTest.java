package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.SourceFormat;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.json.Hashes;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * #233: the effective date is version metadata, outside the behaviour hash (ADR-0026 decision 1, DESIGN section 3.1).
 * The same rules have one canonical hash whatever date they take effect, through import, export and re-import.
 * Users: lan authors, minh approves, at bank-a and bank-a-fin.
 */
class EffectiveDateOutsideHashTest {

    static final Set<String> AUTHOR = Set.of("rules:read", "rules:author");
    static final Set<String> APPROVER = Set.of("rules:read", "rules:approve");
    static final String H1 = Hashes.sha256(CardFee.CANONICAL);

    final EditorApiTest.MovableClock clock = new EditorApiTest.MovableClock(Instant.parse("2026-10-20T00:00:00Z"));
    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "lan");
    final VersionService versions = RuleEngines.create(
            new EngineContext(caller, caller, clock, stores, stores, EngineSettings.defaults()),
            stores, stores, stores).versionService();

    static byte[] cardFee(String comment, String effectiveFrom) {
        String yaml = new String(CardFee.yaml(), StandardCharsets.UTF_8);
        if (effectiveFrom != null) {
            yaml = yaml.replace("hitPolicy: first", "effectiveFrom: \"" + effectiveFrom + "\"\nhitPolicy: first");
        }
        return ("# " + comment + "\n" + yaml).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void RLE_028_versions_that_differ_only_in_comments_and_effective_from_have_equal_canonical_hashes() {
        // Given card-fee v2 without a date and v3, the same rules with other comments and effectiveFrom 1 Jan 2027
        caller.become("bank-a", "lan", AUTHOR);
        versions.importRuleSet(ImportRequest.yaml(CardFee.yaml()));
        RuleSetVersion v2 = versions.importVersion("card-fee", ImportRequest.yaml(cardFee("fee review", null)));
        RuleSetVersion v3 = versions.importVersion("card-fee",
                ImportRequest.yaml(cardFee("same rules, scheduled for January", "2027-01-01T00:00:00Z")));

        // Then the canonical models and hashes are equal; only the source and the date differ, as metadata
        assertThat(v3.canonicalHash()).isEqualTo(v2.canonicalHash()).isEqualTo(H1);
        assertThat(v3.canonicalDocument()).isEqualTo(v2.canonicalDocument()).doesNotContain("effectiveFrom");
        assertThat(v3.sourceHash()).isNotEqualTo(v2.sourceHash());
        assertThat(v2.effectiveFrom()).isNull();
        assertThat(v3.effectiveFrom()).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
    }

    @Test
    void RLE_032_export_round_trips_to_same_canonical_hash() {
        // Given card-fee v1 imported with comments and effectiveFrom 1 Nov 2026, published; canonical hash H1
        caller.become("bank-a", "lan", AUTHOR);
        RuleSetVersion v1 = versions.importRuleSet(ImportRequest.yaml(cardFee("tariff 2026", "2026-11-01T00:00:00Z")));
        versions.requestPublish("card-fee", 1, WriteOptions.none());
        caller.become("bank-a", "minh", APPROVER);
        RuleSetVersion published = versions.approve("card-fee", 1, WriteOptions.none());
        assertThat(v1.canonicalHash()).isEqualTo(H1);
        assertThat(published.effectiveFrom()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));

        // When lan exports it as the canonical JSON and as DMN
        caller.become("bank-a", "lan", AUTHOR);
        String json = versions.export("card-fee", 1, SourceFormat.JSON);
        String dmn = versions.export("card-fee", 1, SourceFormat.DMN_XML);

        // Then the JSON export is the canonical document byte for byte, with no date, tenant, status or approver
        assertThat(json).isEqualTo(CardFee.CANONICAL);
        assertThat(json).doesNotContain("effectiveFrom", "bank-a", "PUBLISHED", "minh", "lockVersion");

        // When the JSON file is imported back as v2 in bank-a (JSON is valid YAML 1.2) and the DMN file as a new rule
        // set in bank-a-fin
        RuleSetVersion v2 = versions.importVersion("card-fee", ImportRequest.yaml(json.getBytes(StandardCharsets.UTF_8)));
        caller.become("bank-a-fin", "lan", AUTHOR);
        RuleSetVersion fin = versions.importRuleSet(new ImportRequest(SourceFormat.DMN_XML,
                dmn.getBytes(StandardCharsets.UTF_8), null));

        // Then both are drafts with canonical hash H1 and no effective date until request-publish sets one
        assertThat(v2.status()).isEqualTo(VersionStatus.DRAFT);
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.canonicalHash()).isEqualTo(H1);
        assertThat(v2.effectiveFrom()).isNull();
        assertThat(fin.status()).isEqualTo(VersionStatus.DRAFT);
        assertThat(fin.version()).isEqualTo(1);
        assertThat(fin.canonicalHash()).isEqualTo(H1);
        assertThat(fin.effectiveFrom()).isNull();
        // and each keeps its own file as source; v1 still has its comments
        caller.become("bank-a", "lan", AUTHOR);
        assertThat(versions.find("card-fee", 1, true).orElseThrow().sourceDocument()).contains("# tariff 2026");
        assertThat(versions.find("card-fee", 2, true).orElseThrow().sourceDocument()).isEqualTo(json);
    }
}
