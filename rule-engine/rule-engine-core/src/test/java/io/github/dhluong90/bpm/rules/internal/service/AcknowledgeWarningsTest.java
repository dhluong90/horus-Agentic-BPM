package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.AcknowledgedWarning;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.LifecycleAction;
import io.github.dhluong90.bpm.rules.api.PersonalDataNames;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.api.VersionConflictException;
import io.github.dhluong90.bpm.rules.api.VersionHistoryEntry;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.api.WarningsNotAcknowledgedException;
import io.github.dhluong90.bpm.rules.api.WarningsNotAcknowledgedException.Unacknowledged;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Approving a version with an unmasked personal-data warning needs an acknowledgement on the record (RLE-053,
 * PD-104), through the public services on the in-memory stores. Users at bank-a: lan authors, bob approves; bank-c
 * masks undeclared inputs by default. The approval sends the exact { code, input } pairs it was shown.
 */
class AcknowledgeWarningsTest {

    static final Instant JAN_15 = Instant.parse("2027-01-15T02:00:00Z");
    static final Set<String> AUTHOR = Set.of("rules:read", "rules:author");
    static final Set<String> APPROVER = Set.of("rules:read", "rules:approve");
    static final String UNMASKED = "UNMASKED_PERSONAL_DATA";
    static final AcknowledgedWarning NATIONAL_ID = new AcknowledgedWarning(UNMASKED, "nationalId");
    static final AcknowledgedWarning CONTACT_NOTE = new AcknowledgedWarning(UNMASKED, "contactNote");

    /** RLE-053: nationalId and contactNote declare no mask, age declares mask: false. */
    static final String KYC_ROUTING = """
            schemaVersion: 1
            ruleSet: kyc-routing
            hitPolicy: first
            inputs:
              - { name: nationalId, type: string }
              - { name: contactNote, type: string }
              - { name: age, type: integer, mask: false }
            outputs:
              - { name: route, type: string }
            rules:
              - { id: R1, when: { age: "< 18" }, then: { route: "'MANUAL'" } }
              - { id: R2, when: {}, then: { route: "'AUTO'" } }
            """;

    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "lan");
    final UnmaskedPersonalDataTest.Policy policy = new UnmaskedPersonalDataTest.Policy();

    VersionService versions(EngineSettings settings) {
        RuleEngines engines = engines(settings);
        engines.cursorKeys().load();
        return engines.versionService();
    }

    RuleEngines engines(EngineSettings settings) {
        return RuleEngines.create(new EngineContext(caller, caller, Clock.fixed(JAN_15, ZoneOffset.UTC), stores,
                stores, settings, policy), stores, stores, stores);
    }

    VersionService versions() {
        return versions(EngineSettings.defaults().withRequireContentHash(false));
    }

    /** A rule set imported and requested by lan in a tenant; returns the pending version. */
    RuleSetVersion pending(VersionService versions, String tenant, String yaml, String ruleSet) {
        caller.become(tenant, "lan", AUTHOR);
        versions.importRuleSet(ImportRequest.yaml(yaml.getBytes(StandardCharsets.UTF_8)));
        RuleSetVersion requested = versions.requestPublish(ruleSet, 1, WriteOptions.none());
        caller.become(tenant, "bob", APPROVER);
        return requested;
    }

    RuleSetVersion pending(VersionService versions, String tenant) {
        return pending(versions, tenant, KYC_ROUTING, "kyc-routing");
    }

    static WriteOptions approval(RuleSetVersion pending, String key, AcknowledgedWarning... acknowledged) {
        return new WriteOptions(key, pending.lockVersion(), "ok").acknowledging(List.of(acknowledged));
    }

    VersionHistoryEntry approvalRow(VersionService versions) {
        return versions.history("kyc-routing", 1).stream()
                .filter(e -> e.action() == LifecycleAction.APPROVED).findFirst().orElseThrow();
    }

    void assertNothingWritten(VersionService versions, RuleSetVersion pending, int history, int events) {
        RuleSetVersion stored = versions.find("kyc-routing", 1, false).orElseThrow();
        assertThat(stored.status()).isEqualTo(VersionStatus.PENDING_APPROVAL);
        assertThat(stored.lockVersion()).isEqualTo(pending.lockVersion());
        assertThat(stores.history).hasSize(history);
        assertThat(stores.outbox).hasSize(events);
    }

    @Test
    void RLE_053_approval_with_an_unmasked_personal_data_warning_needs_an_acknowledgement() {
        // Given kyc-routing v1 pending at bank-a; with the built-in names only nationalId warns
        VersionService versions = versions();
        RuleSetVersion pending = pending(versions, "bank-a");
        int events = stores.outbox.size();
        int history = stores.history.size();

        // Step 1: bob approves without acknowledgedWarnings
        assertThatThrownBy(() -> versions.approve("kyc-routing", 1, approval(pending, "a-1")))
                .isInstanceOfSatisfying(WarningsNotAcknowledgedException.class, e -> {
                    assertThat(e.code()).isEqualTo("WARNINGS_NOT_ACKNOWLEDGED");
                    assertThat(e.warnings()).containsExactly(new Unacknowledged(UNMASKED, "nationalId", "national id"));
                    assertThat(e.setting()).containsExactly(UNMASKED);
                });
        assertNothingWritten(versions, pending, history, events);

        // Step 2: the host adds "contact note" to bank-a's names before bob sends the nationalId pair only
        policy.names.put("bank-a", PersonalDataNames.builtIn().plus(List.of("contact note")));
        assertThatThrownBy(() -> versions.approve("kyc-routing", 1, approval(pending, "a-2", NATIONAL_ID)))
                .isInstanceOfSatisfying(WarningsNotAcknowledgedException.class, e ->
                        // One acknowledged pair never covers an input the approver was not shown
                        assertThat(e.warnings()).containsExactly(
                                new Unacknowledged(UNMASKED, "nationalId", "national id"),
                                new Unacknowledged(UNMASKED, "contactNote", "contact note")));
        assertNothingWritten(versions, pending, history, events);

        // He then sends both pairs, in another order and with a duplicate
        RuleSetVersion published = versions.approve("kyc-routing", 1,
                approval(pending, "a-3", CONTACT_NOTE, NATIONAL_ID, CONTACT_NOTE));

        // Then v1 is published; the history row and the event carry both pairs and an empty masked-by-default list
        assertThat(published.status()).isEqualTo(VersionStatus.PUBLISHED);
        VersionHistoryEntry row = approvalRow(versions);
        assertThat(row.acknowledgedWarnings()).containsExactly(NATIONAL_ID, CONTACT_NOTE);
        assertThat(row.maskedByDefaultInputs()).isEmpty();
        assertThat(stores.outbox.getLast().payload()).contains("\"eventType\":\"rules.version.published.v1\"",
                "\"acknowledgedWarnings\":[{\"code\":\"UNMASKED_PERSONAL_DATA\",\"input\":\"nationalId\"},"
                        + "{\"code\":\"UNMASKED_PERSONAL_DATA\",\"input\":\"contactNote\"}]",
                "\"maskedByDefaultInputs\":[]");

        // Step 3: the replay of the same request returns the same answer and writes nothing
        int after = stores.outbox.size();
        assertThat(versions.approve("kyc-routing", 1, approval(pending, "a-3", CONTACT_NOTE, NATIONAL_ID, CONTACT_NOTE)))
                .isEqualTo(published);
        assertThat(stores.outbox).hasSize(after);
    }

    @Test
    void RLE_053_inputs_masked_by_the_tenant_default_need_no_acknowledgement_and_are_recorded() {
        // Step 4: bank-c masks undeclared inputs by default, and the same version is pending there
        policy.maskByDefault.add("bank-c");
        VersionService versions = versions();
        RuleSetVersion pending = pending(versions, "bank-c");
        assertThat(pending.warnings()).extracting(w -> w.input()).containsExactly("nationalId");

        // When bob approves without acknowledgedWarnings
        RuleSetVersion published = versions.approve("kyc-routing", 1, approval(pending, null));

        // Then it is published; empty acknowledgement, and both undeclared inputs named as masked by default, sorted
        assertThat(published.status()).isEqualTo(VersionStatus.PUBLISHED);
        VersionHistoryEntry row = approvalRow(versions);
        assertThat(row.acknowledgedWarnings()).isEmpty();
        assertThat(row.maskedByDefaultInputs()).containsExactly("contactNote", "nationalId");
        assertThat(stores.outbox.getLast().payload())
                .contains("\"acknowledgedWarnings\":[]", "\"maskedByDefaultInputs\":[\"contactNote\",\"nationalId\"]");
    }

    @Test
    void RLE_053_an_extra_pair_is_refused_with_the_current_set_and_an_unknown_code_is_400() {
        // Given kyc-routing pending at bank-a
        VersionService versions = versions();
        RuleSetVersion pending = pending(versions, "bank-a");
        int events = stores.outbox.size();
        int history = stores.history.size();

        // An extra pair for age, which declares mask: false and needs nothing: 409 with the current set
        assertThatThrownBy(() -> versions.approve("kyc-routing", 1,
                approval(pending, null, NATIONAL_ID, new AcknowledgedWarning(UNMASKED, "age"))))
                .isInstanceOfSatisfying(WarningsNotAcknowledgedException.class, e -> assertThat(e.warnings())
                        .containsExactly(new Unacknowledged(UNMASKED, "nationalId", "national id")));
        // An unknown code: 400 INPUT_INVALID
        assertThatThrownBy(() -> versions.approve("kyc-routing", 1,
                approval(pending, null, new AcknowledgedWarning("FOO", "nationalId"))))
                .isInstanceOf(InputInvalidException.class).hasMessageContaining("FOO");
        // An empty or an over-long input name, and more than 100 pairs: 400 INPUT_INVALID
        assertThatThrownBy(() -> versions.approve("kyc-routing", 1,
                approval(pending, null, new AcknowledgedWarning(UNMASKED, ""))))
                .isInstanceOf(InputInvalidException.class);
        assertThatThrownBy(() -> versions.approve("kyc-routing", 1,
                approval(pending, null, new AcknowledgedWarning(UNMASKED, "x".repeat(101)))))
                .isInstanceOf(InputInvalidException.class);
        List<AcknowledgedWarning> many = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            many.add(new AcknowledgedWarning(UNMASKED, "input" + i));
        }
        assertThatThrownBy(() -> versions.approve("kyc-routing", 1,
                new WriteOptions(null, pending.lockVersion(), null).acknowledging(many)))
                .isInstanceOf(InputInvalidException.class).hasMessageContaining("at most 100");
        // Nothing is written by any of them
        assertNothingWritten(versions, pending, history, events);
    }

    @Test
    void RLE_053_a_name_removed_from_the_list_makes_a_shown_pair_extra_and_nothing_is_needed_any_more() {
        // Given kyc-routing pending at bank-a, shown to bob with the nationalId warning
        VersionService versions = versions();
        RuleSetVersion pending = pending(versions, "bank-a");

        // When bank-a's list stops naming national ids before bob approves with the pair he saw
        policy.names.put("bank-a", PersonalDataNames.of(List.of("passport")));

        // Then 409 with an empty current set: he reloads and approves without a pair
        assertThatThrownBy(() -> versions.approve("kyc-routing", 1, approval(pending, null, NATIONAL_ID)))
                .isInstanceOfSatisfying(WarningsNotAcknowledgedException.class, e -> {
                    assertThat(e.warnings()).isEmpty();
                    assertThat(e.getMessage()).contains("none");
                });
        assertThat(versions.approve("kyc-routing", 1, approval(pending, null)).status())
                .isEqualTo(VersionStatus.PUBLISHED);
        assertThat(approvalRow(versions).acknowledgedWarnings()).isEmpty();
    }

    @Test
    void RLE_053_checks_run_in_order_lock_then_strict_mode_then_acknowledgement() {
        // Given kyc-routing pending at bank-a, then strict mode turned on
        VersionService versions = versions();
        RuleSetVersion pending = pending(versions, "bank-a");
        policy.strict.add("bank-a");

        // A stale lock is reported first, before strict mode or the acknowledgement
        assertThatThrownBy(() -> versions.approve("kyc-routing", 1,
                new WriteOptions(null, pending.lockVersion() - 1, null)))
                .isInstanceOf(VersionConflictException.class);
        // With the current lock, strict mode refuses (422), never 409, even when the pair is acknowledged
        assertThatThrownBy(() -> versions.approve("kyc-routing", 1, approval(pending, null, NATIONAL_ID)))
                .isInstanceOf(ValidationFailedException.class);
        assertThatThrownBy(() -> versions.approve("kyc-routing", 1, approval(pending, null)))
                .isInstanceOf(ValidationFailedException.class);
    }

    @Test
    void RLE_053_each_warning_says_whether_the_approval_must_acknowledge_it_and_the_approve_check_agrees() {
        // Given the same kyc-routing v1 pending at bank-a, which logs undeclared inputs in plain text, and at bank-c,
        // which masks them by default; both tenants list "contact note" as personal data
        policy.names.put("bank-a", PersonalDataNames.builtIn().plus(List.of("contact note")));
        policy.names.put("bank-c", PersonalDataNames.builtIn().plus(List.of("contact note")));
        policy.maskByDefault.add("bank-c");
        VersionService versions = versions();
        RuleSetVersion atBankA = pending(versions, "bank-a");
        RuleSetVersion atBankC = pending(versions, "bank-c");

        // When each approver reads the version
        caller.become("bank-a", "bob", APPROVER);
        RuleSetVersion readAtBankA = versions.find("kyc-routing", 1, false).orElseThrow();
        caller.become("bank-c", "bob", APPROVER);
        RuleSetVersion readAtBankC = versions.find("kyc-routing", 1, false).orElseThrow();

        // Then bank-a marks both warnings and bank-c, whose default masks them, marks neither
        assertThat(atBankA.warnings()).extracting(w -> w.input() + "=" + w.requiresAcknowledgement())
                .containsExactly("nationalId=true", "contactNote=true");
        assertThat(readAtBankA.warnings()).isEqualTo(atBankA.warnings());
        assertThat(readAtBankC.warnings()).extracting(w -> w.input() + "=" + w.requiresAcknowledgement())
                .containsExactly("nationalId=false", "contactNote=false");

        // And the approve check accepts exactly the marked pairs: none is refused at bank-a, none needed at bank-c
        caller.become("bank-a", "bob", APPROVER);
        assertThatThrownBy(() -> versions.approve("kyc-routing", 1, approval(atBankA, "m-1")))
                .isInstanceOfSatisfying(WarningsNotAcknowledgedException.class,
                        e -> assertThat(e.warnings()).extracting(Unacknowledged::input)
                                .containsExactly("nationalId", "contactNote"));
        assertThat(versions.approve("kyc-routing", 1, approval(atBankA, "m-2", marked(readAtBankA))).status())
                .isEqualTo(VersionStatus.PUBLISHED);
        caller.become("bank-c", "bob", APPROVER);
        assertThat(marked(readAtBankC)).isEmpty();
        assertThat(versions.approve("kyc-routing", 1, approval(atBankC, "m-3", marked(readAtBankC))).status())
                .isEqualTo(VersionStatus.PUBLISHED);
    }

    @Test
    void RLE_053_a_host_that_lists_no_code_marks_no_warning() {
        // Given a host whose lifecycle.acknowledge-warnings is empty
        VersionService versions = versions(EngineSettings.defaults().withRequireContentHash(false).withAcknowledgeWarnings(List.of()));

        // When
        RuleSetVersion pending = pending(versions, "bank-a");

        // Then the warning is still shown, and needs no acknowledgement
        assertThat(pending.warnings()).singleElement().satisfies(w -> {
            assertThat(w.input()).isEqualTo("nationalId");
            assertThat(w.requiresAcknowledgement()).isFalse();
        });
    }

    @Test
    void RLE_053_the_mark_is_written_with_the_response_and_a_response_recorded_before_it_reads_as_false() {
        // Given kyc-routing v1 pending at bank-a, its nationalId warning marked
        RuleSetVersion pending = pending(versions(), "bank-a");
        String json = io.github.dhluong90.bpm.rules.internal.json.EngineValues.write(
                io.github.dhluong90.bpm.rules.internal.json.EngineValues.version(pending));

        // When the JSON is read back, and read again without the field, as a replay recorded before it existed
        RuleSetVersion replayed = io.github.dhluong90.bpm.rules.internal.json.EngineValues.versionFromJson(json);
        RuleSetVersion older = io.github.dhluong90.bpm.rules.internal.json.EngineValues.versionFromJson(
                json.replace(",\"requiresAcknowledgement\":true", ""));

        // Then
        assertThat(json).contains("\"input\":\"nationalId\"", "\"requiresAcknowledgement\":true");
        assertThat(replayed.warnings()).isEqualTo(pending.warnings());
        assertThat(older.warnings()).singleElement().satisfies(w -> assertThat(w.requiresAcknowledgement()).isFalse());
    }

    /** The pairs a client sends: exactly the warnings the engine marked. */
    static AcknowledgedWarning[] marked(RuleSetVersion version) {
        return version.warnings().stream().filter(w -> w.requiresAcknowledgement())
                .map(w -> new AcknowledgedWarning(w.code(), w.input())).toArray(AcknowledgedWarning[]::new);
    }

    @Test
    void RLE_053_without_the_code_in_the_setting_no_acknowledgement_is_needed() {
        // Given a host whose lifecycle.acknowledge-warnings is empty
        VersionService versions = versions(EngineSettings.defaults().withRequireContentHash(false).withAcknowledgeWarnings(List.of()));
        RuleSetVersion pending = pending(versions, "bank-a");

        // When bob approves without acknowledgedWarnings: published, with an empty acknowledgement
        assertThat(versions.approve("kyc-routing", 1, approval(pending, null)).status())
                .isEqualTo(VersionStatus.PUBLISHED);
        assertThat(approvalRow(versions).acknowledgedWarnings()).isEmpty();
        // And a setting naming a code this release never raises stops start-up, naming the property
        assertThatThrownBy(() -> EngineSettings.defaults().withRequireContentHash(false).withAcknowledgeWarnings(List.of("SOMETHING_ELSE")))
                .hasMessageContaining("horus.bpm.rules.lifecycle.acknowledge-warnings")
                .hasMessageContaining("SOMETHING_ELSE");
        assertThat(EngineSettings.defaults().withRequireContentHash(false).acknowledgeWarnings()).containsExactly(UNMASKED);
    }

    @Test
    void RLE_053_turning_the_default_off_names_the_published_versions_it_affects_capped_at_twenty() {
        // Given bank-c masks by default, recorded at the last start-up, and 22 rule sets were approved there
        policy.maskByDefault.add("bank-c");
        VersionService versions = versions();
        for (int i = 1; i <= 22; i++) {
            String name = String.format("kyc-%02d", i);
            pending(versions, "bank-c", KYC_ROUTING.replace("ruleSet: kyc-routing", "ruleSet: " + name), name);
            versions.approve(name, 1, new WriteOptions(null, 1L, null));
        }
        stores.recordSettingSeen(MaskingStartupCheck.HOST_DEFAULT_ROW, MaskingStartupCheck.MASK_DEFAULT_SETTING,
                "false", JAN_15);
        stores.recordSettingSeen("bank-c", MaskingStartupCheck.MASK_DEFAULT_SETTING, "true", JAN_15);

        // When the host restarts with bank-c no longer masking by default
        policy.maskByDefault.remove("bank-c");
        List<String> lines = engines(EngineSettings.defaults().withRequireContentHash(false)).maskingCheck().recordMaskDefaults(Set.of(),
                JAN_15.plusSeconds(60));

        // Then the change line is followed by one WARN naming the first 20 versions and "and 2 more"
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).isEqualTo(
                "decision-log.mask-default for tenant bank-c changed from true to false since the last start-up");
        assertThat(lines.get(1)).startsWith("Tenant bank-c no longer masks undeclared inputs")
                .contains("kyc-01 v1 [contactNote, nationalId]", "kyc-20 v1 [contactNote, nationalId]")
                .doesNotContain("kyc-21").endsWith(" and 2 more");
    }
}
