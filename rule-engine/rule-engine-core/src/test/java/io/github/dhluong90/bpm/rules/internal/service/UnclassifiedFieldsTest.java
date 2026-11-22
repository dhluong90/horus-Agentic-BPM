package io.github.dhluong90.bpm.rules.internal.service;

import static io.github.dhluong90.bpm.rules.internal.service.UnmaskedPersonalDataTest.APPROVER;
import static io.github.dhluong90.bpm.rules.internal.service.UnmaskedPersonalDataTest.AUTHOR;
import static io.github.dhluong90.bpm.rules.internal.service.UnmaskedPersonalDataTest.JAN_15;
import static io.github.dhluong90.bpm.rules.internal.service.UnmaskedPersonalDataTest.yaml;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.rules.api.DraftValidation;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.api.VersionWarning;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * RLE-195, PD-146: inputs that declare no {@code mask} are listed as {@code UNCLASSIFIED_INPUT} reminders at import,
 * at the publication request, for the approver and on {@code :validate}; they never block, never need an
 * acknowledgement and never infer sensitivity from a name. Users at bank-a: lan authors, bob approves.
 */
class UnclassifiedFieldsTest {

    /** A card fee table: tier forgotten, channel declared mask: false, cardNumber masked. */
    static final String CARD_FEE = """
            schemaVersion: 1
            ruleSet: card-fee
            hitPolicy: first
            inputs:
              - { name: tier, type: string }
              - { name: channel, type: string, mask: false }
              - { name: cardNumber, type: string, mask: true }
            outputs:
              - { name: fee, type: integer }
            rules:
              - { id: F1, when: { tier: "== 'GOLD'" }, then: { fee: "0" } }
              - { id: F2, when: {}, then: { fee: "20000" } }
            """;

    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "lan");
    final UnmaskedPersonalDataTest.Policy policy = new UnmaskedPersonalDataTest.Policy();

    {
        policy.warnUnclassified = true;
    }

    RuleEngines engines() {
        RuleEngines engines = RuleEngines.create(new EngineContext(caller, caller, Clock.fixed(JAN_15, ZoneOffset.UTC),
                stores, stores, EngineSettings.defaults().withRequireContentHash(false).withDraftAuthoring(true),
                policy), stores, stores, stores);
        engines.cursorKeys().load();
        return engines;
    }

    @Test
    void RLE_195_an_unclassified_input_is_listed_at_import_request_and_review_and_approval_still_works() {
        // Given lan imports card-fee with tier undeclared; channel is mask: false and cardNumber mask: true
        caller.become("bank-a", "lan", AUTHOR);
        VersionService versions = engines().versionService();
        RuleSetVersion imported = versions.importRuleSet(yaml(CARD_FEE));

        // Then the import lists tier only, as a reminder that needs no acknowledgement; mask: false is classified
        assertThat(imported.warnings()).singleElement().satisfies(warning -> {
            assertThat(warning.code()).isEqualTo(VersionWarning.UNCLASSIFIED_INPUT);
            assertThat(warning.input()).isEqualTo("tier");
            assertThat(warning.matchedName()).isNull();
            assertThat(warning.requiresAcknowledgement()).isFalse();
            assertThat(warning.message()).contains("tier", "not classified", "does not block");
        });

        // When lan requests publication and bob reviews and approves without acknowledging anything
        RuleSetVersion requested = versions.requestPublish("card-fee", 1, WriteOptions.none());
        caller.become("bank-a", "bob", APPROVER);
        RuleSetVersion reviewed = versions.find("card-fee", 1, false).orElseThrow();
        RuleSetVersion approved = versions.approve("card-fee", 1, new WriteOptions(null, requested.lockVersion(), null));

        // Then the request and the review list the same reminder, and the approval succeeds
        assertThat(requested.warnings()).extracting(VersionWarning::input).containsExactly("tier");
        assertThat(reviewed.warnings()).isEqualTo(requested.warnings());
        assertThat(approved.status()).isEqualTo(VersionStatus.PUBLISHED);
    }

    @Test
    void RLE_195_validate_lists_unclassified_inputs_and_the_document_stays_valid() {
        // Given lan's draft of card-fee
        caller.become("bank-a", "lan", AUTHOR);
        VersionService versions = engines().versionService();
        versions.importRuleSet(yaml(CARD_FEE));

        // When she validates an edit that also leaves channel undeclared
        DraftValidation result = versions.validateDraft("card-fee", 1,
                yaml(CARD_FEE.replace("{ name: channel, type: string, mask: false }", "{ name: channel, type: string }")));

        // Then it is valid and lists tier and channel in input order
        assertThat(result.valid()).isTrue();
        assertThat(result.warnings()).extracting(VersionWarning::code, VersionWarning::input).containsExactly(
                org.assertj.core.groups.Tuple.tuple(VersionWarning.UNCLASSIFIED_INPUT, "tier"),
                org.assertj.core.groups.Tuple.tuple(VersionWarning.UNCLASSIFIED_INPUT, "channel"));
    }

    @Test
    void RLE_195_a_silenced_deployment_lists_nothing() {
        // Given the deployment turned the reminder off
        policy.warnUnclassified = false;
        caller.become("bank-a", "lan", AUTHOR);
        VersionService versions = engines().versionService();

        // When lan imports and validates card-fee
        RuleSetVersion imported = versions.importRuleSet(yaml(CARD_FEE));
        DraftValidation validated = versions.validateDraft("card-fee", 1, yaml(CARD_FEE));

        // Then nothing is listed
        assertThat(imported.warnings()).isEmpty();
        assertThat(validated.warnings()).isEmpty();
    }

    @Test
    void RLE_195_classification_is_never_inferred_from_a_name() {
        // Given inputs whose names look like personal data and inputs whose names do not, all declared
        String declared = CARD_FEE.replace("{ name: tier, type: string }", "{ name: tier, type: string, mask: false }")
                .replace("{ name: channel, type: string, mask: false }", "{ name: nationalId, type: string, mask: false }");
        caller.become("bank-a", "lan", AUTHOR);

        // When lan imports it
        RuleSetVersion imported = engines().versionService().importRuleSet(yaml(declared));

        // Then no input is listed: the declaration alone decides, whatever the name says
        assertThat(imported.warnings()).filteredOn(w -> w.code().equals(VersionWarning.UNCLASSIFIED_INPUT)).isEmpty();
    }

    @Test
    void RLE_195_the_start_up_summary_counts_unclassified_inputs_per_approved_version_without_values() {
        // Given card-fee v1 approved with tier undeclared, and a draft that is not approved
        caller.become("bank-a", "lan", AUTHOR);
        VersionService versions = engines().versionService();
        RuleSetVersion requested = versions.importRuleSet(yaml(CARD_FEE));
        requested = versions.requestPublish("card-fee", 1, WriteOptions.none());
        caller.become("bank-a", "bob", APPROVER);
        versions.approve("card-fee", 1, new WriteOptions(null, requested.lockVersion(), null));
        caller.become("bank-a", "lan", AUTHOR);
        versions.importVersion("card-fee", yaml(CARD_FEE.replace("{ name: channel, type: string, mask: false }",
                "{ name: channel, type: string }")));

        // When the engine starts
        String summary = engines().maskingCheck().unclassifiedSummary().orElseThrow();

        // Then one line counts the approved version only, names the setting, and carries no input name or value
        assertThat(summary).contains("1 inputs of 1 approved rule set versions", "bank-a/card-fee v1: 1",
                "horus.bpm.rules.classification.warn-unclassified=false").doesNotContain("tier", "GOLD", "v2");

        // And a silenced deployment logs nothing
        policy.warnUnclassified = false;
        assertThat(engines().maskingCheck().unclassifiedSummary()).isEmpty();
    }

    @Test
    void RLE_195_an_input_already_warned_as_personal_data_is_not_listed_twice() {
        // Given card-fee with nationalId undeclared, which also matches the personal-data names (RLE-049)
        caller.become("bank-a", "lan", AUTHOR);
        RuleSetVersion imported = engines().versionService().importRuleSet(yaml(CARD_FEE.replace(
                "{ name: tier, type: string }", "{ name: tier, type: string }\n  - { name: nationalId, type: string }")));

        // Then nationalId carries the personal-data warning only, and tier the reminder
        assertThat(imported.warnings()).extracting(VersionWarning::code, VersionWarning::input).containsExactly(
                org.assertj.core.groups.Tuple.tuple(VersionWarning.UNMASKED_PERSONAL_DATA, "nationalId"),
                org.assertj.core.groups.Tuple.tuple(VersionWarning.UNCLASSIFIED_INPUT, "tier"));
    }
}
