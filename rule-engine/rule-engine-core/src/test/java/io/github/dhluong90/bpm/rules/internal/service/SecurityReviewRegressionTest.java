package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionRequest;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.IdempotencyKeyReuseException;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.MaskingKey;
import io.github.dhluong90.bpm.rules.api.Money;
import io.github.dhluong90.bpm.rules.spi.MaskingPolicy;
import io.github.dhluong90.bpm.rules.api.PermissionDeniedException;
import io.github.dhluong90.bpm.rules.api.SelfApprovalForbiddenException;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Regression tests for the security review of #1, one per finding, through the public services. */
class SecurityReviewRegressionTest {

    static final Set<String> EVALUATE_ONLY = Set.of("rules:evaluate");

    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "alice");
    /** PRICING masks nationalId, so the engine needs a key; without one it fails closed (RLE-012). */
    final RuleEngines engines = RuleEngines.create(
            new EngineContext(caller, caller, Clock.fixed(Instant.parse("2026-12-15T02:00:00Z"), ZoneOffset.UTC),
                    stores, stores, EngineSettings.defaults(), MaskingPolicy.withKey(MaskingKey.of(new byte[32]))),
            stores, stores, stores);

    /** A table that multiplies a money input by a decimal input, the shape the review probed. */
    static final String PRICING = """
            schemaVersion: 1
            ruleSet: pricing
            hitPolicy: first
            inputs:
              - { name: fee,  type: money }
              - { name: rate, type: decimal }
              - { name: nationalId, type: string, mask: true }
            outputs:
              - { name: charge, type: money }
            rules:
              - { id: P1, when: {}, then: { charge: "fee * rate" } }
            """;

    void publish(String yaml, String name) {
        caller.become("bank-a", "alice");
        engines.versionService().importRuleSet(ImportRequest.yaml(yaml.getBytes(StandardCharsets.UTF_8)));
        engines.versionService().requestPublish(name, 1, WriteOptions.none());
        caller.become("bank-a", "bob");
        engines.versionService().approve(name, 1, WriteOptions.none());
    }

    // Finding 1 (blocking): permissions are checked in the services, not only over REST.

    @Test
    void RLE_009_a_caller_without_rules_approve_cannot_approve_through_the_java_api() {
        engines.versionService().importRuleSet(ImportRequest.yaml(CardFee.yaml()));
        engines.versionService().requestPublish("card-fee", 1, WriteOptions.none());
        caller.become("bank-a", "teller", EVALUATE_ONLY);

        assertThatThrownBy(() -> engines.versionService().approve("card-fee", 1, WriteOptions.none()))
                .isInstanceOf(PermissionDeniedException.class)
                .extracting(e -> ((PermissionDeniedException) e).code()).isEqualTo("PERMISSION_DENIED");
        caller.become("bank-a", "bob");
        assertThat(engines.versionService().find("card-fee", 1, false).orElseThrow().status())
                .isEqualTo(VersionStatus.PENDING_APPROVAL);
    }

    @Test
    void RLE_009_every_service_operation_requires_its_permission() {
        caller.become("bank-a", "teller", EVALUATE_ONLY);
        assertThatThrownBy(() -> engines.versionService().importRuleSet(ImportRequest.yaml(CardFee.yaml())))
                .isInstanceOf(PermissionDeniedException.class).hasMessageContaining("rules:author");
        assertThatThrownBy(() -> engines.versionService().requestPublish("card-fee", 1, WriteOptions.none()))
                .isInstanceOf(PermissionDeniedException.class).hasMessageContaining("rules:author");
        assertThatThrownBy(() -> engines.versionService().find("card-fee", 1, false))
                .isInstanceOf(PermissionDeniedException.class).hasMessageContaining("rules:read");
        caller.become("bank-a", "auditor", Set.of("rules:read"));
        assertThatThrownBy(() -> engines.ruleEngine().evaluate("card-fee", DecisionInput.of(Map.of())))
                .isInstanceOf(PermissionDeniedException.class).hasMessageContaining("rules:evaluate");
        assertThat(stores.versions).isEmpty();
    }

    // Finding 2 (blocking): number inputs are bounded before hashing, evaluation and logging.

    @ParameterizedTest
    @ValueSource(strings = {"1e-30000000", "1e999999999", "1e-35", "1e35", "1.0000000000000000000000000000000001"})
    void TR_SEC_08_unbounded_decimal_inputs_are_input_invalid_quickly(String rate) {
        publish(PRICING, "pricing");
        caller.become("bank-a", "teller", EVALUATE_ONLY);
        DecisionInput input = DecisionInput.of(Map.of("fee", Money.ofMinor("VND", 5), "rate", new BigDecimal(rate)));

        assertTimeoutPreemptively(Duration.ofMillis(500), () ->
                assertThatThrownBy(() -> engines.ruleEngine().evaluate("pricing", input))
                        .isInstanceOf(InputInvalidException.class)
                        .hasMessageContaining("Input rate"));
        assertThat(stores.decisions).isEmpty();
    }

    @Test
    void TR_SEC_08_bounded_decimal_inputs_still_evaluate() {
        publish(PRICING, "pricing");
        caller.become("bank-a", "teller", EVALUATE_ONLY);

        DecisionResult result = engines.ruleEngine().evaluate("pricing",
                DecisionInput.of(Map.of("fee", Money.ofMinor("VND", 1_000_000), "rate", new BigDecimal("0.015"))));

        assertThat(result.outputs()).containsEntry("charge", Money.ofMinor("VND", 15_000));
    }

    @Test
    void TR_SEC_08_a_floating_point_input_is_input_invalid() {
        publish(PRICING, "pricing");
        caller.become("bank-a", "teller", EVALUATE_ONLY);

        assertThatThrownBy(() -> engines.ruleEngine().evaluate("pricing", DecisionInput.of(Map.of("rate", 0.1d))))
                .isInstanceOf(InputInvalidException.class).hasMessageContaining("floating-point");
    }

    // Finding 3 (blocking): only declared inputs are logged; masked ones are replaced.

    @Test
    void TR_SEC_05_undeclared_inputs_are_refused_by_default_and_nothing_is_logged() {
        publish(PRICING, "pricing");
        caller.become("bank-a", "teller", EVALUATE_ONLY);

        assertThatThrownBy(() -> engines.ruleEngine().evaluate("pricing", DecisionInput.of(customer())))
                .isInstanceOf(InputInvalidException.class)
                .hasMessageContaining("[accountNumber, phone]")
                .hasMessageNotContaining("0903123456");
        assertThat(stores.decisions).isEmpty();
    }

    @Test
    void TR_SEC_05_undeclared_inputs_never_reach_the_decision_log_when_they_are_allowed() {
        MaskingKey key = MaskingKey.of(new byte[32]);
        RuleEngines lenient = RuleEngines.create(new EngineContext(caller, caller,
                Clock.fixed(Instant.parse("2026-12-15T02:00:00Z"), ZoneOffset.UTC), stores, stores,
                EngineSettings.defaults().withRejectUndeclaredInputs(false), MaskingPolicy.withKey(key)),
                stores, stores, stores);
        publish(PRICING, "pricing");
        caller.become("bank-a", "teller", EVALUATE_ONLY);

        lenient.ruleEngine().evaluate("pricing", DecisionInput.of(customer()));

        String logged = stores.decisions.getFirst().maskedInputsJson();
        assertThat(logged).isEqualTo("{\"fee\":{\"currency\":\"VND\",\"amount\":1000},\"rate\":1,\"nationalId\":\""
                + key.fingerprint("079090001234") + "\"}");
        assertThat(logged).doesNotContain("079090001234", "0903123456", "1903 4567 8901", "phone", "accountNumber");
    }

    static Map<String, Object> customer() {
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("fee", Money.ofMinor("VND", 1000));
        customer.put("rate", BigDecimal.ONE);
        customer.put("nationalId", "079090001234");
        customer.put("phone", "0903123456");
        customer.put("accountNumber", "1903 4567 8901");
        return customer;
    }

    @Test
    void money_outputs_beyond_what_json_clients_read_exactly_fail_the_decision() {
        publish(PRICING, "pricing");
        caller.become("bank-a", "teller", EVALUATE_ONLY);

        DecisionResult result = engines.ruleEngine().evaluate("pricing", DecisionInput.of(Map.of(
                "fee", Money.ofMinor("VND", 9_000_000_000_000_000L), "rate", BigDecimal.TEN)));

        assertThat(((DecisionResult.Failed) result.outcome()).code()).isEqualTo("EVALUATION_ERROR");
        assertThat(result.outputs()).isEmpty();
    }

    // Finding 4: the author of a version cannot approve it either.

    @Test
    void RLE_009_the_author_cannot_approve_a_version_someone_else_requested() {
        engines.versionService().importRuleSet(ImportRequest.yaml(CardFee.yaml()));
        caller.become("bank-a", "bob");
        engines.versionService().requestPublish("card-fee", 1, WriteOptions.none());
        caller.become("bank-a", "alice");

        assertThatThrownBy(() -> engines.versionService().approve("card-fee", 1, WriteOptions.none()))
                .isInstanceOf(SelfApprovalForbiddenException.class);
        assertThat(engines.versionService().find("card-fee", 1, false).orElseThrow().createdBy()).isEqualTo("alice");
        caller.become("bank-a", "carol");
        assertThat(engines.versionService().approve("card-fee", 1, WriteOptions.none()).status())
                .isEqualTo(VersionStatus.PUBLISHED);
    }

    // Finding 5: an idempotency key replays only for the caller who used it.

    @Test
    void RLE_014_another_caller_with_the_same_key_gets_a_mismatch_not_the_stored_result() {
        engines.versionService().importRuleSet(ImportRequest.yaml(CardFee.yaml()));
        engines.versionService().requestPublish("card-fee", 1, WriteOptions.none());
        caller.become("bank-a", "bob");
        engines.versionService().approve("card-fee", 1, WriteOptions.idempotencyKey("k-approve"));

        caller.become("bank-a", "alice");
        assertThatThrownBy(() -> engines.versionService().approve("card-fee", 1, WriteOptions.idempotencyKey("k-approve")))
                .isInstanceOf(IdempotencyKeyReuseException.class);

        caller.become("bank-a", "teller-1", EVALUATE_ONLY);
        DecisionRequest request = DecisionRequest.of("card-fee", DecisionInput.of(Map.of("tier", "GOLD", "txCount", 5)))
                .withIdempotencyKey("k-eval");
        engines.ruleEngine().evaluate(request);
        caller.become("bank-a", "teller-2", EVALUATE_ONLY);
        assertThatThrownBy(() -> engines.ruleEngine().evaluate(request)).isInstanceOf(IdempotencyKeyReuseException.class);
    }

    // Finding 6, inputs: numbers that a double cannot tell apart still hash differently.

    @Test
    void RLE_014_inputs_that_differ_beyond_double_precision_are_a_mismatch() {
        publish(PRICING, "pricing");
        caller.become("bank-a", "teller", EVALUATE_ONLY);
        engines.ruleEngine().evaluate(DecisionRequest.of("pricing", DecisionInput.of(Map.of("fee", Money.ofMinor("VND", 10),
                "rate", new BigDecimal("0.1")))).withIdempotencyKey("k-1"));

        assertThatThrownBy(() -> engines.ruleEngine().evaluate(DecisionRequest.of("pricing", DecisionInput.of(Map.of(
                "fee", Money.ofMinor("VND", 10), "rate", new BigDecimal("0.10000000000000001")))).withIdempotencyKey("k-1")))
                .isInstanceOf(IdempotencyKeyReuseException.class);
        assertThat(engines.ruleEngine().evaluate(DecisionRequest.of("pricing", DecisionInput.of(Map.of(
                "fee", Money.ofMinor("VND", 10), "rate", new BigDecimal("0.10")))).withIdempotencyKey("k-1")).version())
                .isEqualTo(1);
    }

    // NIT 11: a correlation id is bounded.

    @Test
    void a_malformed_correlation_id_is_input_invalid() {
        publish(PRICING, "pricing");
        caller.become("bank-a", "teller", EVALUATE_ONLY);

        assertThatThrownBy(() -> engines.ruleEngine().evaluate(DecisionRequest.of("pricing", DecisionInput.of(Map.of()))
                .withCorrelationId("x".repeat(129)))).isInstanceOf(InputInvalidException.class);
        assertThatThrownBy(() -> engines.ruleEngine().evaluate(DecisionRequest.of("pricing", DecisionInput.of(Map.of()))
                .withCorrelationId("abc\n<script>"))).isInstanceOf(InputInvalidException.class);
    }

    @Test
    void import_refusals_store_nothing() {
        assertThatThrownBy(() -> engines.versionService().importRuleSet(ImportRequest.yaml(
                PRICING.replace("mask: true", "mask: yes").getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(ValidationFailedException.class);
        assertThat(stores.versions).isEmpty();
        assertThat(stores.outbox).isEmpty();
    }
}
