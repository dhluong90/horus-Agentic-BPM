package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionRequest;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.IdempotencyKeyReuseException;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.InvalidStateException;
import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.rules.api.RuleSetNotFoundException;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.SelfApprovalForbiddenException;
import io.github.dhluong90.bpm.rules.api.SourceFormat;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Acceptance tests of issue #1 at the service level: boundaries of the card-fee table, failure paths
 * that must leave nothing behind, idempotent replay that returns an equal result, and tenant scope.
 */
class RuleLifecycleAcceptanceTest {

    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "alice");
    final RuleEngines engines = RuleEngines.create(
            new EngineContext(caller, caller, Clock.fixed(Instant.parse("2026-12-15T02:00:00Z"), ZoneOffset.UTC),
                    stores, stores, EngineSettings.defaults()),
            stores, stores, stores);

    void publish(byte[] yaml, String name) {
        caller.become("bank-a", "alice");
        engines.versionService().importRuleSet(ImportRequest.yaml(yaml));
        engines.versionService().requestPublish(name, 1, WriteOptions.none());
        caller.become("bank-a", "bob");
        engines.versionService().approve(name, 1, new WriteOptions(null, 1L, null));
        caller.become("bank-a", "teller-01");
    }

    static byte[] yaml(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- RLE-001 boundaries

    @ParameterizedTest(name = "tier={0}, txCount={1} -> {2} {3} VND")
    @CsvSource(nullValues = "NULL", value = {
        "GOLD,   0,    R1, 0",
        "GOLD,   NULL, R1, 0",
        "gold,   5,    R2, 10000",
        "' GOLD', 2,   R3, 50000",
        "SILVER, 2,    R3, 50000",
        "SILVER, 3,    R2, 10000",
        "SILVER, -1,   R3, 50000",
        "NULL,   3,    R2, 10000",
        "NULL,   NULL, R3, 50000",
        "VÀNG,   9223372036854775807, R2, 10000"
    })
    void RLE_001_first_hit_policy_boundaries_of_the_card_fee_table(String tier, Long txCount, String rule, long fee) {
        // Given
        publish(CardFee.yaml(), "card-fee");
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("tier", tier);
        inputs.put("txCount", txCount);

        // When
        DecisionResult result = engines.ruleEngine().evaluate("card-fee", DecisionInput.of(inputs));

        // Then
        assertThat(result.isError()).isFalse();
        assertThat(result.matchedRuleIds()).containsExactly(rule);
        assertThat(result.outputs()).containsExactly(Map.entry("fee", Money.ofMinor("VND", fee)));
        assertThat(stores.decisions).singleElement().satisfies(row ->
                assertThat(row.matchedRuleIdsJson()).isEqualTo("[\"" + rule + "\"]"));
    }

    @Test
    void RLE_001_integer_inputs_accept_every_exact_integer_type_and_refuse_fractions_and_floating_point() {
        publish(CardFee.yaml(), "card-fee");

        for (Object three : List.of(3, 3L, (short) 3, BigInteger.valueOf(3), new BigDecimal("3"), new BigDecimal("3.000"))) {
            assertThat(engines.ruleEngine().evaluate("card-fee", DecisionInput.of(Map.of("tier", "S", "txCount", three)))
                    .matchedRuleIds()).as(three.getClass().getSimpleName()).containsExactly("R2");
        }
        for (Object bad : List.of(new BigDecimal("2.5"), 3.0d, 3.0f, "3", true)) {
            assertThatThrownBy(() -> engines.ruleEngine().evaluate("card-fee",
                    DecisionInput.of(Map.of("tier", "S", "txCount", bad))))
                    .as(bad.getClass().getSimpleName()).isInstanceOf(InputInvalidException.class);
        }
        assertThat(stores.decisions).hasSize(6);
    }

    // ---------------------------------------------------------------- RLE-020 failure paths

    @Test
    void RLE_020_a_refused_import_leaves_no_rule_set_version_or_event() {
        byte[] badYaml = yaml("schemaVersion: 1\nruleSet: card-fee\n  hitPolicy: first\n");
        byte[] wrongType = new String(CardFee.yaml(), StandardCharsets.UTF_8).replace("type: integer", "type: int")
                .getBytes(StandardCharsets.UTF_8);
        byte[] emptyTable = yaml("schemaVersion: 1\nruleSet: card-fee\nhitPolicy: first\ninputs: []\n"
                + "outputs:\n  - { name: fee, type: money }\nrules: []\n");
        byte[] badCell = new String(CardFee.yaml(), StandardCharsets.UTF_8).replace(">= 3", ">= ")
                .getBytes(StandardCharsets.UTF_8);
        byte[] notUtf8 = {(byte) 0xC3, (byte) 0x28};

        for (byte[] document : List.of(badYaml, wrongType, emptyTable, badCell, notUtf8)) {
            assertThatThrownBy(() -> engines.versionService().importRuleSet(ImportRequest.yaml(document)))
                    .isInstanceOf(ValidationFailedException.class);
        }
        assertThatThrownBy(() -> engines.versionService().importRuleSet(
                new ImportRequest(SourceFormat.JSON, CardFee.CANONICAL.getBytes(StandardCharsets.UTF_8), null)))
                .isInstanceOf(InputInvalidException.class);
        assertThat(stores.ruleSets).isEmpty();
        assertThat(stores.versions).isEmpty();
        assertThat(stores.outbox).isEmpty();
        assertThat(stores.idempotency).isEmpty();
    }

    @Test
    void RLE_020_a_refused_import_with_a_key_records_nothing_so_the_corrected_file_can_use_the_key() {
        byte[] bad = new String(CardFee.yaml(), StandardCharsets.UTF_8).replace(">= 3", ">= ")
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> engines.versionService().importRuleSet(ImportRequest.yaml(bad).withIdempotencyKey("k-7")))
                .isInstanceOf(ValidationFailedException.class);
        RuleSetVersion fixed = engines.versionService().importRuleSet(ImportRequest.yaml(CardFee.yaml()).withIdempotencyKey("k-7"));

        assertThat(fixed.version()).isEqualTo(1);
        assertThat(fixed.status()).isEqualTo(VersionStatus.DRAFT);
    }

    // ---------------------------------------------------------------- RLE-009 maker-checker

    @Test
    void RLE_009_a_refused_self_approval_changes_nothing_and_writes_no_event() {
        // Given
        engines.versionService().importRuleSet(ImportRequest.yaml(CardFee.yaml()));
        RuleSetVersion pending = engines.versionService().requestPublish("card-fee", 1, WriteOptions.none());
        int events = stores.outbox.size();

        // When alice approves, with and without the right lock version
        assertThatThrownBy(() -> engines.versionService().approve("card-fee", 1, new WriteOptions(null, 1L, null)))
                .isInstanceOf(SelfApprovalForbiddenException.class);
        assertThatThrownBy(() -> engines.versionService().approve("card-fee", 1,
                new WriteOptions(null, pending.lockVersion(), "looks fine")))
                .isInstanceOf(SelfApprovalForbiddenException.class);

        // Then
        RuleSetVersion after = engines.versionService().find("card-fee", 1, false).orElseThrow();
        assertThat(after.status()).isEqualTo(VersionStatus.PENDING_APPROVAL);
        assertThat(after.lockVersion()).isEqualTo(pending.lockVersion());
        assertThat(after.approvedBy()).isNull();
        assertThat(stores.outbox).hasSize(events);
        assertThatThrownBy(() -> engines.ruleEngine().evaluate("card-fee", DecisionInput.of(Map.of())))
                .isInstanceOf(io.github.dhluong90.bpm.rules.api.NoEffectiveVersionException.class);
    }

    @Test
    void RLE_009_the_checker_is_neither_the_requester_nor_the_importer() {
        // Given bob imports, alice requests publication
        caller.become("bank-a", "bob");
        engines.versionService().importRuleSet(ImportRequest.yaml(CardFee.yaml()));
        caller.become("bank-a", "alice");
        engines.versionService().requestPublish("card-fee", 1, WriteOptions.none());

        // When bob, the author, approves (changed by the security review of #1, finding 4: the author is a maker too)
        caller.become("bank-a", "bob");

        // Then he is refused, and a third person publishes
        assertThatThrownBy(() -> engines.versionService().approve("card-fee", 1, new WriteOptions(null, 1L, null)))
                .isInstanceOf(io.github.dhluong90.bpm.rules.api.SelfApprovalForbiddenException.class);
        caller.become("bank-a", "carol");
        RuleSetVersion published = engines.versionService().approve("card-fee", 1, new WriteOptions(null, 1L, null));
        assertThat(published.status()).isEqualTo(VersionStatus.PUBLISHED);
        assertThat(published.requestedBy()).isEqualTo("alice");
        assertThat(published.createdBy()).isEqualTo("bob");
        assertThat(published.approvedBy()).isEqualTo("carol");
    }

    @Test
    void RLE_009_approval_is_refused_before_a_request_and_after_publication() {
        engines.versionService().importRuleSet(ImportRequest.yaml(CardFee.yaml()));
        caller.become("bank-a", "bob");

        assertThatThrownBy(() -> engines.versionService().approve("card-fee", 1, new WriteOptions(null, 1L, null)))
                .isInstanceOf(InvalidStateException.class);

        caller.become("bank-a", "alice");
        engines.versionService().requestPublish("card-fee", 1, WriteOptions.none());
        caller.become("bank-a", "bob");
        engines.versionService().approve("card-fee", 1, new WriteOptions(null, 1L, null));
        caller.become("bank-a", "carol");

        assertThatThrownBy(() -> engines.versionService().approve("card-fee", 1, new WriteOptions(null, 1L, null)))
                .isInstanceOf(InvalidStateException.class);
        assertThat(engines.versionService().find("card-fee", 1, false).orElseThrow().approvedBy()).isEqualTo("bob");
    }

    // ---------------------------------------------------------------- RLE-011 tenant scope

    @Test
    void RLE_011_the_same_rule_set_name_lives_independently_in_two_tenants() {
        // Given bank-a publishes card-fee and tenant-b imports its own card-fee with other fees
        publish(CardFee.yaml(), "card-fee");
        caller.become("tenant-b", "carol");
        byte[] other = new String(CardFee.yaml(), StandardCharsets.UTF_8).replace("\"0 VND\"", "\"7 VND\"")
                .getBytes(StandardCharsets.UTF_8);
        engines.versionService().importRuleSet(ImportRequest.yaml(other));

        // Then tenant-b sees only its draft and cannot evaluate bank-a's published version
        assertThat(engines.versionService().find("card-fee", 1, true).orElseThrow().status()).isEqualTo(VersionStatus.DRAFT);
        assertThat(engines.versionService().find("card-fee", 1, true).orElseThrow().sourceDocument()).contains("7 VND");
        assertThatThrownBy(() -> engines.ruleEngine().evaluate("card-fee", DecisionInput.of(Map.of("tier", "GOLD"))))
                .isInstanceOf(io.github.dhluong90.bpm.rules.api.NoEffectiveVersionException.class);

        // And bank-a still gets its own 0 VND
        caller.become("bank-a", "teller-01");
        assertThat(engines.ruleEngine().evaluate("card-fee", DecisionInput.of(Map.of("tier", "GOLD"))).outputs())
                .containsEntry("fee", Money.ofMinor("VND", 0));
        assertThat(stores.decisions).extracting(r -> r.tenantId()).containsOnly("bank-a");
    }

    @Test
    void RLE_011_a_tenant_cannot_approve_or_request_another_tenants_version() {
        engines.versionService().importRuleSet(ImportRequest.yaml(CardFee.yaml()));
        caller.become("tenant-b", "carol");

        assertThatThrownBy(() -> engines.versionService().requestPublish("card-fee", 1, WriteOptions.none()))
                .isInstanceOf(RuleSetNotFoundException.class);
        caller.become("bank-a", "alice");
        engines.versionService().requestPublish("card-fee", 1, WriteOptions.none());
        caller.become("tenant-b", "dave");
        assertThatThrownBy(() -> engines.versionService().approve("card-fee", 1, new WriteOptions(null, 1L, null)))
                .isInstanceOf(RuleSetNotFoundException.class);

        caller.become("bank-a", "alice");
        assertThat(engines.versionService().find("card-fee", 1, false).orElseThrow().status())
                .isEqualTo(VersionStatus.PENDING_APPROVAL);
    }

    // ---------------------------------------------------------------- RLE-014 idempotency

    @Test
    void RLE_014_idempotency_keys_are_scoped_to_the_tenant() {
        // Given bank-a used k-101
        engines.versionService().importRuleSet(ImportRequest.yaml(CardFee.yaml()).withIdempotencyKey("k-101"));

        // When tenant-b sends the same key with the same body
        caller.become("tenant-b", "carol");
        RuleSetVersion b = engines.versionService().importRuleSet(ImportRequest.yaml(CardFee.yaml()).withIdempotencyKey("k-101"));

        // Then tenant-b gets its own version, not bank-a's replay
        assertThat(stores.versions).extracting(v -> v.tenantId()).containsExactlyInAnyOrder("bank-a", "tenant-b");
        assertThat(b.version()).isEqualTo(1);
        assertThat(b.status()).isEqualTo(VersionStatus.DRAFT);
        assertThat(stores.ruleSets).hasSize(2);
    }

    @Test
    void RLE_014_import_version_replays_identical_and_rejects_a_mismatch() {
        engines.versionService().importRuleSet(ImportRequest.yaml(CardFee.yaml()));
        byte[] b2 = new String(CardFee.yaml(), StandardCharsets.UTF_8).replace("10000 VND", "12000 VND")
                .getBytes(StandardCharsets.UTF_8);

        RuleSetVersion first = engines.versionService().importVersion("card-fee", ImportRequest.yaml(CardFee.yaml()).withIdempotencyKey("k-2"));
        RuleSetVersion replay = engines.versionService().importVersion("card-fee", ImportRequest.yaml(CardFee.yaml()).withIdempotencyKey("k-2"));

        assertThat(replay).isEqualTo(first);
        assertThat(first.version()).isEqualTo(2);
        assertThatThrownBy(() -> engines.versionService().importVersion("card-fee", ImportRequest.yaml(b2).withIdempotencyKey("k-2")))
                .isInstanceOf(IdempotencyKeyReuseException.class);
        assertThat(stores.versions).hasSize(2);
    }

    /**
     * A replayed evaluation must give the caller the same result as the first call. The first call
     * returns integer and decimal outputs as {@code BigDecimal}; the replay must not turn them into
     * {@code Long}.
     */
    @Test
    void RLE_014_an_evaluation_replay_returns_a_result_equal_to_the_first() {
        // Given a rule set with integer, decimal, string, boolean and money outputs
        publish(yaml("""
                schemaVersion: 1
                ruleSet: limits
                hitPolicy: first
                inputs:
                  - { name: tier, type: string }
                outputs:
                  - { name: maxTx, type: integer }
                  - { name: rate, type: decimal }
                  - { name: band, type: string }
                  - { name: vip, type: boolean }
                  - { name: cap, type: money }
                rules:
                  - id: R1
                    when: {}
                    then: { maxTx: 20, rate: 0.0525, band: "'A'", vip: true, cap: "5000000 VND" }
                """), "limits");
        DecisionRequest request = DecisionRequest.of("limits", DecisionInput.of(Map.of("tier", "GOLD")))
                .withIdempotencyKey("ev-9");

        // When
        DecisionResult first = engines.ruleEngine().evaluate(request);
        DecisionResult replay = engines.ruleEngine().evaluate(request);

        // Then
        assertThat(stores.decisions).hasSize(1);
        assertThat(replay.outputs()).isEqualTo(first.outputs());
        assertThat(replay).isEqualTo(first);
    }
}
