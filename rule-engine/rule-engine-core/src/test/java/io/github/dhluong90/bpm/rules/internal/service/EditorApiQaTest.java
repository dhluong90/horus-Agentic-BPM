package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import io.github.dhluong90.bpm.rules.api.HorusBpmException;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.LifecycleAction;
import io.github.dhluong90.bpm.rules.api.Page;
import io.github.dhluong90.bpm.rules.api.PageRequest;
import io.github.dhluong90.bpm.rules.api.QueryInvalidException;
import io.github.dhluong90.bpm.rules.api.RuleSetSummary;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.api.VersionActions;
import io.github.dhluong90.bpm.rules.api.VersionConflictException;
import io.github.dhluong90.bpm.rules.api.VersionHistoryEntry;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.api.Violation;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import io.github.dhluong90.bpm.testing.property.Gen;
import io.github.dhluong90.bpm.testing.property.Property;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** QA review of #147: properties and edge cases on top of EditorApiTest, on the in-memory stores. */
class EditorApiQaTest {

    static final Instant DEC_01 = Instant.parse("2026-12-01T00:00:00Z");
    static final String READ = "rules:read";
    static final String AUTHOR = "rules:author";
    static final String APPROVE = "rules:approve";

    /** A fresh engine with one card-fee version in the given state. */
    static final class World {
        final EditorApiTest.MovableClock clock = new EditorApiTest.MovableClock(DEC_01);
        final InMemoryStores stores = new InMemoryStores();
        final TestCaller caller = new TestCaller("bank-a", "lan");
        final RuleEngines engines = RuleEngines.create(
                new EngineContext(caller, caller, clock, stores, stores, EngineSettings.defaults()),
                stores, stores, stores);
        final VersionService versions = engines.versionService();

        {
            engines.cursorKeys().load();   // as the starter does at start-up
        }

        RuleSetVersion build(VersionStatus status, String author, String requester) {
            caller.become("bank-a", author, Set.of(READ, AUTHOR));
            RuleSetVersion v = versions.importRuleSet(ImportRequest.yaml(CardFee.yaml()));
            if (status == VersionStatus.DRAFT) {
                return v;
            }
            caller.become("bank-a", requester, Set.of(READ, AUTHOR));
            v = versions.requestPublish("card-fee", 1, WriteOptions.none());
            if (status == VersionStatus.PENDING_APPROVAL) {
                return v;
            }
            caller.become("bank-a", "zed", Set.of(READ, APPROVE));
            return versions.approve("card-fee", 1, WriteOptions.none());
        }
    }

    /** Author, requester and caller of one try. */
    record Actors(String author, String requester, String caller) {
    }

    static final Gen<VersionStatus> STATUS =
            Gen.of(VersionStatus.DRAFT, VersionStatus.PENDING_APPROVAL, VersionStatus.PUBLISHED);

    /** Every author, requester and caller among lan, minh and dan: 27 triples. */
    static final Gen<Actors> ACTORS = actors();

    /** Every subset of rules:author and rules:approve, each with rules:read. */
    static final Gen<Set<String>> PERMISSIONS =
            Gen.of(Set.of(READ), Set.of(READ, AUTHOR), Set.of(READ, APPROVE), Set.of(READ, AUTHOR, APPROVE));

    static Gen<Actors> actors() {
        List<Actors> all = new ArrayList<>();
        for (String author : List.of("lan", "minh", "dan")) {
            for (String requester : List.of("lan", "minh", "dan")) {
                for (String caller : List.of("lan", "minh", "dan")) {
                    all.add(new Actors(author, requester, caller));
                }
            }
        }
        return Gen.of(all.toArray(Actors[]::new));
    }

    /** 3 statuses x 27 actor triples x 4 permission sets = 324 combinations, all walked within 400 tries. */
    @TestFactory
    Stream<DynamicTest> RLE_043_allowed_actions_match_what_each_command_accepts() {
        return Property.tries(400).forAll(STATUS, ACTORS, PERMISSIONS, (status, actors, permissions) ->
                allowedActionsMatchCommands(status, actors.author(), actors.requester(), actors.caller(), permissions));
    }

    static void allowedActionsMatchCommands(VersionStatus status, String author, String requester, String caller,
            Set<String> permissions) {
        // Given the version in that state, read by the caller
        World probe = new World();
        RuleSetVersion v = probe.build(status, author, requester);
        List<String> allowed = VersionActions.allowed(v.status(), v.createdBy(), v.requestedBy(), caller, permissions);

        // When each command is tried on a fresh copy of the same state
        List<String> accepted = new ArrayList<>();
        for (String action : List.of(VersionActions.REQUEST_PUBLISH, VersionActions.APPROVE, VersionActions.REJECT,
                VersionActions.WITHDRAW)) {
            World world = new World();
            world.build(status, author, requester);
            world.caller.become("bank-a", caller, permissions);
            try {
                switch (action) {
                    case VersionActions.REQUEST_PUBLISH -> world.versions.requestPublish("card-fee", 1, WriteOptions.none());
                    case VersionActions.APPROVE -> world.versions.approve("card-fee", 1, WriteOptions.none());
                    case VersionActions.REJECT -> world.versions.reject("card-fee", 1, new WriteOptions(null, null, "qa"));
                    default -> world.versions.withdraw("card-fee", 1, WriteOptions.none());
                }
                accepted.add(action);
            } catch (HorusBpmException refused) {
                // refused: not accepted
            }
        }

        // Then the hint and the commands agree
        assertThat(allowed).as("%s author=%s requester=%s caller=%s perms=%s", status, author, requester, caller,
                permissions).isEqualTo(accepted);
    }

    // ---------------------------------------------------------------- RLE-039 cursor bounds

    @Test
    void RLE_039_a_cursor_that_was_not_issued_by_the_endpoint_is_query_invalid() {
        // Given three rule sets
        World w = new World();
        w.caller.become("bank-a", "lan", Set.of(READ, AUTHOR));
        for (String name : List.of("card-fee", "fx-margin", "kyc-risk")) {
            w.versions.importRuleSet(ImportRequest.yaml(EditorApiTest.cardFee("10000 VND", name)));
        }

        // When a caller forges a well-formed cursor the endpoint never issued (no such rule set)
        String forged = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("rule-sets:d-not-a-rule-set".getBytes(StandardCharsets.UTF_8));

        // Then SPEC RLE-039: a cursor that was not issued by the endpoint returns 400 QUERY_INVALID
        assertThatThrownBy(() -> w.versions.listRuleSets(new PageRequest(2, forged)))
                .isInstanceOf(QueryInvalidException.class);
    }

    @Test
    void RLE_039_limit_bounds_are_one_and_the_maximum_inclusive() {
        World w = new World();
        w.caller.become("bank-a", "lan", Set.of(READ, AUTHOR));
        w.versions.importRuleSet(ImportRequest.yaml(CardFee.yaml()));

        assertThat(w.versions.listRuleSets(new PageRequest(1, null)).items()).hasSize(1);
        assertThat(w.versions.listRuleSets(new PageRequest(200, null)).items()).hasSize(1);
        for (int bad : new int[] {0, -1, 201, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            assertThatThrownBy(() -> w.versions.listRuleSets(new PageRequest(bad, null))).as("limit %d", bad)
                    .isInstanceOf(QueryInvalidException.class);
            assertThatThrownBy(() -> w.versions.listVersions("card-fee", new PageRequest(bad, null))).as("limit %d", bad)
                    .isInstanceOf(QueryInvalidException.class);
        }
    }

    @Test
    void RLE_039_paging_under_concurrent_inserts_never_repeats_or_skips_an_existing_rule_set() {
        // Given b, d, f
        World w = new World();
        w.caller.become("bank-a", "lan", Set.of(READ, AUTHOR));
        for (String name : List.of("b-set", "d-set", "f-set")) {
            w.versions.importRuleSet(ImportRequest.yaml(EditorApiTest.cardFee("10000 VND", name)));
        }
        Page<RuleSetSummary> first = w.versions.listRuleSets(new PageRequest(2, null));

        // When a, c and e are created between the two pages
        for (String name : List.of("a-set", "c-set", "e-set")) {
            w.versions.importRuleSet(ImportRequest.yaml(EditorApiTest.cardFee("10000 VND", name)));
        }
        Page<RuleSetSummary> second = w.versions.listRuleSets(new PageRequest(2, first.nextCursor()));

        // Then the second page continues after d-set: e-set (new, after the cursor) and f-set (existing)
        assertThat(first.items()).extracting(RuleSetSummary::ruleSet).containsExactly("b-set", "d-set");
        assertThat(second.items()).extracting(RuleSetSummary::ruleSet).containsExactly("e-set", "f-set");
        assertThat(second.nextCursor()).isNull();
    }

    // ---------------------------------------------------------------- RLE-025 reject, If-Match, history

    @Test
    void RLE_025_a_retry_with_the_same_key_after_success_replays_even_though_the_lock_moved_on() {
        World w = new World();
        RuleSetVersion pending = w.build(VersionStatus.PENDING_APPROVAL, "lan", "lan");
        w.caller.become("bank-a", "minh", Set.of(READ, APPROVE));
        WriteOptions reject = new WriteOptions("k-qa-1", pending.lockVersion(), "R2 waiver needs pricing committee minutes");
        RuleSetVersion first = w.versions.reject("card-fee", 1, reject);
        int events = w.stores.outbox.size();

        // When the client times out and retries the identical request
        RuleSetVersion replay = w.versions.reject("card-fee", 1, reject);

        // Then a replay, not 409 VERSION_CONFLICT, and no second effect
        assertThat(replay).isEqualTo(first);
        assertThat(w.stores.outbox).hasSize(events);

        // And the same If-Match with a new key is a stale write
        assertThatThrownBy(() -> w.versions.reject("card-fee", 1,
                new WriteOptions("k-qa-2", pending.lockVersion(), "again")))
                .isInstanceOf(io.github.dhluong90.bpm.rules.api.InvalidStateException.class);
    }

    @Test
    void RLE_025_stale_if_match_on_a_pending_version_is_version_conflict_and_writes_nothing() {
        World w = new World();
        RuleSetVersion pending = w.build(VersionStatus.PENDING_APPROVAL, "lan", "lan");
        w.caller.become("bank-a", "minh", Set.of(READ, APPROVE));
        int events = w.stores.outbox.size();
        int history = w.stores.history.size();

        for (long stale : new long[] {pending.lockVersion() - 1, pending.lockVersion() + 1, 0}) {
            assertThatThrownBy(() -> w.versions.reject("card-fee", 1,
                    new WriteOptions("k-stale-" + stale, stale, "reason")))
                    .as("If-Match %d", stale).isInstanceOf(VersionConflictException.class);
        }
        assertThat(w.stores.outbox).hasSize(events);
        assertThat(w.stores.history).hasSize(history);
    }

    @Test
    void RLE_025_every_lifecycle_step_writes_one_chained_history_row() {
        // Given a version created, requested, rejected, requested again and approved
        World w = new World();
        w.build(VersionStatus.PENDING_APPROVAL, "lan", "lan");
        w.caller.become("bank-a", "minh", Set.of(READ, APPROVE));
        w.versions.reject("card-fee", 1, new WriteOptions(null, null, "first"));
        w.caller.become("bank-a", "lan", Set.of(READ, AUTHOR));
        w.versions.requestPublish("card-fee", 1, WriteOptions.none());
        w.caller.become("bank-a", "minh", Set.of(READ, APPROVE));
        RuleSetVersion published = w.versions.approve("card-fee", 1, new WriteOptions(null, null, "ok"));

        // When the history is read
        List<VersionHistoryEntry> rows = w.versions.history("card-fee", 1);

        // Then one row per step, each starting where the previous ended, lock versions rising to the current one
        assertThat(rows).extracting(VersionHistoryEntry::action).containsExactly(LifecycleAction.CREATED,
                LifecycleAction.REQUESTED, LifecycleAction.REJECTED, LifecycleAction.REQUESTED, LifecycleAction.APPROVED);
        assertThat(rows.getFirst().fromStatus()).isNull();
        for (int i = 1; i < rows.size(); i++) {
            assertThat(rows.get(i).fromStatus()).isEqualTo(rows.get(i - 1).toStatus());
            assertThat(rows.get(i).lockVersion()).isGreaterThan(rows.get(i - 1).lockVersion());
        }
        assertThat(rows.getLast().lockVersion()).isEqualTo(published.lockVersion());
        assertThat(rows.getLast().reason()).isEqualTo("ok");
    }

    // ---------------------------------------------------------------- RLE-041 order and completeness

    @Test
    void RLE_041_violations_follow_the_order_cells_are_written_in() {
        // Given outputs declared fee then waived, and R1 writing waived before fee, both wrong
        World w = new World();
        w.caller.become("bank-a", "lan", Set.of(READ, AUTHOR));
        byte[] bad = """
                schemaVersion: 1
                ruleSet: order-check
                hitPolicy: first
                inputs:
                  - { name: tier, type: string }
                outputs:
                  - { name: fee, type: money }
                  - { name: waived, type: boolean }
                rules:
                  - { id: R1, when: { tier: "== 'GOLD'" }, then: { waived: "'yes'", fee: "30000" } }
                """.getBytes(StandardCharsets.UTF_8);

        // Then document order: R1/waived, then R1/fee
        assertThatThrownBy(() -> w.versions.importRuleSet(ImportRequest.yaml(bad)))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.violations())
                        .extracting(Violation::ruleId, Violation::column)
                        .containsExactly(tuple("R1", "waived"), tuple("R1", "fee")));
    }

    @Test
    void RLE_041_condition_and_output_problems_of_several_rules_and_a_default_are_all_listed() {
        World w = new World();
        w.caller.become("bank-a", "lan", Set.of(READ, AUTHOR));
        byte[] bad = """
                schemaVersion: 1
                ruleSet: many-problems
                hitPolicy: first
                inputs:
                  - { name: tier, type: string }
                  - { name: balance, type: money }
                outputs:
                  - { name: fee, type: money, default: "5" }
                rules:
                  - { id: R1, when: { tier: "== ", balance: ">= 5" }, then: { fee: "+ 1" } }
                  - { id: R2, when: { tier: "== 'GOLD'" }, then: { fee: "30000" } }
                """.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> w.versions.importRuleSet(ImportRequest.yaml(bad)))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.violations())
                        .extracting(Violation::ruleId, Violation::column)
                        .containsExactly(tuple(null, "fee"), tuple("R1", "tier"), tuple("R1", "balance"),
                                tuple("R1", "fee"), tuple("R2", "fee")));
    }

    @Test
    void RLE_041_an_unknown_input_does_not_hide_the_cell_problems_of_later_rules() {
        // Given R2 names an unknown input (a cell problem, placed on R2/txCnt) and R3 has a money cell without currency
        World w = new World();
        w.caller.become("bank-a", "lan", Set.of(READ, AUTHOR));
        byte[] bad = new String(CardFee.yaml(), StandardCharsets.UTF_8)
                .replace("when: { txCount: \">= 3\" }", "when: { txCnt: \">= 3\" }")
                .replace("then: { fee: \"50000 VND\" }", "then: { fee: \"50000\" }")
                .getBytes(StandardCharsets.UTF_8);

        // Then DESIGN 4.2: one response lists every cell problem
        assertThatThrownBy(() -> w.versions.importRuleSet(ImportRequest.yaml(bad)))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.violations())
                        .extracting(Violation::ruleId, Violation::column)
                        .containsExactly(tuple("R2", "txCnt"), tuple("R3", "fee")));
    }
}
