package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionNotFoundException;
import io.github.dhluong90.bpm.rules.api.DecisionRequest;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.DecisionSearchResult;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.LoggedDecision;
import io.github.dhluong90.bpm.rules.api.MaskedInputQuery;
import io.github.dhluong90.bpm.rules.api.MaskingKey;
import io.github.dhluong90.bpm.rules.api.PermissionDeniedException;
import io.github.dhluong90.bpm.rules.api.QueryInvalidException;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.spi.MaskingPolicy;
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
    /** Non-uniform keys, so a key read in the wrong byte order would give a different fingerprint. */
    static final byte[] KEY_A = sequence(32, 0x0b);
    static final byte[] KEY_B = seeded(32, 20261120L);
    static final Set<String> EVALUATE = Set.of("rules:evaluate");
    /** An auditor who may read decisions and search by a masked value; the search needs its own permission. */
    static final Set<String> AUDIT = Set.of("rules:log:read", "rules:masked:search");
    static final Set<String> READ_ONLY = Set.of("rules:log:read");

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
        return engines(masking, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    RuleEngines engines(MaskingPolicy masking, Clock clock) {
        return RuleEngines.create(new EngineContext(caller, caller, clock, stores, stores,
                EngineSettings.defaults(), masking), stores, stores, stores);
    }

    /** A clock the test moves forward. */
    static final class MovingClock extends Clock {
        private volatile Instant now;

        MovingClock(Instant start) {
            this.now = start;
        }

        void advance(java.time.Duration by) {
            now = now.plus(by);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
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
        long lock = engines.versionService().requestPublish(name, 1, WriteOptions.none()).lockVersion();
        caller.become(tenant, "bob");
        try {
            engines.versionService().approve(name, 1, new WriteOptions(null, lock, null));
        } catch (io.github.dhluong90.bpm.rules.api.WarningsNotAcknowledgedException shown) {
            // As a client does (RLE-053): show the current set, and approve accepting exactly it.
            engines.versionService().approve(name, 1, new WriteOptions(null, lock, null).acknowledging(shown.warnings().stream()
                    .map(w -> new io.github.dhluong90.bpm.rules.api.AcknowledgedWarning(w.code(), w.input())).toList()));
        }
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

        String expected = "hmac:k1:" + hmacHex(subkey(KEY_A, "bank-a"), NATIONAL_ID);
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
    void RLE_008_pinned_and_chosen_clock_decisions_show_their_marks_in_the_log_read_and_the_masked_search() {
        // Given minor-review in force from 1 Jan 2027, and three decisions on 15 Jan for the same national id:
        // one at the host clock, one back-dated to 10 Jan (rules:simulate) and one pinned to version 1 (rules:pin)
        RuleEngines engines = engines(MaskingPolicy.withKey(MaskingKey.of(KEY_A)));
        publish(engines, "bank-a", MINOR_REVIEW.replace("hitPolicy: first",
                "effectiveFrom: \"2027-01-01T00:00:00Z\"\nhitPolicy: first"), "minor-review");
        DecisionInput minor = DecisionInput.of(Map.of("nationalId", NATIONAL_ID, "age", 16));
        Instant tenthOfJanuary = Instant.parse("2027-01-10T09:00:00Z");
        String normal = evaluate(engines, "bank-a", "minor-review", Map.of("nationalId", NATIONAL_ID, "age", 16))
                .decisionId();
        caller.become("bank-a", "pricing", Set.of("rules:evaluate", "rules:simulate"));
        String backDated = engines.ruleEngine().evaluate(DecisionRequest.of("minor-review", minor)
                .withClock(tenthOfJanuary)).decisionId();
        caller.become("bank-a", "pricing", Set.of("rules:pin"));
        String pinned = engines.ruleEngine().evaluate(DecisionRequest.of("minor-review", minor).withVersion(1))
                .decisionId();

        // When the auditor reads each decision back, and finds them by the plain national id
        caller.become("bank-a", "auditor", AUDIT);
        LoggedDecision readNormal = engines.decisionLog().get(normal);
        LoggedDecision readBackDated = engines.decisionLog().get(backDated);
        LoggedDecision readPinned = engines.decisionLog().get(pinned);
        Map<String, LoggedDecision> found = new java.util.HashMap<>();
        engines.decisionLog().findByMaskedInput(byNationalId(NATIONAL_ID)).items()
                .forEach(d -> found.put(d.decisionId(), d));

        // Then the back-dated one shows its clock, the pinned one its pin, and the normal one neither
        assertThat(readNormal.clockChosen()).isFalse();
        assertThat(readNormal.pinned()).isFalse();
        assertThat(readNormal.evalClock()).isEqualTo(NOW).isEqualTo(readNormal.evaluatedAt());
        assertThat(readBackDated.clockChosen()).isTrue();
        assertThat(readBackDated.pinned()).isFalse();
        assertThat(readBackDated.evalClock()).isEqualTo(tenthOfJanuary);
        assertThat(readBackDated.evaluatedAt()).isEqualTo(NOW);
        assertThat(readPinned.pinned()).isTrue();
        assertThat(readPinned.clockChosen()).isFalse();
        assertThat(found).containsOnlyKeys(normal, backDated, pinned);
        for (LoggedDecision read : List.of(readNormal, readBackDated, readPinned)) {
            LoggedDecision searched = found.get(read.decisionId());
            assertThat(searched.evalClock()).isEqualTo(read.evalClock());
            assertThat(searched.clockChosen()).isEqualTo(read.clockChosen());
            assertThat(searched.pinned()).isEqualTo(read.pinned());
        }
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

        assertThat(stores.decisions.get(0).maskedInputsJson()).contains("hmac:k1:" + hmacHex(KEY_A, NATIONAL_ID));
        assertThat(stores.decisions.get(1).maskedInputsJson()).contains("hmac:k1:" + hmacHex(KEY_B, NATIONAL_ID))
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
                .isEqualTo("{\"phone\":\"hmac:k1:" + hmacHex(KEY_A, "0903123456") + "\",\"segment\":\"RETAIL\"}");
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

        assertThat(key.toString()).isEqualTo("MaskingKey[keyId=k1, key=redacted]").doesNotContain(base64);
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

    static final String ECHO = """
            schemaVersion: 1
            ruleSet: contact-echo
            hitPolicy: first
            inputs:
              - { name: phone, type: string, mask: true }
            outputs:
              - { name: notifyPhone, type: string }
            rules:
              - { id: E1, when: {}, then: { notifyPhone: "phone" } }
            """;

    @Test
    void RLE_012_an_output_that_reads_a_masked_input_is_refused_at_import() {
        RuleEngines engines = engines(MaskingPolicy.withKey(MaskingKey.of(KEY_A)));
        caller.become("bank-a", "alice");

        assertThatThrownBy(() -> engines.versionService().importRuleSet(
                ImportRequest.yaml(ECHO.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(io.github.dhluong90.bpm.rules.api.ValidationFailedException.class)
                .hasMessageContaining("notifyPhone").hasMessageContaining("phone").hasMessageContaining("masked");
        assertThatThrownBy(() -> engines.versionService().importRuleSet(ImportRequest.yaml(
                ECHO.replace("\"phone\"", "\"'+84 ' + phone\"").getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(io.github.dhluong90.bpm.rules.api.ValidationFailedException.class);
    }

    @Test
    void RLE_012_an_output_that_reads_an_input_masked_by_the_tenant_default_fails_closed() {
        RuleEngines engines = engines(keys(Map.of("bank-a", KEY_A), Set.of("bank-a")));
        publish(engines, "bank-a", ECHO.replace(", mask: true", ""),
                "contact-echo");

        DecisionResult result = evaluate(engines, "bank-a", "contact-echo", Map.of("phone", "0903123456"));

        assertThat(result.outcome()).isInstanceOf(DecisionResult.Failed.class);
        assertThat(stores.decisions).singleElement().satisfies(row -> {
            assertThat(row.errorCode()).isEqualTo("MASKED_INPUT_IN_OUTPUT");
            assertThat(row.maskedInputsJson()).isEqualTo("{}");
            assertThat(row.outputsJson()).isNull();
        });
    }

    @Test
    void RLE_012_search_tries_every_key_the_tenant_holds_so_rotation_keeps_old_rows_findable() throws Exception {
        MaskingKey old = MaskingKey.of("k1", KEY_A);
        MaskingKey current = MaskingKey.of("k2", KEY_B);
        RuleEngines before = engines(MaskingPolicy.withKey(old));
        publish(before, "bank-a", MINOR_REVIEW, "minor-review");
        String oldDecision = evaluate(before, "bank-a", "minor-review", Map.of("nationalId", NATIONAL_ID, "age", 16))
                .decisionId();
        RuleEngines after = engines(new MaskingPolicy() {
            @Override
            public Optional<MaskingKey> hmacKey(String tenantId) {
                return Optional.of(current.forTenant(tenantId));
            }

            @Override
            public List<MaskingKey> searchKeys(String tenantId) {
                return List.of(current.forTenant(tenantId), old.forTenant(tenantId));
            }
        });
        String newDecision = evaluate(after, "bank-a", "minor-review", Map.of("nationalId", NATIONAL_ID, "age", 30))
                .decisionId();

        assertThat(stores.decisions.get(0).maskedInputsJson()).contains("hmac:k1:"
                + hmacHex(subkey(KEY_A, "bank-a"), NATIONAL_ID));
        assertThat(stores.decisions.get(1).maskedInputsJson()).contains("hmac:k2:"
                + hmacHex(subkey(KEY_B, "bank-a"), NATIONAL_ID));
        caller.become("bank-a", "auditor", AUDIT);
        assertThat(after.decisionLog().findByMaskedInput(byNationalId(NATIONAL_ID)).items())
                .extracting(LoggedDecision::decisionId).containsExactlyInAnyOrder(oldDecision, newDecision);
    }

    @Test
    void RLE_012_every_masked_input_search_writes_an_audit_entry_without_the_value() throws Exception {
        RuleEngines engines = engines(MaskingPolicy.withKey(MaskingKey.of(KEY_A)));
        publish(engines, "bank-a", MINOR_REVIEW, "minor-review");
        evaluate(engines, "bank-a", "minor-review", Map.of("nationalId", NATIONAL_ID, "age", 16));
        int before = stores.outbox.size();

        caller.become("bank-a", "auditor", AUDIT);
        engines.decisionLog().findByMaskedInput(new MaskedInputQuery("minor-review", "nationalId", NATIONAL_ID,
                NOW.minusSeconds(3600), NOW.plusSeconds(3600), 50, "audit-case-17"));
        engines.decisionLog().findByMaskedInput(byNationalId("079090000000"));

        assertThat(stores.outbox).hasSize(before + 2);
        var entry = io.github.dhluong90.bpm.rules.internal.json.EngineValues.read(stores.outbox.get(before).payload());
        LifecycleEventPayloadTest.conformsTo("rules.decision-log.masked-input-searched.v1.schema.json", entry);
        assertThat(entry.path("outcome").asString()).isEqualTo("SEARCHED");
        assertThat(entry.path("actor").asString()).isEqualTo("auditor");
        assertThat(entry.path("tenantId").asString()).isEqualTo("bank-a");
        assertThat(entry.path("input").asString()).isEqualTo("nationalId");
        assertThat(entry.path("matches").asInt()).isEqualTo(1);
        assertThat(entry.path("correlationId").asString()).isEqualTo("audit-case-17");
        assertThat(entry.path("keyIds").get(0).asString()).isEqualTo("k1");
        assertThat(stores.outbox.get(before + 1).payload()).contains("\"matches\":0");
        for (int i = before; i < stores.outbox.size(); i++) {
            assertThat(stores.outbox.get(i).payload()).doesNotContain(NATIONAL_ID, "079090000000",
                    hmacHex(subkey(KEY_A, "bank-a"), NATIONAL_ID), hmacHex(subkey(KEY_A, "bank-a"), "079090000000"));
        }
    }

    @Test
    void TR_SEC_10_key_ids_are_short_labels_and_never_the_key() {
        assertThat(MaskingKey.of("2027-01", KEY_A).fingerprint(NATIONAL_ID)).startsWith("hmac:2027-01:");
        assertThat(MaskingKey.isFingerprint(MaskingKey.of("2027-01", KEY_A).fingerprint(NATIONAL_ID))).isTrue();
        assertThat(MaskingKey.isFingerprint("hmac:" + "0".repeat(64))).isFalse();
        assertThatThrownBy(() -> MaskingKey.of("k 1", KEY_A)).hasMessageContaining("key id");
        assertThatThrownBy(() -> MaskingKey.of("x".repeat(33), KEY_A)).hasMessageContaining("key id");
    }

    @Test
    void RLE_012_masked_search_needs_its_own_permission_not_implied_by_log_read() {
        RuleEngines engines = engines(MaskingPolicy.withKey(MaskingKey.of(KEY_A)));
        publish(engines, "bank-a", MINOR_REVIEW, "minor-review");
        String id = evaluate(engines, "bank-a", "minor-review", Map.of("nationalId", NATIONAL_ID, "age", 16))
                .decisionId();

        caller.become("bank-a", "auditor", READ_ONLY);
        assertThat(engines.decisionLog().get(id).decisionId()).isEqualTo(id);
        assertThatThrownBy(() -> engines.decisionLog().findByMaskedInput(byNationalId(NATIONAL_ID)))
                .isInstanceOf(PermissionDeniedException.class).hasMessageContaining("rules:masked:search");

        caller.become("bank-a", "privacy-ops", Set.of("rules:masked:search"));
        assertThat(engines.decisionLog().findByMaskedInput(byNationalId(NATIONAL_ID)).items()).hasSize(1);
        assertThatThrownBy(() -> engines.decisionLog().get(id)).isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void RLE_012_masked_search_is_limited_per_caller_per_minute_and_refusals_are_audited() throws Exception {
        MovingClock clock = new MovingClock(NOW);
        MaskingPolicy threePerMinute = new MaskingPolicy() {
            @Override
            public Optional<MaskingKey> hmacKey(String tenantId) {
                return Optional.of(MaskingKey.of(KEY_A));
            }

            @Override
            public int searchesPerMinute(String tenantId) {
                return 3;
            }
        };
        RuleEngines engines = engines(threePerMinute, clock);
        publish(engines, "bank-a", MINOR_REVIEW, "minor-review");
        evaluate(engines, "bank-a", "minor-review", Map.of("nationalId", NATIONAL_ID, "age", 16));
        caller.become("bank-a", "auditor", AUDIT);
        for (int i = 0; i < 3; i++) {
            engines.decisionLog().findByMaskedInput(byNationalId(NATIONAL_ID));
            clock.advance(java.time.Duration.ofSeconds(10));
        }
        int before = stores.outbox.size();

        // The fourth search within one minute is refused, with the time until the first one leaves the window.
        assertThatThrownBy(() -> engines.decisionLog().findByMaskedInput(byNationalId(NATIONAL_ID)))
                .isInstanceOfSatisfying(io.github.dhluong90.bpm.rules.api.RateLimitedException.class, e -> {
                    assertThat(e.code()).isEqualTo("RATE_LIMITED");
                    assertThat(e.retryAfterSeconds()).isEqualTo(30);
                    assertThat(e.getMessage()).contains("masking.search.max-per-minute").doesNotContain(NATIONAL_ID);
                });
        var refused = io.github.dhluong90.bpm.rules.internal.json.EngineValues.read(stores.outbox.get(before).payload());
        LifecycleEventPayloadTest.conformsTo("rules.decision-log.masked-input-searched.v1.schema.json", refused);
        assertThat(refused.path("outcome").asString()).isEqualTo("RATE_LIMITED");
        assertThat(refused.path("matches").asInt()).isZero();
        assertThat(stores.outbox.get(before).payload()).doesNotContain(NATIONAL_ID,
                hmacHex(subkey(KEY_A, "bank-a"), NATIONAL_ID));

        // Another caller of the same tenant has its own allowance.
        caller.become("bank-a", "second-auditor", AUDIT);
        assertThat(engines.decisionLog().findByMaskedInput(byNationalId(NATIONAL_ID)).items()).hasSize(1);

        // Once the first search is a minute old, the first caller may search again.
        caller.become("bank-a", "auditor", AUDIT);
        clock.advance(java.time.Duration.ofSeconds(30));
        assertThat(engines.decisionLog().findByMaskedInput(byNationalId(NATIONAL_ID)).items()).hasSize(1);
    }

    @Test
    void RLE_012_the_search_limit_cannot_be_switched_off() {
        RuleEngines engines = engines(new MaskingPolicy() {
            @Override
            public Optional<MaskingKey> hmacKey(String tenantId) {
                return Optional.of(MaskingKey.of(KEY_A));
            }

            @Override
            public int searchesPerMinute(String tenantId) {
                return 0; // a host bean that tries to disable the limit gets one search per minute
            }
        });
        publish(engines, "bank-a", MINOR_REVIEW, "minor-review");
        caller.become("bank-a", "auditor", AUDIT);

        engines.decisionLog().findByMaskedInput(byNationalId(NATIONAL_ID));
        assertThatThrownBy(() -> engines.decisionLog().findByMaskedInput(byNationalId(NATIONAL_ID)))
                .isInstanceOf(io.github.dhluong90.bpm.rules.api.RateLimitedException.class);
    }

    /** The HKDF-SHA256 tenant subkey, computed here independently of the engine (RFC 5869, empty salt, 32 bytes). */
    static byte[] subkey(byte[] hostKey, String tenant) throws Exception {
        Mac extract = Mac.getInstance("HmacSHA256");
        extract.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
        byte[] prk = extract.doFinal(hostKey);
        Mac expand = Mac.getInstance("HmacSHA256");
        expand.init(new SecretKeySpec(prk, "HmacSHA256"));
        expand.update(("horus-bpm/masking/v1/tenant/" + tenant).getBytes(StandardCharsets.UTF_8));
        expand.update((byte) 1);
        return expand.doFinal();
    }

    @Test
    void RLE_012_tenants_sharing_the_host_key_get_different_tokens_from_their_own_subkeys() throws Exception {
        RuleEngines engines = engines(MaskingPolicy.withKey(MaskingKey.of(KEY_A)));
        publish(engines, "bank-a", MINOR_REVIEW, "minor-review");
        publish(engines, "bank-b", MINOR_REVIEW, "minor-review");
        evaluate(engines, "bank-a", "minor-review", Map.of("nationalId", NATIONAL_ID, "age", 16));
        evaluate(engines, "bank-b", "minor-review", Map.of("nationalId", NATIONAL_ID, "age", 16));

        String inA = stores.decisions.get(0).maskedInputsJson();
        String inB = stores.decisions.get(1).maskedInputsJson();
        assertThat(inA).contains("hmac:k1:" + hmacHex(subkey(KEY_A, "bank-a"), NATIONAL_ID));
        assertThat(inB).contains("hmac:k1:" + hmacHex(subkey(KEY_A, "bank-b"), NATIONAL_ID));
        assertThat(inA).isNotEqualTo(inB).doesNotContain(hmacHex(KEY_A, NATIONAL_ID));
        assertThat(inB).doesNotContain(hmacHex(KEY_A, NATIONAL_ID));
    }

    @Test
    void RLE_012_an_explicit_tenant_key_is_used_as_it_is() throws Exception {
        RuleEngines engines = engines(keys(Map.of("bank-a", KEY_B), Set.of()));
        publish(engines, "bank-a", MINOR_REVIEW, "minor-review");

        evaluate(engines, "bank-a", "minor-review", Map.of("nationalId", NATIONAL_ID, "age", 16));

        assertThat(stores.decisions.getFirst().maskedInputsJson())
                .contains("hmac:k1:" + hmacHex(KEY_B, NATIONAL_ID))
                .doesNotContain(hmacHex(subkey(KEY_B, "bank-a"), NATIONAL_ID));
    }

    @Test
    void TR_SEC_10_hkdf_matches_rfc_5869_and_the_documented_tenant_vector() {
        // RFC 5869 test case 3: SHA-256, IKM 22 bytes of 0x0b, no salt, no info, L = 42.
        byte[] okm = MaskingKey.hkdfSha256(bytes(22, (char) 0x0b), new byte[0], new byte[0], 42);
        assertThat(HexFormat.of().formatHex(okm)).isEqualTo("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e"
                + "5f3c738d2d9d201395faa4b61a96c8");
        // The vector of DESIGN section 6 and the wiki: host key 00 01 .. 1f, tenant bank-a, value 079090001234.
        byte[] host = new byte[32];
        for (int i = 0; i < 32; i++) {
            host[i] = (byte) i;
        }
        assertThat(MaskingKey.of(host).forTenant("bank-a").fingerprint(NATIONAL_ID))
                .isEqualTo("hmac:k1:e33165b4a825adb209efdafa2da3414acec8cc341b041985c539c537f69beeac");
        assertThat(MaskingKey.of(host).forTenant("bank-b").fingerprint(NATIONAL_ID))
                .isEqualTo("hmac:k1:4edfc45a138147c7adcfee3a67777fc17e3ac39db08af486b31929448eb809d7");
        assertThat(MaskingKey.of("k7", host).forTenant("bank-a").keyId()).isEqualTo("k7");
    }

    @Test
    void TR_SEC_10_a_subkey_is_refused_for_a_tenant_id_that_cannot_name_a_tenant() {
        MaskingKey host = MaskingKey.of(KEY_A);
        for (String bad : new String[] {"", "  ", "bank\u0000a", "bank-a\n", "bank\uD800a", "bank\uDC00"}) {
            assertThatThrownBy(() -> host.forTenant(bad)).as("tenant id %s", bad.codePoints().boxed().toList())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tenant id");
        }
        assertThatThrownBy(() -> host.forTenant(null)).isInstanceOf(IllegalArgumentException.class);
        // A paired surrogate is a real character and derives a subkey of its own.
        assertThat(host.forTenant("bank-\uD83D\uDE00").fingerprint(NATIONAL_ID))
                .isNotEqualTo(host.forTenant("bank-a").fingerprint(NATIONAL_ID));
    }

    @Test
    void TR_SEC_10_keys_compare_by_their_bytes_not_their_ids() {
        assertThat(MaskingKey.of("k1", KEY_A).sameKeyAs(MaskingKey.of("k9", KEY_A))).isTrue();
        assertThat(MaskingKey.of("k1", KEY_A).sameKeyAs(MaskingKey.of("k1", KEY_B))).isFalse();
        assertThat(MaskingKey.of(KEY_A).sameKeyAs(MaskingKey.of(KEY_A).forTenant("bank-a"))).isFalse();
    }

    @Test
    void TR_SEC_10_key_check_value_comes_from_a_key_derived_for_it_alone_and_never_the_key() throws Exception {
        MaskingKey key = MaskingKey.of("k1", KEY_A);
        String expected = kcvHex(KEY_A);
        assertThat(key.checkValue()).isEqualTo(expected).matches("^[0-9a-f]{16}$");
        // The id does not change it; the bytes do; a tenant subkey has a check value of its own.
        assertThat(MaskingKey.of("k9", KEY_A).checkValue()).isEqualTo(expected);
        assertThat(MaskingKey.of("k1", KEY_B).checkValue()).isNotEqualTo(expected);
        assertThat(key.forTenant("bank-a").checkValue()).isNotEqualTo(expected);
        assertThat(key.checkValue()).doesNotContain(HexFormat.of().formatHex(KEY_A).substring(0, 16));
        // A separate domain: the token of an input whose value is the derivation label is not the check value.
        assertThat(key.fingerprint(MaskingKey.CHECK_VALUE_INFO)).doesNotContain(expected);
        assertThat(hmacHex(KEY_A, MaskingKey.CHECK_VALUE_INFO)).doesNotStartWith(expected);
        // The documented vector: host key 00 01 .. 1f.
        byte[] host = new byte[32];
        for (int i = 0; i < 32; i++) {
            host[i] = (byte) i;
        }
        assertThat(MaskingKey.of(host).checkValue()).isEqualTo("471be5b6391b902f");
    }

    /** The check value computed here, apart from MaskingKey: HKDF-SHA256 with the kcv info, then HMAC of nothing. */
    static String kcvHex(byte[] key) throws Exception {
        Mac extract = Mac.getInstance("HmacSHA256");
        extract.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
        byte[] prk = extract.doFinal(key);
        Mac expand = Mac.getInstance("HmacSHA256");
        expand.init(new SecretKeySpec(prk, "HmacSHA256"));
        expand.update("horus-bpm/masking/v1/kcv".getBytes(StandardCharsets.UTF_8));
        expand.update((byte) 1);
        Mac check = Mac.getInstance("HmacSHA256");
        check.init(new SecretKeySpec(expand.doFinal(), "HmacSHA256"));
        return HexFormat.of().formatHex(check.doFinal(new byte[0])).substring(0, 16);
    }

    static String hmacHex(byte[] key, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    static byte[] sequence(int length, int first) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (first + 7 * i);
        }
        return bytes;
    }

    static byte[] seeded(int length, long seed) {
        byte[] bytes = new byte[length];
        new java.util.Random(seed).nextBytes(bytes);
        return bytes;
    }

    static byte[] bytes(int length, char fill) {
        byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, (byte) fill);
        return bytes;
    }
}
