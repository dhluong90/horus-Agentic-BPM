package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.MaskingKey;
import io.github.dhluong90.bpm.rules.api.PersonalDataNames;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.api.VersionDiff;
import io.github.dhluong90.bpm.rules.api.VersionDiff.InputMasking;
import io.github.dhluong90.bpm.rules.api.VersionDiff.Masking;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.api.VersionWarning;
import io.github.dhluong90.bpm.rules.api.Violation;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.spi.MaskingPolicy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The guards against unmasked personal data (PD-099): the warning for an undeclared input that looks like personal
 * data (RLE-049), strict mode (RLE-050), masking in the version diff (RLE-051) and the start-up record of the masking
 * default (RLE-052), through the public services on the in-memory stores. Users at bank-a: lan authors, bob approves.
 */
class UnmaskedPersonalDataTest {

    static final Instant JAN_15 = Instant.parse("2027-01-15T02:00:00Z");
    static final Set<String> AUTHOR = Set.of("rules:read", "rules:author");
    static final Set<String> APPROVER = Set.of("rules:read", "rules:approve");

    /** The KYC routing table of a retail bank: nationalId forgotten (no mask), age and segment not personal data. */
    static final String KYC_ROUTING = """
            schemaVersion: 1
            ruleSet: kyc-routing
            hitPolicy: first
            inputs:
              - { name: nationalId, type: string }
              - { name: age, type: integer, mask: false }
              - { name: segment, type: string, mask: false }
            outputs:
              - { name: route, type: string }
            rules:
              - { id: R1, when: { nationalId: "is null" }, then: { route: "'MANUAL'" } }
              - { id: R2, when: { age: "< 18" }, then: { route: "'MANUAL'" } }
              - { id: R3, when: {}, then: { route: "'AUTO'" } }
            """;

    /** The same table with every input declared: nationalId masked. */
    static final String KYC_ROUTING_DECLARED = KYC_ROUTING.replace("{ name: nationalId, type: string }",
            "{ name: nationalId, type: string, mask: true }");

    /** The same table with nationalId declared mask: false, as a careless edit would. */
    static final String KYC_ROUTING_UNMASKED = KYC_ROUTING.replace("{ name: nationalId, type: string }",
            "{ name: nationalId, type: string, mask: false }");

    /** A Vietnamese-named contact table: so_dien_thoai and ngaySinh undeclared, kenh (channel) not personal data. */
    static final String LIEN_HE = """
            schemaVersion: 1
            ruleSet: lien-he
            hitPolicy: first
            inputs:
              - { name: so_dien_thoai, type: string }
              - { name: ngaySinh, type: date }
              - { name: kenh, type: string }
            outputs:
              - { name: uuTien, type: string }
            rules:
              - { id: L1, when: {}, then: { uuTien: "'SMS'" } }
            """;

    /** Inputs that must never warn: declared ones, and names that only contain a short entry inside a word. */
    static final String CARD_FEE_PLAIN = """
            schemaVersion: 1
            ruleSet: company-fee
            hitPolicy: first
            inputs:
              - { name: company, type: string }
              - { name: tier, type: string }
              - { name: customerEmail, type: string, mask: true }
              - { name: homeAddress, type: string, mask: false }
            outputs:
              - { name: fee, type: integer }
            rules:
              - { id: F1, when: {}, then: { fee: "0" } }
            """;

    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "lan");
    final Policy policy = new Policy();

    VersionService versions() {
        RuleEngines engines = RuleEngines.create(new EngineContext(caller, caller, Clock.fixed(JAN_15, ZoneOffset.UTC),
                stores, stores, EngineSettings.defaults().withRequireContentHash(false).withDraftAuthoring(true), policy), stores, stores, stores);
        engines.cursorKeys().load();
        return engines.versionService();
    }

    RuleEngines engines() {
        return RuleEngines.create(new EngineContext(caller, caller, Clock.fixed(JAN_15, ZoneOffset.UTC), stores,
                stores, EngineSettings.defaults().withRequireContentHash(false), policy), stores, stores, stores);
    }

    static ImportRequest yaml(String text) {
        return ImportRequest.yaml(text.getBytes(StandardCharsets.UTF_8));
    }

    /** A masking policy the test sets per tenant, as a host's own bean or the property-backed default would. */
    static final class Policy implements MaskingPolicy {
        final Set<String> strict = new HashSet<>();
        final Set<String> maskByDefault = new HashSet<>();
        final Map<String, PersonalDataNames> names = new HashMap<>();

        @Override
        public Optional<MaskingKey> hmacKey(String tenantId) {
            return Optional.of(MaskingKey.of(new byte[32]));
        }

        @Override
        public boolean maskByDefault(String tenantId) {
            return maskByDefault.contains(tenantId);
        }

        @Override
        public boolean requireExplicitMask(String tenantId) {
            return strict.contains(tenantId);
        }

        @Override
        public PersonalDataNames personalDataNames(String tenantId) {
            return names.getOrDefault(tenantId, PersonalDataNames.builtIn());
        }
    }

    // ---------------------------------------------------------------- RLE-049 warning

    @Test
    void RLE_049_an_undeclared_national_id_warns_at_import_naming_the_input_and_the_matched_name() {
        // Given lan, an author at bank-a, with the built-in personal-data names
        caller.become("bank-a", "lan", AUTHOR);

        // When she imports kyc-routing, whose nationalId declares no mask
        RuleSetVersion imported = versions().importRuleSet(yaml(KYC_ROUTING));

        // Then the import succeeds as a draft with one warning naming nationalId and "national id"
        assertThat(imported.status()).isEqualTo(VersionStatus.DRAFT);
        assertThat(imported.warnings()).singleElement().satisfies(warning -> {
            assertThat(warning.code()).isEqualTo(VersionWarning.UNMASKED_PERSONAL_DATA);
            assertThat(warning.input()).isEqualTo("nationalId");
            assertThat(warning.matchedName()).isEqualTo("national id");
            assertThat(warning.message()).contains("nationalId", "\"national id\"", "plain text", "mask: true");
        });
    }

    @Test
    void RLE_049_the_publication_request_and_the_approver_see_the_same_warning() {
        // Given kyc-routing v1 imported by lan with nationalId undeclared
        caller.become("bank-a", "lan", AUTHOR);
        VersionService versions = versions();
        versions.importRuleSet(yaml(KYC_ROUTING));

        // When lan requests publication
        RuleSetVersion requested = versions.requestPublish("kyc-routing", 1, WriteOptions.none());

        // Then the request answers with the warning
        assertThat(requested.status()).isEqualTo(VersionStatus.PENDING_APPROVAL);
        assertThat(requested.warnings()).extracting(VersionWarning::input).containsExactly("nationalId");

        // And bob, reading the pending change to approve it, sees the same warning
        caller.become("bank-a", "bob", APPROVER);
        RuleSetVersion pending = versions.find("kyc-routing", 1, false).orElseThrow();
        assertThat(pending.warnings()).isEqualTo(requested.warnings());
    }

    @Test
    void RLE_049_vietnamese_names_warn_and_declared_or_unrelated_inputs_never_do() {
        // Given lan at bank-a
        caller.become("bank-a", "lan", AUTHOR);
        VersionService versions = versions();

        // When she imports lien-he (so_dien_thoai and ngaySinh undeclared) and company-fee (declared or unrelated)
        RuleSetVersion lienHe = versions.importRuleSet(yaml(LIEN_HE));
        RuleSetVersion companyFee = versions.importRuleSet(yaml(CARD_FEE_PLAIN));

        // Then so_dien_thoai matches "so dien thoai" and ngaySinh "ngay sinh"; kenh does not warn
        assertThat(lienHe.warnings()).extracting(VersionWarning::input, VersionWarning::matchedName)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("so_dien_thoai", "so dien thoai"),
                        org.assertj.core.groups.Tuple.tuple("ngaySinh", "ngay sinh"));
        // And company (which holds "pan" only inside a word), tier and the two declared inputs give no warning
        assertThat(companyFee.warnings()).isEmpty();
    }

    @Test
    void RLE_049_a_tenant_list_applies_to_that_tenant_only() {
        // Given bank-b replaces its list with "segment" only, and bank-a keeps the built-in list
        policy.names.put("bank-b", PersonalDataNames.of(List.of("segment")));
        VersionService versions = versions();

        // When both import kyc-routing (segment declares mask: false, nationalId nothing)
        caller.become("bank-a", "lan", AUTHOR);
        RuleSetVersion atBankA = versions.importRuleSet(yaml(KYC_ROUTING));
        caller.become("bank-b", "lan", AUTHOR);
        RuleSetVersion atBankB = versions.importRuleSet(yaml(KYC_ROUTING));

        // Then bank-a warns about nationalId; bank-b's list has no "national id", and segment is declared
        assertThat(atBankA.warnings()).extracting(VersionWarning::input).containsExactly("nationalId");
        assertThat(atBankB.warnings()).isEmpty();
    }

    @Test
    void RLE_049_a_replayed_import_returns_the_original_warnings_and_masking_by_default_is_explained() {
        // Given bank-a masks undeclared inputs by default
        policy.maskByDefault.add("bank-a");
        caller.become("bank-a", "lan", AUTHOR);
        VersionService versions = versions();
        ImportRequest request = yaml(KYC_ROUTING).withIdempotencyKey("imp-283");

        // When lan imports kyc-routing twice with the same idempotency key
        RuleSetVersion first = versions.importRuleSet(request);
        RuleSetVersion replay = versions.importRuleSet(request);

        // Then the replay is the original result, warnings included
        assertThat(replay).isEqualTo(first);
        // And the warning says nationalId is masked only by the tenant default
        assertThat(first.warnings()).singleElement()
                .satisfies(warning -> assertThat(warning.message()).contains("masked only because the tenant"));
    }

    // ---------------------------------------------------------------- RLE-050 strict mode

    @Test
    void RLE_050_strict_mode_refuses_an_undeclared_input_naming_it_and_stores_nothing() {
        // Given bank-a in strict mode
        policy.strict.add("bank-a");
        caller.become("bank-a", "lan", AUTHOR);
        VersionService versions = versions();
        int events = stores.outbox.size();

        // When lan imports kyc-routing with nationalId undeclared
        // Then 422 VALIDATION_FAILED with one violation whose column is nationalId
        assertThatThrownBy(() -> versions.importRuleSet(yaml(KYC_ROUTING)))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> {
                    assertThat(e.code()).isEqualTo("VALIDATION_FAILED");
                    assertThat(e.violations()).extracting(Violation::column).containsExactly("nationalId");
                    assertThat(e.getMessage()).contains("nationalId", "decision-log.require-explicit-mask");
                });
        // And nothing is stored: no rule set, no version, no event
        assertThat(versions.findRuleSet("kyc-routing")).isEmpty();
        assertThat(stores.outbox).hasSize(events);
    }

    @Test
    void RLE_050_strict_mode_accepts_a_rule_set_that_declares_every_input() {
        // Given bank-a in strict mode
        policy.strict.add("bank-a");
        caller.become("bank-a", "lan", AUTHOR);

        // When lan imports kyc-routing with every input declared
        RuleSetVersion imported = versions().importRuleSet(yaml(KYC_ROUTING_DECLARED));

        // Then the import succeeds, with no warning
        assertThat(imported.version()).isEqualTo(1);
        assertThat(imported.warnings()).isEmpty();
    }

    @Test
    void RLE_050_strict_mode_turned_on_later_stops_the_publication_request_of_an_undeclared_draft() {
        // Given lan imported kyc-routing (nationalId undeclared) before bank-a turned strict mode on
        caller.become("bank-a", "lan", AUTHOR);
        VersionService versions = versions();
        versions.importRuleSet(yaml(KYC_ROUTING));
        policy.strict.add("bank-a");

        // When she requests publication
        // Then it is refused naming nationalId, and the version stays a draft
        assertThatThrownBy(() -> versions.requestPublish("kyc-routing", 1, WriteOptions.none()))
                .isInstanceOfSatisfying(ValidationFailedException.class, e ->
                        assertThat(e.violations()).extracting(Violation::column).containsExactly("nationalId"));
        assertThat(versions.find("kyc-routing", 1, false).orElseThrow().status()).isEqualTo(VersionStatus.DRAFT);
    }

    @Test
    void RLE_050_strict_mode_turned_on_between_request_and_approval_stops_the_approval() {
        // Given lan requested publication of kyc-routing (nationalId undeclared) while strict mode was off
        VersionService versions = versions();
        caller.become("bank-a", "lan", AUTHOR);
        versions.importRuleSet(yaml(KYC_ROUTING));
        RuleSetVersion requested = versions.requestPublish("kyc-routing", 1, WriteOptions.none());
        int events = stores.outbox.size();

        // When bank-a turns strict mode on and bob approves with the current lock version
        policy.strict.add("bank-a");
        caller.become("bank-a", "bob", APPROVER);

        // Then the approval is refused naming nationalId, nothing is published, and no event is written
        assertThatThrownBy(() -> versions.approve("kyc-routing", 1,
                new WriteOptions(null, requested.lockVersion(), null)))
                .isInstanceOfSatisfying(ValidationFailedException.class, e ->
                        assertThat(e.violations()).extracting(Violation::column).containsExactly("nationalId"));
        RuleSetVersion stored = versions.find("kyc-routing", 1, false).orElseThrow();
        assertThat(stored.status()).isEqualTo(VersionStatus.PENDING_APPROVAL);
        assertThat(stored.approvedBy()).isNull();
        assertThat(stored.lockVersion()).isEqualTo(requested.lockVersion());
        assertThat(stores.outbox).hasSize(events);
    }

    @Test
    void RLE_050_strict_mode_checks_a_replaced_draft_and_is_set_per_tenant() {
        // Given bank-a in strict mode with a declared draft, and bank-b not in strict mode
        policy.strict.add("bank-a");
        VersionService versions = versions();
        caller.become("bank-a", "lan", AUTHOR);
        RuleSetVersion draft = versions.importRuleSet(yaml(KYC_ROUTING_DECLARED));

        // When lan replaces the draft with the undeclared document
        // Then the replacement is refused naming nationalId, and the stored draft is unchanged
        assertThatThrownBy(() -> versions.replaceDraft("kyc-routing", 1, yaml(KYC_ROUTING),
                new WriteOptions("put-283", draft.lockVersion(), null)))
                .isInstanceOfSatisfying(ValidationFailedException.class, e ->
                        assertThat(e.violations()).extracting(Violation::column).containsExactly("nationalId"));
        assertThat(versions.find("kyc-routing", 1, false).orElseThrow().canonicalHash())
                .isEqualTo(draft.canonicalHash());

        // And bank-b, without strict mode, imports the undeclared document with a warning only
        caller.become("bank-b", "lan", AUTHOR);
        assertThat(versions.importRuleSet(yaml(KYC_ROUTING)).warnings()).hasSize(1);
    }

    // ---------------------------------------------------------------- RLE-051 masking in the diff

    @Test
    void RLE_051_the_diff_marks_national_id_going_from_masked_to_not_masked() {
        // Given kyc-routing v1 published with nationalId mask: true, and lan's draft v2 with mask: false
        VersionService versions = versions();
        caller.become("bank-a", "lan", AUTHOR);
        versions.importRuleSet(yaml(KYC_ROUTING_DECLARED));
        versions.requestPublish("kyc-routing", 1, WriteOptions.none());
        caller.become("bank-a", "bob", APPROVER);
        versions.approve("kyc-routing", 1, new WriteOptions(null, 1L, null));
        caller.become("bank-a", "lan", AUTHOR);
        versions.importVersion("kyc-routing", yaml(KYC_ROUTING_UNMASKED));

        // When bob reads the diff of v2 against v1
        caller.become("bank-a", "bob", APPROVER);
        VersionDiff diff = versions.diff("kyc-routing", 2, 1);

        // Then nationalId is marked MASKED to NOT_MASKED; age and segment are unchanged
        assertThat(diff.masking()).containsExactly(
                new InputMasking("nationalId", Masking.MASKED, Masking.NOT_MASKED, true, null),
                new InputMasking("age", Masking.NOT_MASKED, Masking.NOT_MASKED, false, null),
                new InputMasking("segment", Masking.NOT_MASKED, Masking.NOT_MASKED, false, null));
        // And the column change is still reported as before
        assertThat(diff.inputs().changed()).extracting(VersionDiff.ColumnChange::name).containsExactly("nationalId");
    }

    @Test
    void RLE_051_the_diff_shows_the_default_status_and_marks_a_look_alike_and_a_removed_input() {
        // Given bank-a masks undeclared inputs by default; v1 declares nationalId mask: true, v2 drops the mask
        // and the segment input
        policy.maskByDefault.add("bank-a");
        VersionService versions = versions();
        caller.become("bank-a", "lan", AUTHOR);
        versions.importRuleSet(yaml(KYC_ROUTING_DECLARED));
        versions.importVersion("kyc-routing", yaml(KYC_ROUTING.replace(
                "  - { name: segment, type: string, mask: false }\n", "")));

        // When the diff of v2 against v1 is read
        VersionDiff diff = versions.diff("kyc-routing", 2, 1);

        // Then nationalId is MASKED to MASKED_BY_DEFAULT (changed, and a personal-data look-alike), and segment
        // is reported as removed
        assertThat(diff.masking()).containsExactly(
                new InputMasking("nationalId", Masking.MASKED, Masking.MASKED_BY_DEFAULT, true, "national id"),
                new InputMasking("age", Masking.NOT_MASKED, Masking.NOT_MASKED, false, null),
                new InputMasking("segment", Masking.NOT_MASKED, null, true, null));
        assertThat(Masking.MASKED_BY_DEFAULT.masked()).isTrue();
        assertThat(Masking.MASKED_BY_DEFAULT.declared()).isFalse();
    }

    // ---------------------------------------------------------------- RLE-052 masking default changes

    @Test
    void RLE_052_a_changed_masking_default_is_one_line_with_the_tenant_and_the_old_and_new_value() {
        // Given bank-a has kyc-routing and started last time with mask-default true, bank-b with false
        caller.become("bank-a", "lan", AUTHOR);
        versions().importRuleSet(yaml(KYC_ROUTING_DECLARED));
        stores.recordSettingSeen(MaskingStartupCheck.HOST_DEFAULT_ROW, MaskingStartupCheck.MASK_DEFAULT_SETTING,
                "false", JAN_15);
        stores.recordSettingSeen("bank-a", MaskingStartupCheck.MASK_DEFAULT_SETTING, "true", JAN_15);
        stores.recordSettingSeen("bank-b", MaskingStartupCheck.MASK_DEFAULT_SETTING, "false", JAN_15);

        // When the host starts with mask-default false for both
        List<String> lines = engines().maskingCheck().recordMaskDefaults(Set.of(), JAN_15.plusSeconds(60));

        // Then one line names bank-a with the old and new value; bank-b, unchanged, is not logged
        assertThat(lines).containsExactly(
                "decision-log.mask-default for tenant bank-a changed from true to false since the last start-up");
        // And the value in force is recorded, so the next start-up logs nothing
        assertThat(stores.settingsSeen(MaskingStartupCheck.MASK_DEFAULT_SETTING))
                .containsEntry("bank-a", "false").containsEntry("bank-b", "false");
        assertThat(engines().maskingCheck().recordMaskDefaults(Set.of(), JAN_15.plusSeconds(120))).isEmpty();
    }

    @Test
    void RLE_052_the_first_start_up_records_the_host_default_and_every_tenant() {
        // Given bank-c is named in the host's settings and masks by default; nothing was ever recorded
        policy.maskByDefault.add("bank-c");

        // When the host starts
        List<String> lines = engines().maskingCheck().recordMaskDefaults(Set.of("bank-c"), JAN_15);

        // Then the host default and bank-c are recorded as first seen
        assertThat(lines).containsExactly("decision-log.mask-default for the host default is false (first recorded)",
                "decision-log.mask-default for tenant bank-c is true (first recorded)");
        assertThat(stores.settingsSeen(MaskingStartupCheck.MASK_DEFAULT_SETTING))
                .containsEntry(MaskingStartupCheck.HOST_DEFAULT_ROW, "false").containsEntry("bank-c", "true");
    }

    @Test
    void RLE_052_a_tenant_new_since_the_last_start_up_is_compared_with_the_host_default_it_followed() {
        // Given the last start-up recorded the host default false, and bank-d got its first rule set since
        stores.recordSettingSeen(MaskingStartupCheck.HOST_DEFAULT_ROW, MaskingStartupCheck.MASK_DEFAULT_SETTING,
                "false", JAN_15);
        caller.become("bank-d", "lan", AUTHOR);
        versions().importRuleSet(yaml(KYC_ROUTING_DECLARED));

        // When the host restarts with bank-d masking by default
        policy.maskByDefault.add("bank-d");
        List<String> lines = engines().maskingCheck().recordMaskDefaults(Set.of(), JAN_15.plusSeconds(60));

        // Then the change from the default it followed is logged, not hidden as a first record
        assertThat(lines).containsExactly(
                "decision-log.mask-default for tenant bank-d changed from false to true since the last start-up");
    }
}
