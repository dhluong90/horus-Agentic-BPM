package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.HorusBpmException;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.InvalidStateException;
import io.github.dhluong90.bpm.rules.api.LastVersionInEffectException;
import io.github.dhluong90.bpm.rules.api.LifecycleAction;
import io.github.dhluong90.bpm.rules.api.NoEffectiveVersionException;
import io.github.dhluong90.bpm.rules.api.PermissionDeniedException;
import io.github.dhluong90.bpm.rules.api.RuleSetNotFoundException;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.VersionConflictException;
import io.github.dhluong90.bpm.rules.api.VersionHistoryEntry;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * RLE-026 and RLE-027 (#28): a published version retired by hand stops serving from its instant on, on every replica,
 * while a clock before it still selects it; a version superseded by a newer one in effect retires exactly once, at
 * approval or by the lifecycle sweep, whichever replica runs it. Users of {@code bank-a}: lan authors, bob and minh
 * approve, carol only authors, teller evaluates. Two engines over one store stand in for two replicas.
 */
class RetireVersionTest {

    static final Set<String> AUTHOR = Set.of("rules:read", "rules:author");
    static final Set<String> APPROVER = Set.of("rules:read", "rules:approve");
    static final Set<String> TELLER = Set.of("rules:evaluate");

    final EditorApiTest.MovableClock clock = new EditorApiTest.MovableClock(Instant.parse("2027-01-10T00:00:00Z"));
    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "lan");
    final RuleEngines podA = engines(EngineSettings.defaults().withRequireContentHash(false));
    final RuleEngines podB = engines(EngineSettings.defaults().withRequireContentHash(false));
    final VersionService versions = podA.versionService();

    RuleEngines engines(EngineSettings settings) {
        return RuleEngines.create(new EngineContext(caller, caller, clock, stores, stores, settings), stores, stores,
                stores);
    }

    /** The Tet fee waiver of RLE-026: every card transaction waives its fee while the promotion runs. */
    static byte[] tetFeeWaiver(String effectiveFrom) {
        return ("""
                schemaVersion: 1
                ruleSet: tet-fee-waiver
                effectiveFrom: "%s"
                hitPolicy: first
                inputs:
                  - { name: channel, type: string }
                outputs:
                  - { name: waiveFee, type: boolean }
                rules:
                  - { id: R1, when: {}, then: { waiveFee: true } }
                """).formatted(effectiveFrom).getBytes(StandardCharsets.UTF_8);
    }

    static byte[] cardFee(String name, String effectiveFrom, String fee) {
        return ("""
                schemaVersion: 1
                ruleSet: %s
                %s
                hitPolicy: first
                inputs:
                  - { name: tier, type: string }
                outputs:
                  - { name: fee, type: money }
                rules:
                  - { id: R1, when: { tier: "== 'GOLD'" }, then: { fee: "0 VND" } }
                  - { id: R2, when: {}, then: { fee: "%s" } }
                """).formatted(name, effectiveFrom == null ? "" : "effectiveFrom: \"" + effectiveFrom + "\"", fee)
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Imports, requests (lan) and approves (minh) the next version at {@code approvedAt}; returns it published. */
    RuleSetVersion publish(String tenant, String ruleSet, byte[] document, Instant approvedAt) {
        caller.become(tenant, "lan", AUTHOR);
        int number = versions.findRuleSet(ruleSet).isPresent()
                ? versions.importVersion(ruleSet, ImportRequest.yaml(document)).version()
                : versions.importRuleSet(ImportRequest.yaml(document)).version();
        long reviewed = versions.requestPublish(ruleSet, number, WriteOptions.none()).lockVersion();
        clock.set(approvedAt);
        caller.become(tenant, "minh", APPROVER);
        return versions.approve(ruleSet, number, new WriteOptions(null, reviewed, null));
    }

    DecisionResult evaluate(RuleEngines pod, String ruleSet, Map<String, Object> inputs) {
        caller.become("bank-a", "teller", TELLER);
        return pod.ruleEngine().evaluate(ruleSet, DecisionInput.of(inputs));
    }

    List<JsonNode> retiredEvents() {
        return stores.outbox.stream().map(row -> EngineJson.JSON.readTree(row.payload()))
                .filter(e -> e.path("eventType").asString().equals("rules.version.retired.v1")).toList();
    }

    List<VersionHistoryEntry> history(String tenant, String ruleSet, int version) {
        caller.become(tenant, "dan", Set.of("rules:read"));
        return versions.history(ruleSet, version);
    }

    // ------------------------------------------------------------------ RLE-026

    @Test
    void RLE_026_manual_retire_stops_every_replica_serving_the_version() {
        // Given tet-fee-waiver v1, published, effective since 20 Jan 2027, and D1 logged against it on 1 Feb
        RuleSetVersion v1 = publish("bank-a", "tet-fee-waiver", tetFeeWaiver("2027-01-20T00:00:00Z"),
                Instant.parse("2027-01-15T00:00:00Z"));
        clock.set(Instant.parse("2027-02-01T09:00:00Z"));
        assertThat(evaluate(podA, "tet-fee-waiver", Map.of("channel", "CARD")).outputs()).containsEntry("waiveFee", true);
        assertThat(evaluate(podB, "tet-fee-waiver", Map.of("channel", "CARD")).version()).isEqualTo(1);

        // When bob retires it at 2027-02-17T08:00:00Z with a reason, under k-301 and If-Match
        clock.set(Instant.parse("2027-02-17T08:00:00Z"));
        caller.become("bank-a", "bob", APPROVER);
        WriteOptions command = new WriteOptions("k-301", v1.lockVersion(), "Tet promotion ended");
        RuleSetVersion retired = versions.retire("tet-fee-waiver", 1, command, true);

        // Then v1 is RETIRED from that instant, with one history row and one event recording the mode single
        assertThat(retired.status()).isEqualTo(VersionStatus.RETIRED);
        assertThat(retired.retiredAt()).isEqualTo(Instant.parse("2027-02-17T08:00:00Z"));
        assertThat(retired.lockVersion()).isEqualTo(v1.lockVersion() + 1);
        VersionHistoryEntry row = history("bank-a", "tet-fee-waiver", 1).getLast();
        assertThat(row).isEqualTo(new VersionHistoryEntry(LifecycleAction.RETIRED, VersionStatus.PUBLISHED,
                VersionStatus.RETIRED, "bob", "Tet promotion ended", retired.lockVersion(),
                Instant.parse("2027-02-17T08:00:00Z"), null, "single", true));
        assertThat(retiredEvents()).singleElement().satisfies(event -> {
            assertThat(event.path("ruleSet").asString()).isEqualTo("tet-fee-waiver");
            assertThat(event.path("version").asInt()).isEqualTo(1);
            assertThat(event.path("tenantId").asString()).isEqualTo("bank-a");
            assertThat(event.path("retiredBy").asString()).isEqualTo("bob");
            assertThat(event.path("actor").asString()).isEqualTo("bob");
            assertThat(event.path("reason").asString()).isEqualTo("Tet promotion ended");
            assertThat(event.path("retiredAt").asString()).isEqualTo("2027-02-17T08:00:00Z");
            assertThat(event.path("approvalMode").asString()).isEqualTo("single");
            assertThat(event.path("supersededBy").isNull()).isTrue();
            assertThat(event.path("noVersionInEffect").asBoolean()).isTrue();
            LifecycleEventPayloadTest.conformsTo("rules.version.retired.v1.schema.json", event);
        });

        // and 5 s later, once pod B's epoch check has run (ADR-0009; in production the notification usually comes
        // first), both replicas answer 404 NO_EFFECTIVE_VERSION
        clock.set(Instant.parse("2027-02-17T08:00:05Z"));
        podB.cache().checkEpochs();
        for (RuleEngines pod : List.of(podA, podB)) {
            assertThatThrownBy(() -> evaluate(pod, "tet-fee-waiver", Map.of("channel", "CARD")))
                    .isInstanceOf(NoEffectiveVersionException.class);
        }
        // and a clock before the retirement still selects v1 with waiveFee true; the version stays readable
        clock.set(Instant.parse("2027-02-01T09:00:00Z"));
        DecisionResult past = evaluate(podB, "tet-fee-waiver", Map.of("channel", "CARD"));
        assertThat(past.version()).isEqualTo(1);
        assertThat(past.outputs()).containsEntry("waiveFee", true);
        caller.become("bank-a", "dan", Set.of("rules:read"));
        assertThat(versions.find("tet-fee-waiver", 1, false)).get()
                .extracting(RuleSetVersion::status).isEqualTo(VersionStatus.RETIRED);

        // and a replay of k-301 returns the same version and writes no second event or history row
        clock.set(Instant.parse("2027-02-17T08:00:09Z"));
        caller.become("bank-a", "bob", APPROVER);
        assertThat(versions.retire("tet-fee-waiver", 1, command, true)).isEqualTo(retired);
        assertThat(retiredEvents()).hasSize(1);
        assertThat(history("bank-a", "tet-fee-waiver", 1)).filteredOn(e -> e.action() == LifecycleAction.RETIRED)
                .hasSize(1);
    }

    @Test
    void RLE_026_refused_retirements_change_nothing() {
        // Given tet-fee-waiver v1 published and v2 a draft
        RuleSetVersion v1 = publish("bank-a", "tet-fee-waiver", tetFeeWaiver("2027-01-20T00:00:00Z"),
                Instant.parse("2027-01-15T00:00:00Z"));
        caller.become("bank-a", "lan", AUTHOR);
        versions.importVersion("tet-fee-waiver", ImportRequest.yaml(tetFeeWaiver("2027-03-01T00:00:00Z")));
        clock.set(Instant.parse("2027-02-17T08:00:00Z"));
        int events = stores.outbox.size();
        int rows = stores.history.size();

        // a draft; an empty, blank or too long reason; a stale lock; a caller without rules:approve; another tenant
        caller.become("bank-a", "bob", APPROVER);
        assertThatThrownBy(() -> versions.retire("tet-fee-waiver", 2, new WriteOptions(null, null, "Wrong version")))
                .isInstanceOf(InvalidStateException.class).hasMessageContaining("DRAFT");
        for (String reason : new String[] {null, "", "   ", "x".repeat(2001)}) {
            assertThatThrownBy(() -> versions.retire("tet-fee-waiver", 1, new WriteOptions(null, null, reason)))
                    .isInstanceOf(InputInvalidException.class).hasMessageContaining("reason");
        }
        assertThatThrownBy(() -> versions.retire("tet-fee-waiver", 1,
                new WriteOptions(null, v1.lockVersion() - 1, "Tet promotion ended")))
                .isInstanceOf(VersionConflictException.class);
        caller.become("bank-a", "carol", AUTHOR);
        assertThatThrownBy(() -> versions.retire("tet-fee-waiver", 1, new WriteOptions(null, null, "Tet ended")))
                .isInstanceOf(PermissionDeniedException.class);
        caller.become("bank-b", "bob", APPROVER);
        assertThatThrownBy(() -> versions.retire("tet-fee-waiver", 1, new WriteOptions(null, null, "Tet ended")))
                .isInstanceOf(RuleSetNotFoundException.class);

        // Then the versions, their history and the events are unchanged, and v1 still serves
        assertThat(stores.outbox).hasSize(events);
        assertThat(stores.history).hasSize(rows);
        caller.become("bank-a", "dan", Set.of("rules:read"));
        assertThat(versions.find("tet-fee-waiver", 1, false).orElseThrow().status()).isEqualTo(VersionStatus.PUBLISHED);
        assertThat(evaluate(podA, "tet-fee-waiver", Map.of("channel", "CARD")).version()).isEqualTo(1);
    }

    @Test
    void RLE_026_a_retire_never_brings_an_older_version_back() {
        // Given card-fee v1 from 1 Nov, and v2 from 1 Dec approved on 25 Nov; on 2 Dec no sweep has run yet
        publish("bank-a", "card-fee", cardFee("card-fee", "2026-11-01T00:00:00Z", "50000 VND"),
                Instant.parse("2026-10-20T00:00:00Z"));
        RuleSetVersion v2 = publish("bank-a", "card-fee", cardFee("card-fee", "2026-12-01T00:00:00Z", "30000 VND"),
                Instant.parse("2026-11-25T10:00:00Z"));
        clock.set(Instant.parse("2026-12-02T09:00:00Z"));
        caller.become("bank-a", "dan", Set.of("rules:read"));
        assertThat(versions.find("card-fee", 1, false).orElseThrow().status()).isEqualTo(VersionStatus.PUBLISHED);

        // When bob retires v2, the version in force
        caller.become("bank-a", "bob", APPROVER);
        versions.retire("card-fee", 2, new WriteOptions(null, v2.lockVersion(), "Fee change withdrawn by pricing"), true);

        // Then v1 retires with it, as superseded by v2, and nothing serves
        assertThat(history("bank-a", "card-fee", 1).getLast()).satisfies(row -> {
            assertThat(row.action()).isEqualTo(LifecycleAction.RETIRED);
            assertThat(row.actor()).isEqualTo("system:supersession");
            assertThat(row.reason()).isEqualTo("SUPERSEDED");
            assertThat(row.supersededBy()).isEqualTo(2);
            assertThat(row.approvalMode()).isEqualTo("system");
        });
        assertThat(retiredEvents()).extracting(e -> e.path("version").asInt()).containsExactly(1, 2);
        assertThatThrownBy(() -> evaluate(podA, "card-fee", Map.of("tier", "STANDARD")))
                .isInstanceOf(NoEffectiveVersionException.class);
    }

    @Test
    void RLE_026_retiring_a_scheduled_version_leaves_the_version_in_force_serving() {
        // Given v1 in force and v2 scheduled for 1 Dec
        publish("bank-a", "card-fee", cardFee("card-fee", "2026-11-01T00:00:00Z", "50000 VND"),
                Instant.parse("2026-10-20T00:00:00Z"));
        RuleSetVersion v2 = publish("bank-a", "card-fee", cardFee("card-fee", "2026-12-01T00:00:00Z", "30000 VND"),
                Instant.parse("2026-11-25T10:00:00Z"));

        // When bob cancels the scheduled change on 26 Nov by retiring v2
        clock.set(Instant.parse("2026-11-26T09:00:00Z"));
        caller.become("bank-a", "bob", APPROVER);
        versions.retire("card-fee", 2, new WriteOptions(null, v2.lockVersion(), "December tariff postponed"));

        // Then v1 keeps serving, also after 1 Dec, and only v2 has a retired event
        clock.set(Instant.parse("2026-12-05T00:00:00Z"));
        assertThat(evaluate(podA, "card-fee", Map.of("tier", "STANDARD")).version()).isEqualTo(1);
        assertThat(retiredEvents()).extracting(e -> e.path("version").asInt()).containsExactly(2);
    }

    @Test
    void RLE_026_a_version_already_superseded_is_refused_and_retires_on_its_own() {
        // Given v1 and v2, v2 in force since 1 Dec, and no sweep yet
        RuleSetVersion v1 = publish("bank-a", "card-fee", cardFee("card-fee", "2026-11-01T00:00:00Z", "50000 VND"),
                Instant.parse("2026-10-20T00:00:00Z"));
        publish("bank-a", "card-fee", cardFee("card-fee", "2026-12-01T00:00:00Z", "30000 VND"),
                Instant.parse("2026-11-25T10:00:00Z"));
        clock.set(Instant.parse("2026-12-02T09:00:00Z"));
        int events = stores.outbox.size();

        // When bob retires v1 by hand
        caller.become("bank-a", "bob", APPROVER);
        assertThatThrownBy(() -> versions.retire("card-fee", 1, new WriteOptions(null, v1.lockVersion(), "Old tariff")))
                .isInstanceOf(InvalidStateException.class).hasMessageContaining("v2 is in effect and supersedes it")
                .extracting(e -> ((HorusBpmException) e).code()).isEqualTo("INVALID_STATE");

        // Then nothing was written, and the sweep retires it as superseded
        assertThat(stores.outbox).hasSize(events);
        assertThat(podB.lifecycleSweep().run()).isEqualTo(1);
        assertThat(history("bank-a", "card-fee", 1).getLast().reason()).isEqualTo("SUPERSEDED");
    }

    @Test
    void RLE_026_a_second_retire_with_the_same_lock_version_is_refused_and_writes_nothing() {
        // Given v1 published; bob and minh both send a retire for lock version L
        RuleSetVersion v1 = publish("bank-a", "tet-fee-waiver", tetFeeWaiver("2027-01-20T00:00:00Z"),
                Instant.parse("2027-01-15T00:00:00Z"));
        clock.set(Instant.parse("2027-02-17T08:00:00Z"));
        caller.become("bank-a", "bob", APPROVER);
        versions.retire("tet-fee-waiver", 1, new WriteOptions(null, v1.lockVersion(), "Tet promotion ended"), true);

        // When the second one arrives with the same lock version
        caller.become("bank-a", "minh", APPROVER);

        // Then it is refused on the state and no second retirement is written
        assertThatThrownBy(() -> versions.retire("tet-fee-waiver", 1,
                new WriteOptions(null, v1.lockVersion(), "Tet promotion ended"), true))
                .isInstanceOf(InvalidStateException.class).hasMessageContaining("RETIRED");
        assertThat(retiredEvents()).hasSize(1);
    }

    @Test
    void RLE_026_retiring_the_last_version_in_effect_needs_an_explicit_confirmation() {
        // Given tet-fee-waiver v1, the only version, in force (PD-101)
        RuleSetVersion v1 = publish("bank-a", "tet-fee-waiver", tetFeeWaiver("2027-01-20T00:00:00Z"),
                Instant.parse("2027-01-15T00:00:00Z"));
        clock.set(Instant.parse("2027-02-17T08:00:00Z"));
        int events = stores.outbox.size();
        int rows = stores.history.size();
        caller.become("bank-a", "bob", APPROVER);
        WriteOptions command = new WriteOptions("k-302", v1.lockVersion(), "Tet promotion ended");

        // When bob retires it without the confirmation
        // Then 409 LAST_VERSION_IN_EFFECT naming the rule set, and nothing is written or remembered under the key
        assertThatThrownBy(() -> versions.retire("tet-fee-waiver", 1, command))
                .isInstanceOf(LastVersionInEffectException.class)
                .hasMessageContaining("rule set tet-fee-waiver").hasMessageContaining("confirmNoVersionInEffect")
                .extracting(e -> ((HorusBpmException) e).code()).isEqualTo("LAST_VERSION_IN_EFFECT");
        assertThat(stores.outbox).hasSize(events);
        assertThat(stores.history).hasSize(rows);
        assertThat(evaluate(podA, "tet-fee-waiver", Map.of("channel", "CARD")).version()).isEqualTo(1);

        // When he confirms that no version will remain in effect, with the same key
        caller.become("bank-a", "bob", APPROVER);
        RuleSetVersion retired = versions.retire("tet-fee-waiver", 1, command, true);

        // Then it is retired and the history row and the event record that no version remains in effect
        assertThat(retired.status()).isEqualTo(VersionStatus.RETIRED);
        assertThat(history("bank-a", "tet-fee-waiver", 1).getLast().noVersionInEffect()).isTrue();
        assertThat(retiredEvents()).singleElement()
                .satisfies(e -> assertThat(e.path("noVersionInEffect").asBoolean()).isTrue());
    }

    @Test
    void RLE_026_a_version_that_only_starts_later_does_not_count_and_the_refusal_names_it() {
        // Given v1 in force and v2 approved for 1 Dec: retiring v1 on 26 Nov leaves a gap until v2 starts
        RuleSetVersion v1 = publish("bank-a", "card-fee", cardFee("card-fee", "2026-11-01T00:00:00Z", "50000 VND"),
                Instant.parse("2026-10-20T00:00:00Z"));
        publish("bank-a", "card-fee", cardFee("card-fee", "2026-12-01T00:00:00Z", "30000 VND"),
                Instant.parse("2026-11-25T10:00:00Z"));
        clock.set(Instant.parse("2026-11-26T09:00:00Z"));
        caller.become("bank-a", "bob", APPROVER);

        // Then the refusal carries the rule set, the version, the setting and the start of v2
        assertThatThrownBy(() -> versions.retire("card-fee", 1, new WriteOptions(null, v1.lockVersion(), "Old tariff")))
                .isInstanceOfSatisfying(LastVersionInEffectException.class, e -> {
                    assertThat(e.ruleSet()).isEqualTo("card-fee");
                    assertThat(e.version()).isEqualTo(1);
                    assertThat(e.setting()).isEqualTo("confirm");
                    assertThat(e.nextEffectiveFrom()).isEqualTo(Instant.parse("2026-12-01T00:00:00Z"));
                    assertThat(e.getMessage()).contains("until v2 starts at 2026-12-01T00:00:00Z");
                });
        assertThat(retiredEvents()).isEmpty();
    }

    @Test
    void RLE_026_a_lone_scheduled_version_is_the_last_one_too() {
        // Given fx-margin v1 approved for 1 Dec and nothing in effect on 26 Nov
        RuleSetVersion v1 = publish("bank-a", "fx-margin", cardFee("fx-margin", "2026-12-01T00:00:00Z", "100 VND"),
                Instant.parse("2026-11-25T10:00:00Z"));
        clock.set(Instant.parse("2026-11-26T09:00:00Z"));
        caller.become("bank-a", "bob", APPROVER);

        // Then retiring it needs the confirmation, there is no next start, and with it the gap is recorded
        assertThatThrownBy(() -> versions.retire("fx-margin", 1, new WriteOptions(null, v1.lockVersion(), "Cancelled")))
                .isInstanceOfSatisfying(LastVersionInEffectException.class,
                        e -> assertThat(e.nextEffectiveFrom()).isNull());
        versions.retire("fx-margin", 1, new WriteOptions(null, v1.lockVersion(), "Cancelled"), true);
        assertThat(history("bank-a", "fx-margin", 1).getLast().noVersionInEffect()).isTrue();
    }

    @Test
    void RLE_026_a_retirement_that_leaves_a_version_in_effect_needs_no_confirmation_and_says_so() {
        // Given v1 in force and v2 scheduled for 1 Dec: retiring v2 leaves v1 in effect
        publish("bank-a", "card-fee", cardFee("card-fee", "2026-11-01T00:00:00Z", "50000 VND"),
                Instant.parse("2026-10-20T00:00:00Z"));
        RuleSetVersion v2 = publish("bank-a", "card-fee", cardFee("card-fee", "2026-12-01T00:00:00Z", "30000 VND"),
                Instant.parse("2026-11-25T10:00:00Z"));
        clock.set(Instant.parse("2026-11-26T09:00:00Z"));
        caller.become("bank-a", "bob", APPROVER);

        versions.retire("card-fee", 2, new WriteOptions(null, v2.lockVersion(), "December tariff postponed"));

        assertThat(history("bank-a", "card-fee", 2).getLast().noVersionInEffect()).isFalse();
        assertThat(retiredEvents()).singleElement()
                .satisfies(e -> assertThat(e.path("noVersionInEffect").asBoolean()).isFalse());
    }

    @Test
    void RLE_026_a_host_that_refuses_never_retires_the_last_version_in_effect() {
        // Given horus.bpm.lifecycle.retire-last-version: refuse
        RuleEngines refusing = engines(EngineSettings.defaults().withRequireContentHash(false).withRetireLastVersion("refuse"));
        RuleSetVersion v1 = publish("bank-a", "tet-fee-waiver", tetFeeWaiver("2027-01-20T00:00:00Z"),
                Instant.parse("2027-01-15T00:00:00Z"));
        clock.set(Instant.parse("2027-02-17T08:00:00Z"));
        caller.become("bank-a", "bob", APPROVER);

        // Then even with the confirmation the last version in effect stays published
        assertThatThrownBy(() -> refusing.versionService().retire("tet-fee-waiver", 1,
                new WriteOptions(null, v1.lockVersion(), "Tet promotion ended"), true))
                .isInstanceOf(LastVersionInEffectException.class).hasMessageContaining("refuse");
        assertThat(retiredEvents()).isEmpty();
        // and four-eyes is accepted (RLE-038), while an unknown value stops start-up naming the three values
        assertThat(EngineSettings.defaults().withRetireLastVersion("four-eyes").retireLastVersion()).isEqualTo("four-eyes");
        assertThatThrownBy(() -> EngineSettings.defaults().withRetireLastVersion("ask"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("horus.bpm.lifecycle.retire-last-version")
                .hasMessageContaining("confirm, refuse or four-eyes");
        assertThatThrownBy(() -> refusing.versionService().retire("tet-fee-waiver", 1,
                new WriteOptions(null, v1.lockVersion(), "Tet promotion ended"), true))
                .isInstanceOfSatisfying(LastVersionInEffectException.class,
                        e -> assertThat(e.setting()).isEqualTo("refuse"));
        assertThat(EngineSettings.defaults().withRequireContentHash(false).retireLastVersion()).isEqualTo("confirm");
    }

    @Test
    void RLE_026_a_caller_whose_id_is_in_the_system_namespace_is_refused_for_every_operation() {
        // Given a published version, and a host user whose id is the supersession actor's
        RuleSetVersion v1 = publish("bank-a", "tet-fee-waiver", tetFeeWaiver("2027-01-20T00:00:00Z"),
                Instant.parse("2027-01-15T00:00:00Z"));
        clock.set(Instant.parse("2027-02-17T08:00:00Z"));
        int events = stores.outbox.size();
        int rows = stores.history.size();
        int decisions = stores.decisions.size();
        Set<String> everything = Set.of("rules:read", "rules:author", "rules:approve", "rules:evaluate");

        for (String id : new String[] {"system:supersession", "system:anything"}) {
            caller.become("bank-a", id, everything);
            // Then every command and read is 403 PERMISSION_DENIED, before anything is read or written
            List<org.assertj.core.api.ThrowableAssert.ThrowingCallable> calls = List.of(
                    () -> versions.retire("tet-fee-waiver", 1,
                            new WriteOptions(null, v1.lockVersion(), "Tet promotion ended"), true),
                    () -> versions.importVersion("tet-fee-waiver",
                            ImportRequest.yaml(tetFeeWaiver("2027-03-01T00:00:00Z"))),
                    () -> versions.find("tet-fee-waiver", 1, false),
                    () -> versions.history("tet-fee-waiver", 1),
                    () -> podA.ruleEngine().evaluate("tet-fee-waiver", DecisionInput.of(Map.of("channel", "CARD"))));
            for (var call : calls) {
                assertThatThrownBy(call).isInstanceOf(PermissionDeniedException.class)
                        .hasMessageContaining("reserved").hasMessageNotContaining(id);
            }
        }
        assertThat(stores.outbox).hasSize(events);
        assertThat(stores.history).hasSize(rows);
        assertThat(stores.decisions).hasSize(decisions);
        // and a user named plain system, outside the namespace, is an ordinary caller
        caller.become("bank-a", "system", APPROVER);
        assertThat(versions.find("tet-fee-waiver", 1, false)).isPresent();
    }

    // ------------------------------------------------------------------ RLE-027

    @Test
    void RLE_027_previous_version_retires_once_when_newer_becomes_effective() {
        // Given card-fee v1 from 1 Nov and v2 approved on 25 Nov with effectiveFrom 1 Dec
        publish("bank-a", "card-fee", cardFee("card-fee", "2026-11-01T00:00:00Z", "50000 VND"),
                Instant.parse("2026-10-20T00:00:00Z"));
        publish("bank-a", "card-fee", cardFee("card-fee", "2026-12-01T00:00:00Z", "30000 VND"),
                Instant.parse("2026-11-25T10:00:00Z"));

        // When the clock is 2026-11-30T23:59:59Z, then 2026-12-01T00:00:01Z, on both replicas
        clock.set(Instant.parse("2026-11-30T23:59:59Z"));
        assertThat(evaluate(podA, "card-fee", Map.of("tier", "STANDARD")).version()).isEqualTo(1);
        clock.set(Instant.parse("2026-12-01T00:00:01Z"));
        for (RuleEngines pod : List.of(podA, podB)) {
            assertThat(evaluate(pod, "card-fee", Map.of("tier", "STANDARD")).version()).isEqualTo(2);
        }
        // before the sweep: v2 is selected whether or not v1's status has changed yet
        caller.become("bank-a", "dan", Set.of("rules:read"));
        assertThat(versions.find("card-fee", 1, false).orElseThrow().status()).isEqualTo(VersionStatus.PUBLISHED);

        // When the sweep runs on both replicas at the same moment
        int a = podA.lifecycleSweep().run();
        int b = podB.lifecycleSweep().run();

        // Then v1 is RETIRED once: one event (actor system, SUPERSEDED, supersededBy 2) and one history row
        assertThat(a + b).isEqualTo(1);
        assertThat(retiredEvents()).singleElement().satisfies(event -> {
            assertThat(event.path("version").asInt()).isEqualTo(1);
            assertThat(event.path("retiredBy").asString()).isEqualTo("system:supersession");
            assertThat(event.path("reason").asString()).isEqualTo("SUPERSEDED");
            assertThat(event.path("supersededBy").asInt()).isEqualTo(2);
            assertThat(event.path("approvalMode").asString()).isEqualTo("system");
            assertThat(event.path("retiredAt").asString()).isEqualTo("2026-12-01T00:00:01Z");
            LifecycleEventPayloadTest.conformsTo("rules.version.retired.v1.schema.json", event);
        });
        assertThat(history("bank-a", "card-fee", 1)).filteredOn(e -> e.action() == LifecycleAction.RETIRED)
                .singleElement().satisfies(row -> {
                    assertThat(row.actor()).isEqualTo("system:supersession");
                    assertThat(row.supersededBy()).isEqualTo(2);
                });
        // and an evaluation with a clock of 20 Nov still selects v1
        clock.set(Instant.parse("2026-11-20T10:00:00Z"));
        assertThat(evaluate(podA, "card-fee", Map.of("tier", "STANDARD")).version()).isEqualTo(1);
    }

    @Test
    void RLE_027_a_version_effective_at_approval_retires_the_previous_one_in_the_same_transaction() {
        // Given fx-margin v3 published (v1 and v2 already superseded) and v4 effective at its approval
        for (int i = 1; i <= 3; i++) {
            publish("bank-a", "fx-margin", cardFee("fx-margin", null, (1000 * i) + " VND"),
                    Instant.parse("2026-11-0" + i + "T00:00:00Z"));
        }
        int before = retiredEvents().size();

        // When minh approves v4 at 2026-11-20T10:00:00Z
        publish("bank-a", "fx-margin", cardFee("fx-margin", null, "4000 VND"), Instant.parse("2026-11-20T10:00:00Z"));

        // Then v3 retires in that transaction with one event, superseded by v4, and the sweep finds nothing left
        assertThat(retiredEvents().subList(before, retiredEvents().size())).singleElement()
                .satisfies(e -> assertThat(e.path("version").asInt()).isEqualTo(3))
                .satisfies(e -> assertThat(e.path("supersededBy").asInt()).isEqualTo(4));
        assertThat(podA.lifecycleSweep().run()).isZero();
        caller.become("bank-a", "dan", Set.of("rules:read"));
        for (int v = 1; v <= 3; v++) {
            assertThat(versions.find("fx-margin", v, false).orElseThrow().status()).isEqualTo(VersionStatus.RETIRED);
        }
    }

    @Test
    void RLE_027_the_sweep_covers_every_tenant_and_records_each_in_its_own() {
        // Given card-fee superseded in bank-a and in bank-b, with the next versions scheduled for 1 Dec
        for (String tenant : List.of("bank-a", "bank-b")) {
            publish(tenant, "card-fee", cardFee("card-fee", "2026-11-01T00:00:00Z", "50000 VND"),
                    Instant.parse("2026-10-20T00:00:00Z"));
            publish(tenant, "card-fee", cardFee("card-fee", "2026-12-01T00:00:00Z", "30000 VND"),
                    Instant.parse("2026-11-25T10:00:00Z"));
        }

        // When one sweep runs on 1 Dec
        clock.set(Instant.parse("2026-12-01T00:00:00Z"));
        assertThat(podA.lifecycleSweep().run()).isEqualTo(2);

        // Then each tenant gets its own retired event for its own v1
        assertThat(retiredEvents()).extracting(e -> e.path("tenantId").asString())
                .containsExactlyInAnyOrder("bank-a", "bank-b");
        assertThat(history("bank-b", "card-fee", 1).getLast().action()).isEqualTo(LifecycleAction.RETIRED);
    }

    @Test
    void RLE_027_the_system_actor_is_configurable() {
        RuleEngines pod = engines(EngineSettings.defaults().withRequireContentHash(false).withLifecycle("system:pricing-sweeper", "single"));
        publish("bank-a", "card-fee", cardFee("card-fee", "2026-11-01T00:00:00Z", "50000 VND"),
                Instant.parse("2026-10-20T00:00:00Z"));
        publish("bank-a", "card-fee", cardFee("card-fee", "2026-12-01T00:00:00Z", "30000 VND"),
                Instant.parse("2026-11-25T10:00:00Z"));
        clock.set(Instant.parse("2026-12-01T00:00:00Z").plus(Duration.ofMinutes(1)));

        assertThat(pod.lifecycleSweep().run()).isEqualTo(1);
        assertThat(retiredEvents().getFirst().path("retiredBy").asString()).isEqualTo("system:pricing-sweeper");
        assertThat(history("bank-a", "card-fee", 1).getLast().actor()).isEqualTo("system:pricing-sweeper");
    }

    @Test
    void RLE_026_lifecycle_settings_are_validated_at_start_up() {
        assertThat(EngineSettings.defaults().withLifecycle("system:supersession", "four-eyes").retireApproval())
                .isEqualTo("four-eyes");
        assertThatThrownBy(() -> EngineSettings.defaults().withLifecycle("system:supersession", "dual"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("horus.bpm.lifecycle.retire-approval").hasMessageContaining("must be single or four-eyes");
        assertThatThrownBy(() -> EngineSettings.defaults().withLifecycle(" ", "single"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("horus.bpm.rules.lifecycle.system-actor");
        assertThat(EngineSettings.defaults().withRequireContentHash(false).retireApproval()).isEqualTo("single");
        assertThat(EngineSettings.defaults().withRequireContentHash(false).systemActor()).isEqualTo("system:supersession");
        // the actor lives in the reserved system: namespace, which no caller id may use
        for (String actor : new String[] {"system", "sweeper", "system:", "SYSTEM:x"}) {
            assertThatThrownBy(() -> EngineSettings.defaults().withRequireContentHash(false).withLifecycle(actor, "single"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("horus.bpm.rules.lifecycle.system-actor must start with system:");
        }
    }
}
