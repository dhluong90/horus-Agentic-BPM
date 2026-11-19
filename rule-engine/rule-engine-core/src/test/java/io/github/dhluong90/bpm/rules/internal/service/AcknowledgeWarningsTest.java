package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.AcknowledgedWarning;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.LifecycleAction;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.api.VersionHistoryEntry;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.api.WarningsNotAcknowledgedException;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Approving a version with an unmasked personal-data warning needs an acknowledgement on the record (RLE-053,
 * PD-104), through the public services on the in-memory stores. Users at bank-a: lan authors, bob approves; bank-c
 * masks undeclared inputs by default.
 */
class AcknowledgeWarningsTest {

    static final Instant JAN_15 = Instant.parse("2027-01-15T02:00:00Z");
    static final Set<String> AUTHOR = Set.of("rules:read", "rules:author");
    static final Set<String> APPROVER = Set.of("rules:read", "rules:approve");
    static final String UNMASKED = "UNMASKED_PERSONAL_DATA";

    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "lan");
    final UnmaskedPersonalDataTest.Policy policy = new UnmaskedPersonalDataTest.Policy();

    VersionService versions(EngineSettings settings) {
        RuleEngines engines = RuleEngines.create(new EngineContext(caller, caller, Clock.fixed(JAN_15, ZoneOffset.UTC),
                stores, stores, settings, policy), stores, stores, stores);
        engines.cursorKeys().load();
        return engines.versionService();
    }

    VersionService versions() {
        return versions(EngineSettings.defaults());
    }

    /** kyc-routing v1 imported and requested by lan in a tenant; returns the pending version. */
    RuleSetVersion pending(VersionService versions, String tenant, String yaml) {
        caller.become(tenant, "lan", AUTHOR);
        versions.importRuleSet(ImportRequest.yaml(yaml.getBytes(StandardCharsets.UTF_8)));
        RuleSetVersion requested = versions.requestPublish("kyc-routing", 1, WriteOptions.none());
        caller.become(tenant, "bob", APPROVER);
        return requested;
    }

    static WriteOptions approval(RuleSetVersion pending, String key, String... acknowledged) {
        return new WriteOptions(key, pending.lockVersion(), "ok", List.of(acknowledged));
    }

    VersionHistoryEntry approvalRow(VersionService versions) {
        return versions.history("kyc-routing", 1).stream()
                .filter(e -> e.action() == LifecycleAction.APPROVED).findFirst().orElseThrow();
    }

    @Test
    void RLE_053_approval_with_an_unmasked_personal_data_warning_needs_an_acknowledgement() {
        // Given kyc-routing v1 pending at bank-a, whose nationalId declares no mask
        VersionService versions = versions();
        RuleSetVersion pending = pending(versions, "bank-a", UnmaskedPersonalDataTest.KYC_ROUTING);
        int events = stores.outbox.size();
        int history = stores.history.size();

        // When bob approves without acknowledgedWarnings
        // Then 409 WARNINGS_NOT_ACKNOWLEDGED names the warning, nationalId, the matched name and the setting
        assertThatThrownBy(() -> versions.approve("kyc-routing", 1, approval(pending, "a-1")))
                .isInstanceOfSatisfying(WarningsNotAcknowledgedException.class, e -> {
                    assertThat(e.code()).isEqualTo("WARNINGS_NOT_ACKNOWLEDGED");
                    assertThat(e.warnings()).containsExactly(
                            new WarningsNotAcknowledgedException.Unacknowledged(UNMASKED, "nationalId", "national id"));
                    assertThat(e.setting()).containsExactly(UNMASKED);
                    assertThat(e.getMessage()).contains("UNMASKED_PERSONAL_DATA on nationalId");
                });
        // And v1 stays pending at its lock version, with no history row and no event
        RuleSetVersion stored = versions.find("kyc-routing", 1, false).orElseThrow();
        assertThat(stored.status()).isEqualTo(VersionStatus.PENDING_APPROVAL);
        assertThat(stored.lockVersion()).isEqualTo(pending.lockVersion());
        assertThat(stores.history).hasSize(history);
        assertThat(stores.outbox).hasSize(events);

        // When bob approves again with the code and a new key
        RuleSetVersion published = versions.approve("kyc-routing", 1, approval(pending, "a-2", UNMASKED));

        // Then v1 is published, and the history row and the event carry the acknowledgement
        assertThat(published.status()).isEqualTo(VersionStatus.PUBLISHED);
        VersionHistoryEntry row = approvalRow(versions);
        assertThat(row.acknowledgedWarnings()).containsExactly(new AcknowledgedWarning(UNMASKED, "nationalId"));
        assertThat(row.maskedByDefaultInputs()).isEmpty();
        assertThat(stores.outbox.getLast().payload()).contains("\"eventType\":\"rules.version.published.v1\"",
                "\"acknowledgedWarnings\":[{\"code\":\"UNMASKED_PERSONAL_DATA\",\"input\":\"nationalId\"}]");

        // When he replays the second request: the same answer, nothing written again
        int after = stores.outbox.size();
        assertThat(versions.approve("kyc-routing", 1, approval(pending, "a-2", UNMASKED))).isEqualTo(published);
        assertThat(stores.outbox).hasSize(after);
    }

    @Test
    void RLE_053_an_input_masked_by_the_tenant_default_needs_no_acknowledgement_and_is_recorded_as_such() {
        // Given bank-c masks undeclared inputs by default, and the same version is pending there
        policy.maskByDefault.add("bank-c");
        VersionService versions = versions();
        RuleSetVersion pending = pending(versions, "bank-c", UnmaskedPersonalDataTest.KYC_ROUTING);
        // The warning is still shown, because the declaration is missing
        assertThat(pending.warnings()).extracting(w -> w.input()).containsExactly("nationalId");

        // When bob approves without acknowledgedWarnings
        RuleSetVersion published = versions.approve("kyc-routing", 1, approval(pending, null));

        // Then it is published; the record has an empty acknowledgement and names nationalId as masked by default
        assertThat(published.status()).isEqualTo(VersionStatus.PUBLISHED);
        VersionHistoryEntry row = approvalRow(versions);
        assertThat(row.acknowledgedWarnings()).isEmpty();
        assertThat(row.maskedByDefaultInputs()).containsExactly("nationalId");
        assertThat(stores.outbox.getLast().payload()).contains("\"acknowledgedWarnings\":[]");
    }

    @Test
    void RLE_053_a_version_without_warnings_is_approved_as_before_and_a_code_not_present_is_not_recorded() {
        // Given kyc-routing with every input declared, pending at bank-a
        VersionService versions = versions();
        RuleSetVersion pending = pending(versions, "bank-a", UnmaskedPersonalDataTest.KYC_ROUTING_DECLARED);

        // When bob approves it, acknowledging a code that no warning carries
        RuleSetVersion published = versions.approve("kyc-routing", 1, approval(pending, null, UNMASKED));

        // Then it is published and the acknowledgement on the record is empty
        assertThat(published.status()).isEqualTo(VersionStatus.PUBLISHED);
        assertThat(approvalRow(versions).acknowledgedWarnings()).isEmpty();
        assertThat(stores.outbox.getLast().payload()).contains("\"acknowledgedWarnings\":[]");
    }

    @Test
    void RLE_053_without_the_code_in_the_setting_no_acknowledgement_is_needed() {
        // Given a host whose lifecycle.acknowledge-warnings is empty
        VersionService versions = versions(EngineSettings.defaults().withAcknowledgeWarnings(List.of()));
        RuleSetVersion pending = pending(versions, "bank-a", UnmaskedPersonalDataTest.KYC_ROUTING);

        // When bob approves without acknowledgedWarnings
        RuleSetVersion published = versions.approve("kyc-routing", 1, approval(pending, null));

        // Then it is published, with an empty acknowledgement
        assertThat(published.status()).isEqualTo(VersionStatus.PUBLISHED);
        assertThat(approvalRow(versions).acknowledgedWarnings()).isEmpty();
    }

    @Test
    void RLE_053_under_strict_mode_the_version_is_refused_before_any_acknowledgement_is_asked_for() {
        // Given kyc-routing pending at bank-a, then strict mode turned on
        VersionService versions = versions();
        RuleSetVersion pending = pending(versions, "bank-a", UnmaskedPersonalDataTest.KYC_ROUTING);
        policy.strict.add("bank-a");

        // When bob approves, even acknowledging the warning
        // Then 422 VALIDATION_FAILED, never 409: an acknowledgement cannot let an undeclared input through
        assertThatThrownBy(() -> versions.approve("kyc-routing", 1, approval(pending, null, UNMASKED)))
                .isInstanceOf(ValidationFailedException.class);
        assertThatThrownBy(() -> versions.approve("kyc-routing", 1, approval(pending, null)))
                .isInstanceOf(ValidationFailedException.class);
    }

    @Test
    void RLE_053_malformed_acknowledged_codes_and_an_unknown_setting_code_are_refused() {
        // Given a pending version
        VersionService versions = versions();
        RuleSetVersion pending = pending(versions, "bank-a", UnmaskedPersonalDataTest.KYC_ROUTING);

        // Then a malformed code is 400 INPUT_INVALID, before anything is checked or written
        assertThatThrownBy(() -> versions.approve("kyc-routing", 1, approval(pending, null, "unmasked personal data")))
                .isInstanceOf(InputInvalidException.class).hasMessageContaining("acknowledgedWarnings");
        // And a setting naming a code this release never raises stops start-up, naming the property
        assertThatThrownBy(() -> EngineSettings.defaults().withAcknowledgeWarnings(List.of("SOMETHING_ELSE")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("horus.bpm.rules.lifecycle.acknowledge-warnings")
                .hasMessageContaining("SOMETHING_ELSE");
        assertThat(EngineSettings.defaults().acknowledgeWarnings()).containsExactly(UNMASKED);
    }
}
