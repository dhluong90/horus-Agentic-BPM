package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.InvalidStateException;
import io.github.dhluong90.bpm.rules.api.LastVersionInEffectException;
import io.github.dhluong90.bpm.rules.api.LifecycleAction;
import io.github.dhluong90.bpm.rules.api.NoEffectiveVersionException;
import io.github.dhluong90.bpm.rules.api.PermissionDeniedException;
import io.github.dhluong90.bpm.rules.api.PreconditionRequiredException;
import io.github.dhluong90.bpm.rules.api.RetirementRequest;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.SelfApprovalForbiddenException;
import io.github.dhluong90.bpm.rules.api.VersionActions;
import io.github.dhluong90.bpm.rules.api.VersionConflictException;
import io.github.dhluong90.bpm.rules.api.VersionHistoryEntry;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * RLE-038 (#213): with {@code lifecycle.retire-approval: four-eyes} a manual retirement is a request, the version
 * keeps serving until a second approver confirms, and the requester never confirms their own request. Users of
 * {@code bank-a}: lan authors, bob and carol approve, minh approves publications, teller evaluates. Two engines over
 * one store stand in for two replicas.
 */
class FourEyesRetirementTest {

    static final Set<String> AUTHOR = Set.of("rules:read", "rules:author");
    static final Set<String> APPROVER = Set.of("rules:read", "rules:approve");
    static final Set<String> TELLER = Set.of("rules:evaluate");
    static final String REASON = "Tet promotion ended";

    final EditorApiTest.MovableClock clock = new EditorApiTest.MovableClock(Instant.parse("2027-01-10T00:00:00Z"));
    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "lan");
    final EngineSettings fourEyes = EngineSettings.defaults().withLifecycle("system:supersession", "four-eyes");
    final RuleEngines podA = engines(fourEyes);
    final RuleEngines podB = engines(fourEyes);
    final VersionService versions = podA.versionService();

    RuleEngines engines(EngineSettings settings) {
        return RuleEngines.create(new EngineContext(caller, caller, clock, stores, stores, settings), stores, stores,
                stores);
    }

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

    /** Imports, requests (lan) and approves (minh) the next version at {@code approvedAt}; returns it published. */
    RuleSetVersion publish(VersionService service, byte[] document, Instant approvedAt) {
        caller.become("bank-a", "lan", AUTHOR);
        int number = service.findRuleSet("tet-fee-waiver").isPresent()
                ? service.importVersion("tet-fee-waiver", ImportRequest.yaml(document)).version()
                : service.importRuleSet(ImportRequest.yaml(document)).version();
        long reviewed = service.requestPublish("tet-fee-waiver", number, WriteOptions.none()).lockVersion();
        clock.set(approvedAt);
        caller.become("bank-a", "minh", APPROVER);
        return service.approve("tet-fee-waiver", number, new WriteOptions(null, reviewed, null));
    }

    DecisionResult evaluate(RuleEngines pod) {
        caller.become("bank-a", "teller", TELLER);
        return pod.ruleEngine().evaluate("tet-fee-waiver", DecisionInput.of(Map.of("channel", "CARD")));
    }

    RuleSetVersion read(int version) {
        caller.become("bank-a", "dan", Set.of("rules:read"));
        return versions.find("tet-fee-waiver", version, false).orElseThrow();
    }

    List<VersionHistoryEntry> history(int version) {
        caller.become("bank-a", "dan", Set.of("rules:read"));
        return versions.history("tet-fee-waiver", version);
    }

    List<JsonNode> events(String type) {
        return stores.outbox.stream().map(row -> EngineJson.JSON.readTree(row.payload()))
                .filter(e -> e.path("eventType").asString().equals(type)).toList();
    }

    /** What a refused command must leave alone: the outbox, the history and the version. */
    String snapshot(int version) {
        return stores.outbox.size() + "/" + history(version).size() + "/" + read(version);
    }

    @Test
    void RLE_038_four_eyes_retirement_needs_second_approver() {
        // Given four-eyes; tet-fee-waiver v1 PUBLISHED, the only version, in effect since 20 Jan 2027
        RuleSetVersion v1 = publish(versions, tetFeeWaiver("2027-01-20T00:00:00Z"), Instant.parse("2027-01-15T00:00:00Z"));
        clock.set(Instant.parse("2027-02-17T08:00:00Z"));

        // When bob asks to retire it, confirming that no version will remain in effect (PD-101)
        caller.become("bank-a", "bob", APPROVER);
        RuleSetVersion requested = versions.retire("tet-fee-waiver", 1, new WriteOptions("k-401", v1.lockVersion(), REASON),
                true);

        // Then v1 is still PUBLISHED with a pending retirement by bob, one RETIRE_REQUESTED row and one event
        assertThat(requested.status()).isEqualTo(VersionStatus.PUBLISHED);
        assertThat(requested.retiredAt()).isNull();
        assertThat(requested.lockVersion()).isEqualTo(v1.lockVersion() + 1);
        assertThat(requested.retirementRequest())
                .isEqualTo(new RetirementRequest("bob", Instant.parse("2027-02-17T08:00:00Z"), REASON, true));
        assertThat(history(1).getLast()).isEqualTo(new VersionHistoryEntry(LifecycleAction.RETIRE_REQUESTED,
                VersionStatus.PUBLISHED, VersionStatus.PUBLISHED, "bob", REASON, requested.lockVersion(),
                Instant.parse("2027-02-17T08:00:00Z")));
        assertThat(events("rules.version.retire-requested.v1")).singleElement().satisfies(event -> {
            assertThat(event.path("requestedBy").asString()).isEqualTo("bob");
            assertThat(event.path("actor").asString()).isEqualTo("bob");
            assertThat(event.path("reason").asString()).isEqualTo(REASON);
            assertThat(event.path("version").asInt()).isEqualTo(1);
            LifecycleEventPayloadTest.conformsTo("rules.version.retire-requested.v1.schema.json", event);
        });
        assertThat(events("rules.version.retired.v1")).isEmpty();
        // and both replicas still select v1
        for (RuleEngines pod : List.of(podA, podB)) {
            assertThat(evaluate(pod).version()).isEqualTo(1);
        }
        // and a replay of k-401 returns the same request and writes nothing more
        caller.become("bank-a", "bob", APPROVER);
        assertThat(versions.retire("tet-fee-waiver", 1, new WriteOptions("k-401", v1.lockVersion(), REASON), true))
                .isEqualTo(requested);
        assertThat(events("rules.version.retire-requested.v1")).hasSize(1);

        // When bob confirms his own request, Then 403 SELF_APPROVAL_FORBIDDEN and nothing changes
        String before = snapshot(1);
        caller.become("bank-a", "bob", APPROVER);
        assertThatThrownBy(() -> versions.confirmRetire("tet-fee-waiver", 1,
                new WriteOptions("k-402", requested.lockVersion(), null), false))
                .isInstanceOf(SelfApprovalForbiddenException.class)
                .extracting(e -> ((SelfApprovalForbiddenException) e).code()).isEqualTo("SELF_APPROVAL_FORBIDDEN");
        // and a second request while one is pending is 409 INVALID_STATE
        caller.become("bank-a", "carol", APPROVER);
        assertThatThrownBy(() -> versions.retire("tet-fee-waiver", 1,
                new WriteOptions("k-403", requested.lockVersion(), "Also ended"), true))
                .isInstanceOf(InvalidStateException.class).hasMessageContaining("requested by bob is pending");
        // and a confirmation without the lock version is 428, with a stale one 409 VERSION_CONFLICT
        assertThatThrownBy(() -> versions.confirmRetire("tet-fee-waiver", 1, new WriteOptions("k-404", null, null), false))
                .isInstanceOf(PreconditionRequiredException.class);
        assertThatThrownBy(() -> versions.confirmRetire("tet-fee-waiver", 1,
                new WriteOptions("k-405", v1.lockVersion(), null), false))
                .isInstanceOf(VersionConflictException.class);
        // and an author without rules:approve may not confirm
        caller.become("bank-a", "lan", AUTHOR);
        assertThatThrownBy(() -> versions.confirmRetire("tet-fee-waiver", 1,
                new WriteOptions("k-406", requested.lockVersion(), null), false))
                .isInstanceOf(PermissionDeniedException.class);
        assertThat(snapshot(1)).isEqualTo(before);

        // When carol confirms
        clock.set(Instant.parse("2027-02-17T09:30:00Z"));
        caller.become("bank-a", "carol", APPROVER);
        WriteOptions confirm = new WriteOptions("k-407", requested.lockVersion(), null);
        RuleSetVersion retired = versions.confirmRetire("tet-fee-waiver", 1, confirm, false);

        // Then v1 is RETIRED from the confirmation, with the requester, confirmer, reason and mode four-eyes recorded
        assertThat(retired.status()).isEqualTo(VersionStatus.RETIRED);
        assertThat(retired.retiredAt()).isEqualTo(Instant.parse("2027-02-17T09:30:00Z"));
        assertThat(retired.retirementRequest()).isNull();
        assertThat(history(1).getLast()).isEqualTo(new VersionHistoryEntry(LifecycleAction.RETIRED,
                VersionStatus.PUBLISHED, VersionStatus.RETIRED, "carol", REASON, retired.lockVersion(),
                Instant.parse("2027-02-17T09:30:00Z"), null, "four-eyes", true, null, null, List.of(), List.of(), "bob"));
        assertThat(events("rules.version.retired.v1")).singleElement().satisfies(event -> {
            assertThat(event.path("retiredBy").asString()).isEqualTo("carol");
            assertThat(event.path("requestedBy").asString()).isEqualTo("bob");
            assertThat(event.path("reason").asString()).isEqualTo(REASON);
            assertThat(event.path("approvalMode").asString()).isEqualTo("four-eyes");
            assertThat(event.path("noVersionInEffect").asBoolean()).isTrue();
            LifecycleEventPayloadTest.conformsTo("rules.version.retired.v1.schema.json", event);
        });
        // and every replica answers 404 NO_EFFECTIVE_VERSION once its epoch check has run (ADR-0009)
        podB.cache().checkEpochs();
        for (RuleEngines pod : List.of(podA, podB)) {
            assertThatThrownBy(() -> evaluate(pod)).isInstanceOf(NoEffectiveVersionException.class);
        }
        // and a replay of k-407 returns the same version with no second event
        caller.become("bank-a", "carol", APPROVER);
        assertThat(versions.confirmRetire("tet-fee-waiver", 1, confirm, false)).isEqualTo(retired);
        assertThat(events("rules.version.retired.v1")).hasSize(1);
        // and a confirmation with no pending request is 409 INVALID_STATE
        assertThatThrownBy(() -> versions.confirmRetire("tet-fee-waiver", 1,
                new WriteOptions("k-408", retired.lockVersion(), null), false))
                .isInstanceOf(InvalidStateException.class).hasMessageContaining("no retirement is pending");
    }

    @Test
    void RLE_038_a_request_with_an_empty_reason_or_for_the_last_version_unconfirmed_is_refused() {
        RuleSetVersion v1 = publish(versions, tetFeeWaiver("2027-01-20T00:00:00Z"), Instant.parse("2027-01-15T00:00:00Z"));
        clock.set(Instant.parse("2027-02-17T08:00:00Z"));
        String before = snapshot(1);
        caller.become("bank-a", "bob", APPROVER);

        assertThatThrownBy(() -> versions.retire("tet-fee-waiver", 1, new WriteOptions("k-1", v1.lockVersion(), ""), true))
                .isInstanceOf(InputInvalidException.class);
        assertThatThrownBy(() -> versions.retire("tet-fee-waiver", 1, new WriteOptions("k-2", v1.lockVersion(), " "), true))
                .isInstanceOf(InputInvalidException.class);
        // PD-101 applies to the request: v1 is the last version in effect and the request does not confirm it
        assertThatThrownBy(() -> versions.retire("tet-fee-waiver", 1, new WriteOptions("k-3", v1.lockVersion(), REASON)))
                .isInstanceOf(LastVersionInEffectException.class);

        assertThat(snapshot(1)).isEqualTo(before);
        assertThat(read(1).retirementRequest()).isNull();
    }

    @Test
    void RLE_038_the_last_version_rule_is_checked_again_at_confirmation() {
        // Given v1 in effect and v2 approved to take effect on 1 Mar 2027
        publish(versions, tetFeeWaiver("2027-01-20T00:00:00Z"), Instant.parse("2027-01-15T00:00:00Z"));
        RuleSetVersion v2 = publish(versions, tetFeeWaiver("2027-03-01T00:00:00Z"), Instant.parse("2027-02-10T00:00:00Z"));
        // When bob asks to retire v2 before it starts: v1 still serves, so no confirmation is needed
        clock.set(Instant.parse("2027-02-20T00:00:00Z"));
        caller.become("bank-a", "bob", APPROVER);
        RuleSetVersion requested = versions.retire("tet-fee-waiver", 2, new WriteOptions("k-1", v2.lockVersion(), REASON));
        assertThat(requested.retirementRequest().confirmNoVersionInEffect()).isFalse();

        // and carol confirms on 2 Mar, when v2 has taken effect and is now the last version in effect
        clock.set(Instant.parse("2027-03-02T00:00:00Z"));
        caller.become("bank-a", "carol", APPROVER);
        assertThatThrownBy(() -> versions.confirmRetire("tet-fee-waiver", 2,
                new WriteOptions("k-2", requested.lockVersion(), null), false))
                .isInstanceOf(LastVersionInEffectException.class);
        assertThat(read(2).status()).isEqualTo(VersionStatus.PUBLISHED);
        caller.become("bank-a", "carol", APPROVER);

        // Then with her own confirmation it retires, and v1, superseded by v2, retires first as SUPERSEDED
        RuleSetVersion retired = versions.confirmRetire("tet-fee-waiver", 2,
                new WriteOptions("k-3", requested.lockVersion(), null), true);
        assertThat(retired.status()).isEqualTo(VersionStatus.RETIRED);
        assertThat(read(1).status()).isEqualTo(VersionStatus.RETIRED);
        assertThat(history(1).getLast().reason()).isEqualTo("SUPERSEDED");
        assertThat(history(2).getLast().noVersionInEffect()).isTrue();
        assertThatThrownBy(() -> evaluate(podA)).isInstanceOf(NoEffectiveVersionException.class);
    }

    @Test
    void RLE_038_any_approver_cancels_a_pending_request_with_a_reason() {
        RuleSetVersion v1 = publish(versions, tetFeeWaiver("2027-01-20T00:00:00Z"), Instant.parse("2027-01-15T00:00:00Z"));
        clock.set(Instant.parse("2027-02-17T08:00:00Z"));
        caller.become("bank-a", "bob", APPROVER);
        RuleSetVersion requested = versions.retire("tet-fee-waiver", 1, new WriteOptions("k-1", v1.lockVersion(), REASON),
                true);
        String before = snapshot(1);
        caller.become("bank-a", "bob", APPROVER);

        // a cancellation needs a reason and the lock version it was decided on
        assertThatThrownBy(() -> versions.cancelRetire("tet-fee-waiver", 1,
                new WriteOptions("k-2", requested.lockVersion(), " "))).isInstanceOf(InputInvalidException.class);
        assertThatThrownBy(() -> versions.cancelRetire("tet-fee-waiver", 1, new WriteOptions("k-3", null, "Extended")))
                .isInstanceOf(PreconditionRequiredException.class);
        assertThatThrownBy(() -> versions.cancelRetire("tet-fee-waiver", 1,
                new WriteOptions("k-4", v1.lockVersion(), "Extended"))).isInstanceOf(VersionConflictException.class);
        assertThat(snapshot(1)).isEqualTo(before);
        caller.become("bank-a", "bob", APPROVER);

        // When bob, the requester, cancels it, Then v1 stays PUBLISHED and serving with no pending request
        RuleSetVersion cancelled = versions.cancelRetire("tet-fee-waiver", 1,
                new WriteOptions("k-5", requested.lockVersion(), "Promotion extended to March"));
        assertThat(cancelled.status()).isEqualTo(VersionStatus.PUBLISHED);
        assertThat(cancelled.retirementRequest()).isNull();
        assertThat(cancelled.lockVersion()).isEqualTo(requested.lockVersion() + 1);
        assertThat(history(1).getLast()).isEqualTo(new VersionHistoryEntry(LifecycleAction.RETIRE_CANCELLED,
                VersionStatus.PUBLISHED, VersionStatus.PUBLISHED, "bob", "Promotion extended to March",
                cancelled.lockVersion(), Instant.parse("2027-02-17T08:00:00Z")));
        assertThat(evaluate(podA).version()).isEqualTo(1);
        // and nothing is pending any more: a confirmation or a second cancellation is 409 INVALID_STATE
        caller.become("bank-a", "carol", APPROVER);
        assertThatThrownBy(() -> versions.confirmRetire("tet-fee-waiver", 1,
                new WriteOptions("k-6", cancelled.lockVersion(), null), true)).isInstanceOf(InvalidStateException.class);
        assertThatThrownBy(() -> versions.cancelRetire("tet-fee-waiver", 1,
                new WriteOptions("k-7", cancelled.lockVersion(), "Again"))).isInstanceOf(InvalidStateException.class);
        // and a new request can be made
        assertThat(versions.retire("tet-fee-waiver", 1, new WriteOptions("k-8", cancelled.lockVersion(), REASON), true)
                .retirementRequest().requestedBy()).isEqualTo("carol");
    }

    @Test
    void RLE_038_a_supersession_ends_a_pending_request() {
        RuleSetVersion v1 = publish(versions, tetFeeWaiver("2027-01-20T00:00:00Z"), Instant.parse("2027-01-15T00:00:00Z"));
        clock.set(Instant.parse("2027-02-17T08:00:00Z"));
        caller.become("bank-a", "bob", APPROVER);
        versions.retire("tet-fee-waiver", 1, new WriteOptions("k-1", v1.lockVersion(), REASON), true);

        // When v2 is approved and takes effect at once, v1 retires as superseded and its request ends with it
        publish(versions, tetFeeWaiver("2027-02-18T00:00:00Z"), Instant.parse("2027-02-18T00:00:00Z"));
        RuleSetVersion old = read(1);
        assertThat(old.status()).isEqualTo(VersionStatus.RETIRED);
        assertThat(old.retirementRequest()).isNull();
        assertThat(history(1).getLast().approvalMode()).isEqualTo("system");
        caller.become("bank-a", "carol", APPROVER);
        assertThatThrownBy(() -> versions.confirmRetire("tet-fee-waiver", 1,
                new WriteOptions("k-2", old.lockVersion(), null), true)).isInstanceOf(InvalidStateException.class);
    }

    @Test
    void RLE_038_in_single_mode_the_last_version_needs_a_second_approver_when_the_host_says_so() {
        // Given retire-approval single and retire-last-version four-eyes (PD-101 option 2)
        RuleEngines pod = engines(EngineSettings.defaults().withRetireLastVersion("four-eyes"));
        VersionService service = pod.versionService();
        publish(service, tetFeeWaiver("2027-01-20T00:00:00Z"), Instant.parse("2027-01-15T00:00:00Z"));
        RuleSetVersion v2 = publish(service, tetFeeWaiver("2027-03-01T00:00:00Z"), Instant.parse("2027-02-10T00:00:00Z"));

        // When bob retires v2, which has not started while v1 serves, it retires at once in single mode
        clock.set(Instant.parse("2027-02-20T00:00:00Z"));
        caller.become("bank-a", "bob", APPROVER);
        RuleSetVersion retired = service.retire("tet-fee-waiver", 2, new WriteOptions("k-1", v2.lockVersion(), REASON));
        assertThat(retired.status()).isEqualTo(VersionStatus.RETIRED);
        assertThat(history(2).getLast().approvalMode()).isEqualTo("single");

        // When he retires v1, the last version in effect, it becomes a request with no confirmation needed
        long lock = read(1).lockVersion();
        caller.become("bank-a", "bob", APPROVER);
        RuleSetVersion requested = service.retire("tet-fee-waiver", 1, new WriteOptions("k-2", lock, REASON));
        assertThat(requested.status()).isEqualTo(VersionStatus.PUBLISHED);
        assertThat(requested.retirementRequest().requestedBy()).isEqualTo("bob");
        // and carol's confirmation retires it in four-eyes mode
        caller.become("bank-a", "carol", APPROVER);
        RuleSetVersion confirmed = service.confirmRetire("tet-fee-waiver", 1,
                new WriteOptions("k-3", requested.lockVersion(), null), false);
        assertThat(confirmed.status()).isEqualTo(VersionStatus.RETIRED);
        assertThat(history(1).getLast().approvalMode()).isEqualTo("four-eyes");
        assertThat(history(1).getLast().retireRequestedBy()).isEqualTo("bob");
    }

    @Test
    void RLE_038_a_host_that_refuses_the_last_version_refuses_its_request_too() {
        RuleEngines pod = engines(fourEyes.withRetireLastVersion("refuse"));
        RuleSetVersion v1 = publish(pod.versionService(), tetFeeWaiver("2027-01-20T00:00:00Z"),
                Instant.parse("2027-01-15T00:00:00Z"));
        clock.set(Instant.parse("2027-02-17T08:00:00Z"));
        caller.become("bank-a", "bob", APPROVER);
        assertThatThrownBy(() -> pod.versionService().retire("tet-fee-waiver", 1,
                new WriteOptions("k-1", v1.lockVersion(), REASON), true))
                .isInstanceOf(LastVersionInEffectException.class).hasMessageContaining("refuse");
        assertThat(events("rules.version.retire-requested.v1")).isEmpty();
    }

    @Test
    void RLE_038_allowed_actions_offer_confirm_to_a_second_approver_only() {
        Set<String> approver = Set.of("rules:approve");
        assertThat(VersionActions.allowed(VersionStatus.PUBLISHED, "lan", "lan", null, "bob", approver))
                .containsExactly("retire");
        assertThat(VersionActions.allowed(VersionStatus.PUBLISHED, "lan", "lan", "bob", "bob", approver))
                .containsExactly("cancel-retire");
        assertThat(VersionActions.allowed(VersionStatus.PUBLISHED, "lan", "lan", "bob", "carol", approver))
                .containsExactly("confirm-retire", "cancel-retire");
        assertThat(VersionActions.allowed(VersionStatus.PUBLISHED, "lan", "lan", "bob", "dan", Set.of("rules:read")))
                .isEmpty();
    }
}
