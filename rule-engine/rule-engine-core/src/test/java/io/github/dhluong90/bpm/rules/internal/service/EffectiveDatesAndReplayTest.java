package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.ClockNotAllowedException;
import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionNotFoundException;
import io.github.dhluong90.bpm.rules.api.DecisionRequest;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.HorusBpmException;
import io.github.dhluong90.bpm.rules.api.IdempotencyKeyReuseException;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.InvalidStateException;
import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.rules.api.NoEffectiveVersionException;
import io.github.dhluong90.bpm.rules.api.PermissionDeniedException;
import io.github.dhluong90.bpm.rules.api.ReplayNotPossibleException;
import io.github.dhluong90.bpm.rules.api.RuleEngine;
import io.github.dhluong90.bpm.rules.api.RuleSetNotFoundException;
import io.github.dhluong90.bpm.rules.api.SourceFormat;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.api.VersionNotFoundException;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.store.StoredVersion;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.internal.eval.CompiledRuleSet;
import io.github.dhluong90.bpm.rules.internal.read.RuleSetReader;
import io.github.dhluong90.bpm.testing.property.Gen;
import io.github.dhluong90.bpm.testing.property.Property;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * RLE-008 and RLE-022 (#22): the clock selects the version in force, a decision pinned to a version and clock or
 * replayed by its id gives the logged answer, and {@code today()} follows the rule set's time zone. Users of
 * {@code bank-a}: lan authors, minh approves, thu the auditor reads the log, pricing evaluates (with
 * {@code rules:simulate}) and teller evaluates without it.
 */
class EffectiveDatesAndReplayTest {

    static final Set<String> AUTHOR = Set.of("rules:read", "rules:author");
    static final Set<String> APPROVER = Set.of("rules:read", "rules:approve");
    /** Chooses clocks and pins versions: a test role; no role holds rules:pin by default. */
    static final Set<String> PRICING = Set.of("rules:evaluate", "rules:simulate", "rules:pin");
    static final Set<String> ANALYST = Set.of("rules:evaluate", "rules:simulate");
    static final Set<String> PINNER = Set.of("rules:evaluate", "rules:pin");
    static final Set<String> TELLER = Set.of("rules:evaluate");
    static final Set<String> AUDITOR = Set.of("rules:log:read");

    static final Instant NOV_20 = Instant.parse("2026-11-20T10:00:00Z");
    static final Instant DEC_01 = Instant.parse("2026-12-01T00:00:00Z");
    static final Instant DEC_05 = Instant.parse("2026-12-05T08:00:00Z");
    static final DecisionInput STANDARD = DecisionInput.of(Map.of("tier", "STANDARD", "txCount", 1));

    final EditorApiTest.MovableClock clock = new EditorApiTest.MovableClock(Instant.parse("2026-10-20T00:00:00Z"));
    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "lan");
    final RuleEngines engines = RuleEngines.create(
            new EngineContext(caller, caller, clock, stores, stores, EngineSettings.defaults(),
                    // A masked input is logged as its keyed fingerprint, so the tenant needs a key (RLE-012).
                    io.github.dhluong90.bpm.rules.spi.MaskingPolicy.withKey(io.github.dhluong90.bpm.rules.api.MaskingKey
                            .of("replay-test-key-of-32-bytes-long".getBytes(StandardCharsets.UTF_8)))),
            stores, stores, stores);
    final VersionService versions = engines.versionService();
    final RuleEngine engine = engines.ruleEngine();

    /** The card fee of RLE-008: GOLD pays nothing, everyone else pays {@code fee}, billed on {@code today()}. */
    static byte[] cardFee(String effectiveFrom, String fee) {
        return ("""
                schemaVersion: 1
                ruleSet: card-fee
                effectiveFrom: "%s"
                hitPolicy: first
                inputs:
                  - { name: tier, type: string }
                  - { name: txCount, type: integer }
                outputs:
                  - { name: fee, type: money }
                  - { name: billedOn, type: date }
                rules:
                  - { id: R1, when: { tier: "== 'GOLD'" }, then: { fee: "0 VND", billedOn: "today()" } }
                  - { id: R2, when: {}, then: { fee: "%s", billedOn: "today()" } }
                """).formatted(effectiveFrom, fee).getBytes(StandardCharsets.UTF_8);
    }

    /** The same-day cut-off of RLE-022, with or without a time zone. */
    static byte[] sameDayCutoff(String timeZone) {
        return ("""
                schemaVersion: 1
                ruleSet: same-day-cutoff
                %s
                hitPolicy: first
                inputs:
                  - { name: txDate, type: date }
                outputs:
                  - { name: sameDay, type: boolean }
                rules:
                  - { id: R1, when: { txDate: "== today()" }, then: { sameDay: true } }
                """).formatted(timeZone == null ? "" : "timeZone: " + timeZone).getBytes(StandardCharsets.UTF_8);
    }

    void publish(String ruleSet, byte[] document, Instant approvedAt) {
        caller.become("bank-a", "lan", AUTHOR);
        int number = versions.findRuleSet(ruleSet).isPresent()
                ? versions.importVersion(ruleSet, ImportRequest.yaml(document)).version()
                : versions.importRuleSet(ImportRequest.yaml(document)).version();
        long lock = versions.requestPublish(ruleSet, number, WriteOptions.none()).lockVersion();
        clock.set(approvedAt);
        caller.become("bank-a", "minh", APPROVER);
        versions.approve(ruleSet, number, new WriteOptions(null, lock, null));
    }

    /** v1 from 1 Nov at 50,000 VND, approved 20 Oct; v2 from 1 Dec at 30,000 VND, approved 25 Nov. */
    void givenTheTwoCardFees() {
        publish("card-fee", cardFee("2026-11-01T00:00:00Z", "50000 VND"), Instant.parse("2026-10-20T00:00:00Z"));
        publish("card-fee", cardFee("2026-12-01T00:00:00Z", "30000 VND"), Instant.parse("2026-11-25T10:00:00Z"));
    }

    static Money vnd(long amount) {
        return Money.ofMinor("VND", amount);
    }

    // ------------------------------------------------------------------ RLE-008

    @Test
    void RLE_008_explicit_clock_resolves_effective_version_and_replays() {
        givenTheTwoCardFees();
        clock.set(Instant.parse("2026-11-26T09:00:00Z"));
        caller.become("bank-a", "pricing", PRICING);

        // When evaluated with clock 20 Nov, then with clock 1 Dec
        DecisionResult november = engine.evaluate(DecisionRequest.of("card-fee", STANDARD).withClock(NOV_20));
        DecisionResult december = engine.evaluate(DecisionRequest.of("card-fee", STANDARD).withClock(DEC_01));

        // Then v1 and 50,000 VND, then v2 and 30,000 VND; today() is the clock's date, not the host's
        assertThat(november.version()).isEqualTo(1);
        assertThat(november.outputs()).containsEntry("fee", vnd(50_000)).containsEntry("billedOn", LocalDate.of(2026, 11, 20));
        assertThat(november.matchedRuleIds()).containsExactly("R2");
        assertThat(november.clock()).isEqualTo(NOV_20);
        assertThat(november.evaluatedAt()).isEqualTo(Instant.parse("2026-11-26T09:00:00Z"));
        assertThat(december.version()).isEqualTo(2);
        assertThat(december.outputs()).containsEntry("fee", vnd(30_000)).containsEntry("billedOn", LocalDate.of(2026, 12, 1));
        // and the row records the clock it reasoned about beside the time it ran
        assertThat(stores.decisions.getFirst().evalClock()).isEqualTo(NOV_20);
        assertThat(stores.decisions.getFirst().createdAt()).isEqualTo(Instant.parse("2026-11-26T09:00:00Z"));

        // When the November decision is replayed by its id after 5 Dec, and evaluated pinned to (v1, 20 Nov)
        clock.set(DEC_05);
        int rowsBefore = stores.decisions.size();
        caller.become("bank-a", "thu", AUDITOR);
        DecisionResult replayed = engine.replay(november.decisionId());
        caller.become("bank-a", "pricing", PRICING);
        DecisionResult pinned = engine.evaluate(DecisionRequest.of("card-fee", STANDARD).withVersion(1).withClock(NOV_20));

        // Then version, fee and matchedRuleIds are identical to the logged decision; replay wrote nothing
        assertThat(replayed.decisionId()).isEqualTo(november.decisionId());
        assertThat(replayed.version()).isEqualTo(1);
        assertThat(replayed.clock()).isEqualTo(NOV_20);
        assertThat(replayed.outcome()).isEqualTo(november.outcome());
        assertThat(pinned.version()).isEqualTo(1);
        assertThat(pinned.outcome()).isEqualTo(november.outcome());
        assertThat(stores.decisions).hasSize(rowsBefore + 1); // the pinned evaluation's own row only
        // while the host clock alone now selects v2
        assertThat(engine.evaluate(DecisionRequest.of("card-fee", STANDARD)).version()).isEqualTo(2);
    }

    @Test
    void RLE_008_selection_takes_the_highest_approved_version_in_force_whatever_its_date_order() {
        // Given v1 from 1 Dec, then v2 approved later with an earlier date, 25 Nov (ADR-0025 decision 3)
        publish("card-fee", cardFee("2026-12-01T00:00:00Z", "50000 VND"), Instant.parse("2026-11-01T00:00:00Z"));
        publish("card-fee", cardFee("2026-11-25T00:00:00Z", "30000 VND"), Instant.parse("2026-11-02T00:00:00Z"));
        caller.become("bank-a", "pricing", PRICING);

        // Then before 25 Nov nothing is in force, from 25 Nov v2, and on 5 Dec still v2, the higher version
        assertThatThrownBy(() -> engine.evaluate(DecisionRequest.of("card-fee", STANDARD)
                .withClock(Instant.parse("2026-11-24T23:59:59Z")))).isInstanceOf(NoEffectiveVersionException.class);
        assertThat(engine.evaluate(DecisionRequest.of("card-fee", STANDARD)
                .withClock(Instant.parse("2026-11-25T00:00:00Z"))).version()).isEqualTo(2);
        assertThat(engine.evaluate(DecisionRequest.of("card-fee", STANDARD).withClock(DEC_05)).version()).isEqualTo(2);
    }

    @Test
    void RLE_008_a_chosen_clock_needs_rules_simulate_and_writes_nothing_without_it() {
        givenTheTwoCardFees();
        caller.become("bank-a", "teller", TELLER);

        assertThatThrownBy(() -> engine.evaluate(DecisionRequest.of("card-fee", STANDARD).withClock(NOV_20)))
                .isInstanceOf(ClockNotAllowedException.class)
                .extracting(e -> ((HorusBpmException) e).code()).isEqualTo("CLOCK_NOT_ALLOWED");
        caller.become("bank-a", "pinner", PINNER);
        assertThatThrownBy(() -> engine.evaluate(DecisionRequest.of("card-fee", STANDARD).withVersion(1).withClock(NOV_20)))
                .isInstanceOf(ClockNotAllowedException.class);
        assertThat(stores.decisions).isEmpty();
        // and pinning a published version at the host clock needs rules:pin, not rules:simulate
        clock.set(DEC_05);
        DecisionResult pinned = engine.evaluate(DecisionRequest.of("card-fee", STANDARD).withVersion(1));
        assertThat(pinned.version()).isEqualTo(1);
        assertThat(pinned.outputs()).containsEntry("fee", vnd(50_000)).containsEntry("billedOn", LocalDate.of(2026, 12, 5));
    }

    @Test
    void RLE_008_a_pinned_version_must_exist_and_have_been_approved() {
        givenTheTwoCardFees();
        caller.become("bank-a", "lan", AUTHOR);
        versions.importVersion("card-fee", ImportRequest.yaml(cardFee("2027-01-01T00:00:00Z", "20000 VND")));
        caller.become("bank-a", "pricing", PRICING);

        assertThatThrownBy(() -> engine.evaluate(DecisionRequest.of("card-fee", STANDARD).withVersion(3)))
                .isInstanceOf(InvalidStateException.class).hasMessageContaining("simulate a draft instead");
        assertThatThrownBy(() -> engine.evaluate(DecisionRequest.of("card-fee", STANDARD).withVersion(9)))
                .isInstanceOf(VersionNotFoundException.class);
        assertThatThrownBy(() -> engine.evaluate(DecisionRequest.of("no-such-fee", STANDARD).withVersion(1)))
                .isInstanceOf(RuleSetNotFoundException.class);
        assertThat(stores.decisions).isEmpty();
    }

    @Test
    void RLE_008_replay_of_another_tenants_decision_is_not_found_and_writes_nothing() {
        // Given a decision of bank-b
        givenTheTwoCardFees();
        caller.become("bank-b", "lan", AUTHOR);
        versions.importRuleSet(ImportRequest.yaml(cardFee("2026-11-01T00:00:00Z", "45000 VND")));
        long lock = versions.requestPublish("card-fee", 1, WriteOptions.none()).lockVersion();
        caller.become("bank-b", "minh", APPROVER);
        versions.approve("card-fee", 1, new WriteOptions(null, lock, null));
        caller.become("bank-b", "pricing", PRICING);
        String bankB = engine.evaluate(DecisionRequest.of("card-fee", STANDARD).withClock(NOV_20)).decisionId();
        int rows = stores.decisions.size();

        // When bank-a's auditor replays it, or an id that cannot exist, or one that was never written
        caller.become("bank-a", "thu", AUDITOR);

        // Then 404 DECISION_NOT_FOUND each time, and nothing is written
        assertThatThrownBy(() -> engine.replay(bankB)).isInstanceOf(DecisionNotFoundException.class)
                .extracting(e -> ((HorusBpmException) e).code()).isEqualTo("DECISION_NOT_FOUND");
        assertThatThrownBy(() -> engine.replay("not-a-ulid")).isInstanceOf(DecisionNotFoundException.class);
        assertThatThrownBy(() -> engine.replay(null)).isInstanceOf(DecisionNotFoundException.class);
        assertThatThrownBy(() -> engine.replay("01KCS3W1DQ0M8V6T2B9R5N7K4E")).isInstanceOf(DecisionNotFoundException.class);
        assertThat(stores.decisions).hasSize(rows);
        // and bank-b's own auditor gets it
        caller.become("bank-b", "thu", AUDITOR);
        assertThat(engine.replay(bankB).outputs()).containsEntry("fee", vnd(45_000));
    }

    @Test
    void RLE_008_replay_needs_rules_log_read() {
        givenTheTwoCardFees();
        caller.become("bank-a", "pricing", PRICING);
        String id = engine.evaluate(DecisionRequest.of("card-fee", STANDARD).withClock(NOV_20)).decisionId();

        assertThatThrownBy(() -> engine.replay(id)).isInstanceOf(PermissionDeniedException.class);
        caller.become("bank-a", "minh", APPROVER);
        assertThatThrownBy(() -> engine.replay(id)).isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void RLE_008_a_masked_input_with_a_value_cannot_be_replayed_and_a_masked_null_can() {
        // Given a fee whose national id is masked in the log
        byte[] masked = ("""
                schemaVersion: 1
                ruleSet: id-check-fee
                hitPolicy: first
                inputs:
                  - { name: nationalId, type: string, mask: true }
                  - { name: tier, type: string }
                outputs:
                  - { name: fee, type: money }
                rules:
                  - { id: R1, when: { tier: "== 'GOLD'" }, then: { fee: "0 VND" } }
                  - { id: R2, when: {}, then: { fee: "20000 VND" } }
                """).getBytes(StandardCharsets.UTF_8);
        publish("id-check-fee", masked, Instant.parse("2026-11-01T00:00:00Z"));
        caller.become("bank-a", "teller", TELLER);
        String withValue = engine.evaluate(DecisionRequest.of("id-check-fee",
                DecisionInput.of(Map.of("nationalId", "079201001234", "tier", "STANDARD")))).decisionId();
        java.util.Map<String, Object> nullId = new java.util.HashMap<>();
        nullId.put("nationalId", null);
        nullId.put("tier", "GOLD");
        String withNull = engine.evaluate(DecisionRequest.of("id-check-fee", DecisionInput.of(nullId))).decisionId();

        // When the auditor replays both
        caller.become("bank-a", "thu", AUDITOR);

        // Then the first is 422 REPLAY_NOT_POSSIBLE naming the input, never its value; the second replays
        assertThatThrownBy(() -> engine.replay(withValue)).isInstanceOf(ReplayNotPossibleException.class)
                .hasMessageContaining("nationalId").hasMessageNotContaining("079201001234")
                .extracting(e -> ((HorusBpmException) e).code()).isEqualTo("REPLAY_NOT_POSSIBLE");
        assertThat(engine.replay(withNull).outputs()).containsEntry("fee", vnd(0));
    }

    @Test
    void RLE_008_an_idempotency_key_reused_with_another_clock_or_version_is_refused() {
        givenTheTwoCardFees();
        caller.become("bank-a", "pricing", PRICING);
        DecisionResult first = engine.evaluate(DecisionRequest.of("card-fee", STANDARD).withClock(NOV_20)
                .withIdempotencyKey("k-801"));

        // The same request replays the logged decision; another clock or a pin under the same key is refused
        assertThat(engine.evaluate(DecisionRequest.of("card-fee", STANDARD).withClock(NOV_20)
                .withIdempotencyKey("k-801"))).isEqualTo(first);
        assertThatThrownBy(() -> engine.evaluate(DecisionRequest.of("card-fee", STANDARD).withClock(DEC_01)
                .withIdempotencyKey("k-801"))).isInstanceOf(IdempotencyKeyReuseException.class);
        assertThatThrownBy(() -> engine.evaluate(DecisionRequest.of("card-fee", STANDARD).withClock(NOV_20).withVersion(1)
                .withIdempotencyKey("k-801"))).isInstanceOf(IdempotencyKeyReuseException.class);
        assertThat(stores.decisions).hasSize(1);
    }

    @Test
    void RLE_008_a_pinned_evaluation_needs_rules_pin_and_writes_nothing_without_it() {
        // Given the two card fees; an analyst may choose clocks but holds no rules:pin, nor does a teller
        givenTheTwoCardFees();

        for (Set<String> permissions : List.of(ANALYST, TELLER)) {
            caller.become("bank-a", "someone", permissions);
            // Then pinning v1, which would bypass the version in force, is 403 naming rules:pin
            assertThatThrownBy(() -> engine.evaluate(DecisionRequest.of("card-fee", STANDARD).withVersion(1)))
                    .isInstanceOf(PermissionDeniedException.class).hasMessageContaining("rules:pin");
        }
        assertThat(stores.decisions).isEmpty();
        // and the same callers still evaluate the version in force
        clock.set(DEC_05);
        assertThat(engine.evaluate(DecisionRequest.of("card-fee", STANDARD)).version()).isEqualTo(2);
        // and rules:pin alone is the permission of a pinned evaluation, as a service identity holds it
        caller.become("bank-a", "process-engine", Set.of("rules:pin"));
        assertThat(engine.evaluate(DecisionRequest.of("card-fee", STANDARD).withVersion(1)).version()).isEqualTo(1);
        assertThatThrownBy(() -> engine.evaluate(DecisionRequest.of("card-fee", STANDARD)))
                .isInstanceOf(PermissionDeniedException.class).hasMessageContaining("rules:evaluate");
    }

    @Test
    void RLE_008_pinning_a_retired_version_is_refused_unless_the_host_allows_it() {
        // Given card-fee v1, decided on 20 Nov, then taken out of service (RETIRED)
        givenTheTwoCardFees();
        caller.become("bank-a", "pricing", PRICING);
        String november = engine.evaluate(DecisionRequest.of("card-fee", STANDARD).withClock(NOV_20)).decisionId();
        for (int i = 0; i < stores.versions.size(); i++) {
            StoredVersion v = stores.versions.get(i);
            if (v.version() == 1) {
                stores.versions.set(i, new StoredVersion(v.tenantId(), v.id(), v.ruleSetId(), v.ruleSet(), v.version(),
                        VersionStatus.RETIRED, v.hitPolicy(), v.effectiveFrom(), v.canonicalJson(), v.canonicalHash(),
                        v.sourceFormat(), v.sourceDocument(), v.sourceHash(), v.lockVersion() + 1, v.requestedBy(),
                        v.requestedAt(), v.approvedBy(), v.approvedAt(), v.approvalComment(), v.createdBy(),
                        v.createdAt()));
            }
        }
        int rows = stores.decisions.size();

        // When a caller with rules:pin pins it, by default
        // Then 409 INVALID_STATE naming the setting, and no decision row
        assertThatThrownBy(() -> engine.evaluate(DecisionRequest.of("card-fee", STANDARD).withVersion(1)))
                .isInstanceOf(InvalidStateException.class).hasMessageContaining("evaluation.pin-retired")
                .hasMessageContaining("RETIRED");
        assertThat(stores.decisions).hasSize(rows);
        // and replay of its logged decision is unaffected: it writes nothing
        caller.become("bank-a", "thu", AUDITOR);
        assertThat(engine.replay(november).outputs()).containsEntry("fee", vnd(50_000));

        // When the host turns evaluation.pin-retired on, Then the pin is accepted and logged
        RuleEngine allowing = RuleEngines.create(new EngineContext(caller, caller, clock, stores, stores,
                EngineSettings.defaults().withPinRetired(true)), stores, stores, stores).ruleEngine();
        caller.become("bank-a", "pricing", PRICING);
        assertThat(allowing.evaluate(DecisionRequest.of("card-fee", STANDARD).withVersion(1)).version()).isEqualTo(1);
        assertThat(stores.decisions).hasSize(rows + 1);
    }

    @Test
    void RLE_008_a_pinned_decision_is_marked_on_its_row_in_the_result_and_on_replay() {
        givenTheTwoCardFees();
        clock.set(DEC_05);
        caller.become("bank-a", "pricing", PRICING);

        DecisionResult pinned = engine.evaluate(DecisionRequest.of("card-fee", STANDARD).withVersion(1));
        DecisionResult selected = engine.evaluate(DecisionRequest.of("card-fee", STANDARD));

        // Then the engine sets the mark, never the request: the row, the result and its JSON say pinned
        assertThat(pinned.pinned()).isTrue();
        assertThat(selected.pinned()).isFalse();
        assertThat(stores.decisions.get(0).pinned()).isTrue();
        assertThat(stores.decisions.get(1).pinned()).isFalse();
        tools.jackson.databind.JsonNode json = io.github.dhluong90.bpm.rules.internal.json.EngineValues.decision(pinned);
        assertThat(json.path("pinned").asBoolean()).isTrue();
        assertThat(json.path("evalClock").asString()).isEqualTo(DEC_05.toString());
        assertThat(json.path("clockChosen").asBoolean()).isFalse();
        // and a replay reads the marks back from the row
        caller.become("bank-a", "thu", AUDITOR);
        assertThat(engine.replay(pinned.decisionId()).pinned()).isTrue();
        assertThat(engine.replay(selected.decisionId()).pinned()).isFalse();
        // and an idempotent replay of a pinned evaluation keeps the mark
        caller.become("bank-a", "pricing", PRICING);
        DecisionResult keyed = engine.evaluate(DecisionRequest.of("card-fee", STANDARD).withVersion(1)
                .withIdempotencyKey("k-pin"));
        assertThat(engine.evaluate(DecisionRequest.of("card-fee", STANDARD).withVersion(1).withIdempotencyKey("k-pin")))
                .isEqualTo(keyed).extracting(DecisionResult::pinned).isEqualTo(true);
    }

    @Test
    void RLE_008_a_decision_at_a_chosen_clock_is_flagged_as_back_dated() {
        givenTheTwoCardFees();
        clock.set(DEC_05);
        caller.become("bank-a", "pricing", PRICING);

        DecisionResult backDated = engine.evaluate(DecisionRequest.of("card-fee", STANDARD).withClock(NOV_20));
        DecisionResult now = engine.evaluate(DecisionRequest.of("card-fee", STANDARD));

        // Then the result and the decision row mark the chosen clock; a host-clock decision is not marked
        assertThat(backDated.clockChosen()).isTrue();
        assertThat(now.clockChosen()).isFalse();
        assertThat(stores.decisions.get(0).evalClock()).isNotEqualTo(stores.decisions.get(0).createdAt());
        assertThat(stores.decisions.get(1).evalClock()).isEqualTo(stores.decisions.get(1).createdAt());
        assertThat(io.github.dhluong90.bpm.rules.internal.json.EngineValues.decision(backDated).path("clockChosen")
                .asBoolean()).isTrue();
        // and a replay keeps the mark, from the row
        caller.become("bank-a", "thu", AUDITOR);
        assertThat(engine.replay(backDated.decisionId()).clockChosen()).isTrue();
        assertThat(engine.replay(now.decisionId()).clockChosen()).isFalse();
    }

    // ------------------------------------------------------------------ RLE-022

    @Test
    void RLE_022_today_uses_rule_set_time_zone() {
        // Given same-day-cutoff v1 in Asia/Ho_Chi_Minh and v2 without a time zone (UTC), each evaluated while it is
        // the version in force (v2's approval retires v1, and a retired version is not pinned by default)
        Instant clockAt = Instant.parse("2027-01-15T18:30:00Z"); // 01:30 on 16 January in Ho Chi Minh City
        DecisionInput tx = DecisionInput.of(Map.of("txDate", LocalDate.of(2027, 1, 16)));

        // When both are evaluated with txDate 2027-01-16 at that clock
        publish("same-day-cutoff", sameDayCutoff("Asia/Ho_Chi_Minh"), Instant.parse("2027-01-10T00:00:00Z"));
        caller.become("bank-a", "pricing", PRICING);
        DecisionResult v1 = engine.evaluate(DecisionRequest.of("same-day-cutoff", tx).withVersion(1).withClock(clockAt));
        publish("same-day-cutoff", sameDayCutoff(null), Instant.parse("2027-01-10T00:00:01Z"));
        caller.become("bank-a", "pricing", PRICING);
        DecisionResult v2 = engine.evaluate(DecisionRequest.of("same-day-cutoff", tx).withVersion(2).withClock(clockAt));

        // Then v1 matches R1 (today() is 16 January there) and v2 does not (15 January in UTC)
        assertThat(v1.matchedRuleIds()).containsExactly("R1");
        assertThat(v1.outputs()).containsEntry("sameDay", true);
        assertThat(v2.outcome()).isInstanceOf(DecisionResult.NoMatch.class);
        // and replaying either decision with its logged version and clock gives the same result
        caller.become("bank-a", "thu", AUDITOR);
        assertThat(engine.replay(v1.decisionId()).outcome()).isEqualTo(v1.outcome());
        assertThat(engine.replay(v2.decisionId()).outcome()).isEqualTo(v2.outcome());
        // and the zone is part of the version, not of the hash's metadata: the two versions hash differently
        caller.become("bank-a", "lan", AUTHOR);
        assertThat(versions.find("same-day-cutoff", 1, false).orElseThrow().canonicalHash())
                .isNotEqualTo(versions.find("same-day-cutoff", 2, false).orElseThrow().canonicalHash());
    }

    @Test
    void RLE_022_clock_readings_are_type_checked_at_import() {
        caller.become("bank-a", "lan", AUTHOR);
        // a date compared with now(), a datetime
        assertThatThrownBy(() -> versions.importRuleSet(ImportRequest.yaml(
                new String(sameDayCutoff(null), StandardCharsets.UTF_8).replace("== today()", "== now()")
                        .getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(ValidationFailedException.class).hasMessageContaining("date").hasMessageContaining("datetime");
        // an argument
        assertThatThrownBy(() -> versions.importRuleSet(ImportRequest.yaml(
                new String(sameDayCutoff(null), StandardCharsets.UTF_8).replace("== today()", "== today('UTC')")
                        .getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(ValidationFailedException.class).hasMessageContaining("today() takes no argument");
        assertThat(stores.versions).isEmpty();
    }

    @Test
    void RLE_022_an_input_named_today_is_an_input_and_today_with_parentheses_is_the_clock() {
        String yaml = """
                schemaVersion: 1
                ruleSet: cutoff-check
                hitPolicy: first
                inputs:
                  - { name: today, type: date }
                outputs:
                  - { name: same, type: boolean }
                rules:
                  - { id: R1, when: { today: "== today()" }, then: { same: true } }
                """;
        CompiledRuleSet rules = CompiledRuleSet.compile(RuleSetReader.read(SourceFormat.YAML,
                yaml.getBytes(StandardCharsets.UTF_8)).model());

        assertThat(rules.evaluate(DecisionInput.of(Map.of("today", LocalDate.of(2027, 1, 15))),
                Instant.parse("2027-01-15T18:30:00Z")).getClass()).isEqualTo(DecisionResult.Matched.class);
        assertThat(rules.evaluate(DecisionInput.of(Map.of("today", LocalDate.of(2027, 1, 16))),
                Instant.parse("2027-01-15T18:30:00Z")).getClass()).isEqualTo(DecisionResult.NoMatch.class);
        // and without a clock the reading fails deterministically, never with the system time
        assertThat(rules.evaluate(DecisionInput.of(Map.of("today", LocalDate.of(2027, 1, 15)))))
                .isInstanceOf(DecisionResult.Failed.class)
                .extracting(o -> ((DecisionResult.Failed) o).detail()).isEqualTo("today() needs an evaluation clock");
    }

    static final Gen<ZoneId> ZONES = Gen.of("UTC", "Asia/Ho_Chi_Minh", "Asia/Singapore", "Pacific/Kiritimati",
            "Pacific/Pago_Pago", "America/St_Johns", "Asia/Kathmandu", "Europe/London").map(ZoneId::of);
    /** Instants from 2000 to 2100, at second precision, DST changes included. */
    static final Gen<Instant> INSTANTS = Gen.longs(946_684_800L, 4_102_444_800L).map(Instant::ofEpochSecond);

    @TestFactory
    Stream<DynamicTest> RLE_022_today_is_the_clock_date_in_the_rule_set_zone_and_replays_the_same() {
        return Property.tries(500).forAll(ZONES, INSTANTS, (zone, at) -> {
            String yaml = """
                    schemaVersion: 1
                    ruleSet: clock-reading
                    timeZone: %s
                    hitPolicy: first
                    inputs:
                      - { name: x, type: integer }
                    outputs:
                      - { name: d, type: date }
                      - { name: n, type: datetime }
                    rules:
                      - { id: R1, when: {}, then: { d: "today()", n: "now()" } }
                    """.formatted(zone.getId());
            CompiledRuleSet rules = CompiledRuleSet.compile(RuleSetReader.readCanonical(RuleSetReader.read(
                    SourceFormat.YAML, yaml.getBytes(StandardCharsets.UTF_8)).canonicalJson()));
            DecisionInput input = DecisionInput.of(Map.of("x", 1));

            DecisionResult.Outcome first = rules.evaluate(input, at);
            DecisionResult.Outcome again = rules.evaluate(input, at);

            assertThat(((DecisionResult.Matched) first).outputs())
                    .containsEntry("d", LocalDate.ofInstant(at, zone)).containsEntry("n", at);
            assertThat(again).isEqualTo(first);
        });
    }
}
