package com.horus.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.horus.bpm.rules.api.DecisionInput;
import com.horus.bpm.rules.api.DecisionNotFoundException;
import com.horus.bpm.rules.api.DecisionResult;
import com.horus.bpm.rules.api.DecisionSearchResult;
import com.horus.bpm.rules.api.ImportRequest;
import com.horus.bpm.rules.api.LoggedDecision;
import com.horus.bpm.rules.api.MaskedInputQuery;
import com.horus.bpm.rules.api.MaskingKey;
import com.horus.bpm.rules.api.PermissionDeniedException;
import com.horus.bpm.rules.api.QueryInvalidException;
import com.horus.bpm.rules.api.WriteOptions;
import com.horus.bpm.rules.spi.MaskingPolicy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * Masked inputs in the decision log and their lookup (RLE-012, TR-SEC-05, TR-OBS-01), through the public services.
 * The expected fingerprints are computed here with the JDK's HMAC, independently of the engine.
 */
class MaskedInputsTest {

    static final Instant NOW = Instant.parse("2027-01-15T02:00:00Z");
    static final String NATIONAL_ID = "079090001234";
    static final byte[] KEY_A = bytes(32, 'a');
    static final byte[] KEY_B = bytes(32, 'b');
    static final Set<String> EVALUATE = Set.of("rules:evaluate");
    static final Set<String> AUDIT = Set.of("rules:log:read");

    /** The minor-review table of RLE-031: a national id (masked) and an age route a customer to a review. */
    static final String MINOR_REVIEW = """
            schemaVersion: 1
            ruleSet: minor-review
            hitPolicy: first
            inputs:
              - { name: nationalId, type: string, mask: true }
              - { name: age, type: integer }
            outputs:
              - { name: route, type: string }
            rules:
              - { id: R1, when: { nationalId: "is not null", age: "< 18" }, then: { route: "'MANUAL'" } }
              - { id: R2, when: { age: ">= 18" }, then: { route: "'AUTO'" } }
            """;

    /** A table that declares no mask on phone and mask: false on segment, for the masking default. */
    static final String CONTACT_ROUTING = """
            schemaVersion: 1
            ruleSet: contact-routing
            hitPolicy: first
            inputs:
              - { name: phone, type: string }
              - { name: segment, type: string, mask: false }
            outputs:
              - { name: channel, type: string }
            rules:
              - { id: C1, when: {}, then: { channel: "'SMS'" } }
            """;

    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "alice");

    RuleEngines engines(MaskingPolicy masking) {
        return RuleEngines.create(new EngineContext(caller, caller, Clock.fixed(NOW, ZoneOffset.UTC), stores, stores,
                EngineSettings.defaults(), masking), stores, stores, stores);
    }

    static MaskingPolicy keys(Map<String, byte[]> byTenant, Set<String> maskByDefault) {
        return new MaskingPolicy() {
            @Override
            public Optional<MaskingKey> hmacKey(String tenantId) {
                return Optional.ofNullable(byTenant.get(tenantId)).map(MaskingKey::of);
            }

            @Override
            public boolean maskByDefault(String tenantId) {
                return maskByDefault.contains(tenantId);
            }
        };
    }

    void publish(RuleEngines engines, String tenant, String yaml, String name) {
        caller.become(tenant, "alice");
        engines.versionService().importRuleSet(ImportRequest.yaml(yaml.getBytes(StandardCharsets.UTF_8)));
        engines.versionService().requestPublish(name, 1, WriteOptions.none());
        caller.become(tenant, "bob");
        engines.versionService().approve(name, 1, WriteOptions.none());
    }

    DecisionResult evaluate(RuleEngines engines, String tenant, String ruleSet, Map<String, Object> inputs) {
        caller.become(tenant, "teller", EVALUATE);
        return engines.ruleEngine().evaluate(ruleSet, DecisionInput.of(inputs));
    }

    static MaskedInputQuery byNationalId(String value) {
        return new MaskedInputQuery("minor-review", "nationalId", value, NOW.minusSeconds(3600), NOW.plusSeconds(3600),
                50);
    }

    @Test
    void RLE_012_masked_inputs_stored_as_keyed_hmac() throws Exception {
        RuleEngines engines = engines(MaskingPolicy.withKey(MaskingKey.of(KEY_A)));
        publish(engines, "bank-a", MINOR_REVIEW, "minor-review");

        DecisionResult result = evaluate(engines, "bank-a", "minor-review", Map.of("nationalId", NATIONAL_ID, "age", 16));

        String expected = "hmac:" + hmacHex(KEY_A, NATIONAL_ID);
        String row = stores.decisions.getFirst().maskedInputsJson();
        assertThat(row).isEqualTo("{\"nationalId\":\"" + expected + "\",\"age\":16}").doesNotContain(NATIONAL_ID);
        assertThat(result.outputs()).containsEntry("route", "MANUAL");

        caller.become("bank-a", "auditor", AUDIT);
        LoggedDecision read = engines.decisionLog().get(result.decisionId());
        assertThat(read.inputsJson()).isEqualTo(row);
        assertThat(read.toString()).doesNotContain(NATIONAL_ID);
        assertThat(read.matchedRuleIds()).containsExactly("R1");
        assertThat(read.outcome()).isEqualTo("OK");
    }

    @Test
    void RLE_012_lookup_by_plain_value_finds_every_decision_of_that_value() {
        RuleEngines engines = engines(MaskingPolicy.withKey(MaskingKey.of(KEY_A)));
        publish(engines, "bank-a", MINOR_REVIEW, "minor-review");
        String first = evaluate(engines, "bank-a", "minor-review", Map.of("nationalId", NATIONAL_ID, "age", 16))
                .decisionId();
        String second = evaluate(engines, "bank-a", "minor-review", Map.of("nationalId", NATIONAL_ID, "age", 30))
                .decisionId();
        evaluate(engines, "bank-a", "minor-review", Map.of("nationalId", "079090009999", "age", 30));

        caller.become("bank-a", "auditor", AUDIT);
        DecisionSearchResult found = engines.decisionLog().findByMaskedInput(byNationalId(NATIONAL_ID));

        assertThat(found.items()).extracting(LoggedDecision::decisionId).containsExactlyInAnyOrder(first, second);
        assertThat(found.truncated()).isFalse();
        assertThat(engines.decisionLog().findByMaskedInput(new MaskedInputQuery("minor-review", "nationalId",
                NATIONAL_ID, NOW.minusSeconds(3600), NOW.plusSeconds(3600), 1)).truncated()).isTrue();
        assertThat(engines.decisionLog().findByMaskedInput(byNationalId("079090000000")).items()).isEmpty();
    }

    @Test
    void RLE_012_tenants_with_their_own_keys_get_different_fingerprints_and_never_see_each_other() throws Exception {
        RuleEngines engines = engines(keys(Map.of("bank-a", KEY_A, "bank-b", KEY_B), Set.of()));
        publish(engines, "bank-a", MINOR_REVIEW, "minor-review");
        publish(engines, "bank-b", MINOR_REVIEW, "minor-review");
        String inA = evaluate(engines, "bank-a", "minor-review", Map.of("nationalId", NATIONAL_ID, "age", 16))
                .decisionId();
        String inB = evaluate(engines, "bank-b", "minor-review", Map.of("nationalId", NATIONAL_ID, "age", 16))
                .decisionId();

        assertThat(stores.decisions.get(0).maskedInputsJson()).contains("hmac:" + hmacHex(KEY_A, NATIONAL_ID));
        assertThat(stores.decisions.get(1).maskedInputsJson()).contains("hmac:" + hmacHex(KEY_B, NATIONAL_ID))
                .doesNotContain(hmacHex(KEY_A, NATIONAL_ID));

        caller.become("bank-b", "auditor", AUDIT);
        assertThat(engines.decisionLog().findByMaskedInput(byNationalId(NATIONAL_ID)).items())
                .extracting(LoggedDecision::decisionId).containsExactly(inB);
        assertThatThrownBy(() -> engines.decisionLog().get(inA)).isInstanceOf(DecisionNotFoundException.class);

        caller.become("bank-a", "auditor", AUDIT);
        assertThat(engines.decisionLog().findByMaskedInput(byNationalId(NATIONAL_ID)).items())
                .extracting(LoggedDecision::decisionId).containsExactly(inA);
        assertThatThrownBy(() -> engines.decisionLog().get(inB)).isInstanceOf(DecisionNotFoundException.class);
    }

    @Test
    void RLE_012_mask_default_masks_undeclared_inputs_and_mask_false_opts_out() throws Exception {
        RuleEngines engines = engines(keys(Map.of("bank-a", KEY_A, "bank-b", KEY_B), Set.of("bank-a")));
        publish(engines, "bank-a", CONTACT_ROUTING, "contact-routing");
        publish(engines, "bank-b", CONTACT_ROUTING, "contact-routing");

        evaluate(engines, "bank-a", "contact-routing", Map.of("phone", "0903123456", "segment", "RETAIL"));
        evaluate(engines, "bank-b", "contact-routing", Map.of("phone", "0903123456", "segment", "RETAIL"));

        // bank-a masks by default: phone (undeclared) is a fingerprint, segment (mask: false) stays plain.
        assertThat(stores.decisions.get(0).maskedInputsJson())
                .isEqualTo("{\"phone\":\"hmac:" + hmacHex(KEY_A, "0903123456") + "\",\"segment\":\"RETAIL\"}");
        // bank-b keeps the engine default: nothing is masked that the rule set does not mask.
        assertThat(stores.decisions.get(1).maskedInputsJson())
                .isEqualTo("{\"phone\":\"0903123456\",\"segment\":\"RETAIL\"}");
    }

    @Test
    void RLE_012_missing_key_fails_the_decision_closed() {
        RuleEngines engines = engines(keys(Map.of("bank-b", KEY_B), Set.of()));
        publish(engines, "bank-a", MINOR_REVIEW, "minor-review");

        DecisionResult result = evaluate(engines, "bank-a", "minor-review", Map.of("nationalId", NATIONAL_ID, "age", 16));

        assertThat(result.outcome()).isInstanceOfSatisfying(DecisionResult.Failed.class, failed -> {
            assertThat(failed.code()).isEqualTo("EVALUATION_ERROR");
            assertThat(failed.detail()).contains("horus.bpm.rules.masking.hmac-key").doesNotContain(NATIONAL_ID);
        });
        assertThat(stores.decisions).singleElement().satisfies(row -> {
            assertThat(row.outcome()).isEqualTo("EVALUATION_ERROR");
            assertThat(row.errorCode()).isEqualTo("MASKING_KEY_MISSING");
            assertThat(row.maskedInputsJson()).isEqualTo("{}");
        });
        caller.become("bank-a", "auditor", AUDIT);
        assertThatThrownBy(() -> engines.decisionLog().findByMaskedInput(byNationalId(NATIONAL_ID)))
                .isInstanceOf(QueryInvalidException.class).hasMessageContaining("masking key");
    }

    @Test
    void RLE_012_log_reads_need_rules_log_read_and_stay_bounded() {
        RuleEngines engines = engines(MaskingPolicy.withKey(MaskingKey.of(KEY_A)));
        publish(engines, "bank-a", MINOR_REVIEW, "minor-review");
        String id = evaluate(engines, "bank-a", "minor-review", Map.of("nationalId", NATIONAL_ID, "age", 16))
                .decisionId();

        caller.become("bank-a", "teller", EVALUATE);
        assertThatThrownBy(() -> engines.decisionLog().get(id)).isInstanceOf(PermissionDeniedException.class);
        assertThatThrownBy(() -> engines.decisionLog().findByMaskedInput(byNationalId(NATIONAL_ID)))
                .isInstanceOf(PermissionDeniedException.class);

        caller.become("bank-a", "auditor", AUDIT);
        assertThatThrownBy(() -> engines.decisionLog().findByMaskedInput(new MaskedInputQuery("minor-review",
                "nationalId", NATIONAL_ID, NOW.minusSeconds(32L * 86_400), NOW, 50)))
                .isInstanceOf(QueryInvalidException.class).hasMessageContaining("31 days");
        assertThatThrownBy(() -> engines.decisionLog().findByMaskedInput(new MaskedInputQuery("minor-review",
                "nationalId", NATIONAL_ID, NOW, NOW.minusSeconds(1), 50))).isInstanceOf(QueryInvalidException.class);
        assertThatThrownBy(() -> engines.decisionLog().findByMaskedInput(new MaskedInputQuery("minor-review",
                "nationalId", NATIONAL_ID, NOW.minusSeconds(60), NOW, 201))).isInstanceOf(QueryInvalidException.class);
        assertThatThrownBy(() -> engines.decisionLog().findByMaskedInput(new MaskedInputQuery("minor-review",
                "national'Id", NATIONAL_ID, NOW.minusSeconds(60), NOW, 5))).isInstanceOf(QueryInvalidException.class);
        assertThatThrownBy(() -> engines.decisionLog().get("not-a-decision-id"))
                .isInstanceOf(DecisionNotFoundException.class);
        assertThat(byNationalId(NATIONAL_ID).toString()).doesNotContain(NATIONAL_ID);
    }

    @Test
    void RLE_035_startup_check_names_the_setting_and_the_rule_set_never_the_key() {
        RuleEngines withoutKey = engines(MaskingPolicy.none());
        publish(withoutKey, "bank-a", MINOR_REVIEW, "minor-review");

        assertThatThrownBy(() -> withoutKey.maskingCheck().verify(List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("horus.bpm.rules.masking.hmac-key")
                .hasMessageContaining("minor-review")
                .hasMessageContaining("bank-a");
        engines(MaskingPolicy.withKey(MaskingKey.of(KEY_A))).maskingCheck().verify(List.of());
        assertThatThrownBy(() -> engines(MaskingPolicy.withKey(MaskingKey.of(KEY_A))).maskingCheck()
                .verify(List.of("horus.bpm.rules.masking.tenants.bank-b.hmac-key (too short)")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("horus.bpm.rules.masking.tenants.bank-b.hmac-key")
                .hasMessageNotContaining(new String(KEY_A, StandardCharsets.US_ASCII));
    }

    @Test
    void RLE_035_a_tenant_that_masks_by_default_needs_a_key_for_any_rule_set() {
        publish(engines(keys(Map.of(), Set.of())), "bank-a", CONTACT_ROUTING, "contact-routing");

        engines(keys(Map.of(), Set.of())).maskingCheck().verify(List.of());
        assertThatThrownBy(() -> engines(keys(Map.of(), Set.of("bank-a"))).maskingCheck().verify(List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("contact-routing").hasMessageContaining("phone");
    }

    @Test
    void TR_SEC_10_masking_key_requires_32_bytes_and_never_prints_itself() {
        String base64 = java.util.Base64.getEncoder().encodeToString(KEY_A);
        MaskingKey key = MaskingKey.fromBase64(base64);

        assertThat(key.toString()).isEqualTo("MaskingKey[redacted]").doesNotContain(base64);
        assertThat(MaskingKey.isFingerprint(key.fingerprint(NATIONAL_ID))).isTrue();
        assertThat(MaskingKey.fromBase64(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(KEY_A))
                .fingerprint(NATIONAL_ID)).isEqualTo(key.fingerprint(NATIONAL_ID));
        String short31 = java.util.Base64.getEncoder().encodeToString(bytes(31, 'z'));
        assertThatThrownBy(() -> MaskingKey.fromBase64(short31))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least 32 bytes")
                .hasMessageNotContaining(short31);
        assertThatThrownBy(() -> MaskingKey.fromBase64("not base64 !!"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("not base64 !!");
        assertThatThrownBy(() -> MaskingKey.fromBase64(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    static String hmacHex(byte[] key, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    static byte[] bytes(int length, char fill) {
        byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, (byte) fill);
        return bytes;
    }
}
