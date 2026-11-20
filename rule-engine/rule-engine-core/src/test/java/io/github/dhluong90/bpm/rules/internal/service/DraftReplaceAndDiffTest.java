package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.DraftAuthoringDisabledException;
import io.github.dhluong90.bpm.rules.api.IdempotencyKeyReuseException;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.InvalidStateException;
import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.rules.api.PermissionDeniedException;
import io.github.dhluong90.bpm.rules.api.RuleSetNotFoundException;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.api.VersionConflictException;
import io.github.dhluong90.bpm.rules.api.VersionDiff;
import io.github.dhluong90.bpm.rules.api.VersionDiff.Change;
import io.github.dhluong90.bpm.rules.api.VersionDiff.FieldChange;
import io.github.dhluong90.bpm.rules.api.VersionDiff.RowChange;
import io.github.dhluong90.bpm.rules.api.VersionNotFoundException;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.api.Violation;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Replacing a draft in place (RLE-047) and the engine's version diff (RLE-028, RLE-048), on the in-memory stores.
 * Users at bank-a: lan authors, minh authors and approves, bob approves, dan reads; a bank-b caller checks tenancy.
 */
class DraftReplaceAndDiffTest {

    static final Instant DEC_01 = Instant.parse("2026-12-01T00:00:00Z");
    static final Set<String> AUTHOR = Set.of("rules:read", "rules:author");
    static final Set<String> AUTHOR_APPROVER = Set.of("rules:read", "rules:author", "rules:approve");
    static final Set<String> APPROVER = Set.of("rules:read", "rules:approve");
    static final Set<String> READER = Set.of("rules:read");

    final EditorApiTest.MovableClock clock = new EditorApiTest.MovableClock(DEC_01);
    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "lan");
    final VersionService versions = engines(true).versionService();

    RuleEngines engines(boolean draftAuthoring) {
        RuleEngines engines = RuleEngines.create(new EngineContext(caller, caller, clock, stores, stores,
                EngineSettings.defaults().withDraftAuthoring(draftAuthoring)), stores, stores, stores);
        engines.cursorKeys().load();
        return engines;
    }

    // ---------------------------------------------------------------- RLE-047 replace a draft

    /** card-fee v1 published by bob; v2 a draft by lan from card-fee.yaml, lockVersion 0. */
    RuleSetVersion lansDraftV2() {
        caller.become("bank-a", "lan", AUTHOR);
        versions.importRuleSet(ImportRequest.yaml(CardFee.yaml()));
        versions.requestPublish("card-fee", 1, WriteOptions.none());
        caller.become("bank-a", "bob", APPROVER);
        versions.approve("card-fee", 1, new WriteOptions(null, 1L, null));
        caller.become("bank-a", "lan", AUTHOR);
        return versions.importVersion("card-fee", ImportRequest.yaml(CardFee.yaml()));
    }

    static byte[] r2Zero() {
        return EditorApiTest.cardFee("0 VND", "card-fee");
    }

    @Test
    void RLE_047_author_replaces_draft_in_place_with_import_checks() {
        // Given lan's draft v2 with R2 at 10000 VND
        RuleSetVersion draft = lansDraftV2();
        int events = stores.outbox.size();
        int history = stores.history.size();

        // When lan replaces it with card-fee-r2-zero.yaml, k-501 and If-Match "0"
        WriteOptions put = new WriteOptions("k-501", draft.lockVersion(), null);
        RuleSetVersion replaced = versions.replaceDraft("card-fee", 2, ImportRequest.yaml(r2Zero()), put);

        // Then still v2, still a draft by lan, lock moved on, hashes and source replaced
        assertThat(replaced.version()).isEqualTo(2);
        assertThat(replaced.status()).isEqualTo(VersionStatus.DRAFT);
        assertThat(replaced.createdBy()).isEqualTo("lan");
        assertThat(replaced.createdAt()).isEqualTo(draft.createdAt());
        assertThat(replaced.lockVersion()).isEqualTo(draft.lockVersion() + 1);
        assertThat(replaced.canonicalHash()).isNotEqualTo(draft.canonicalHash())
                .isEqualTo(io.github.dhluong90.bpm.rules.internal.json.Hashes.sha256(replaced.canonicalDocument()));
        assertThat(replaced.sourceHash()).isEqualTo(io.github.dhluong90.bpm.rules.internal.json.Hashes.sha256(r2Zero()));
        assertThat(versions.find("card-fee", 2, true).orElseThrow().sourceDocument())
                .isEqualTo(new String(r2Zero(), StandardCharsets.UTF_8));
        // and no new version, one DRAFT_UPDATED history row with both hashes, one draft-updated event
        assertThat(versions.find("card-fee", 3, false)).isEmpty();
        assertThat(stores.history).hasSize(history + 1);
        io.github.dhluong90.bpm.rules.api.VersionHistoryEntry audit = versions.history("card-fee", 2).getLast();
        assertThat(audit.action()).isEqualTo(io.github.dhluong90.bpm.rules.api.LifecycleAction.DRAFT_UPDATED);
        assertThat(audit.actor()).isEqualTo("lan");
        assertThat(audit.fromStatus()).isEqualTo(VersionStatus.DRAFT);
        assertThat(audit.toStatus()).isEqualTo(VersionStatus.DRAFT);
        assertThat(audit.lockVersion()).isEqualTo(replaced.lockVersion());
        assertThat(audit.previousCanonicalHash()).isEqualTo(draft.canonicalHash());
        assertThat(audit.canonicalHash()).isEqualTo(replaced.canonicalHash());
        assertThat(stores.outbox).hasSize(events + 1);
        assertThat(stores.outbox.getLast().payload()).contains("\"eventType\":\"rules.version.draft-updated.v1\"",
                "\"updatedBy\":\"lan\"", "\"ruleSet\":\"card-fee\"", "\"version\":2", "\"tenantId\":\"bank-a\"",
                "\"canonicalHash\":\"" + replaced.canonicalHash() + "\"",
                "\"previousCanonicalHash\":\"" + draft.canonicalHash() + "\"");

        // When the PUT is repeated with k-501: the same result, nothing changes, no second row or event
        assertThat(versions.replaceDraft("card-fee", 2, ImportRequest.yaml(r2Zero()), put)).isEqualTo(replaced);
        assertThat(versions.find("card-fee", 2, false).orElseThrow().lockVersion()).isEqualTo(replaced.lockVersion());
        assertThat(stores.history).hasSize(history + 1);
        assertThat(stores.outbox).hasSize(events + 1);

        // And once requested and approved, R2 charges 0 VND
        RuleSetVersion requested = versions.requestPublish("card-fee", 2, WriteOptions.none());
        caller.become("bank-a", "bob", Set.of("rules:approve", "rules:evaluate"));
        versions.approve("card-fee", 2, new WriteOptions(null, requested.lockVersion(), null));
        DecisionResult result = engines(true).ruleEngine().evaluate("card-fee",
                DecisionInput.of(Map.of("tier", "SILVER", "txCount", 5)));
        assertThat(result.version()).isEqualTo(2);
        assertThat(result.outcome()).isEqualTo(new DecisionResult.Matched(List.of("R2"),
                Map.of("fee", Money.ofMinor("VND", 0))));
    }

    @Test
    void RLE_047_every_refused_replace_stores_nothing() {
        RuleSetVersion draft = lansDraftV2();
        versions.replaceDraft("card-fee", 2, ImportRequest.yaml(r2Zero()), new WriteOptions("k-501", 0L, null));
        RuleSetVersion current = versions.find("card-fee", 2, true).orElseThrow();
        int keys = stores.idempotency.size();
        int history = stores.history.size();
        int events = stores.outbox.size();
        byte[] noCurrency = new String(CardFee.yaml(), StandardCharsets.UTF_8)
                .replace("  - { name: txCount, type: integer, description: card transactions in the month }",
                        "  - { name: txCount, type: integer, description: card transactions in the month }\n"
                                + "  - { name: balance, type: money }")
                .concat("  - id: R4\n    when: { balance: \">= 50000000\" }\n    then: { fee: \"0 VND\" }\n")
                .getBytes(StandardCharsets.UTF_8);

        // a stale If-Match "0" with a new key
        assertThatThrownBy(() -> versions.replaceDraft("card-fee", 2, ImportRequest.yaml(CardFee.yaml()),
                new WriteOptions("k-502", draft.lockVersion(), null))).isInstanceOf(VersionConflictException.class);
        // a document that does not compile: 422 at R4 / balance
        assertThatThrownBy(() -> versions.replaceDraft("card-fee", 2, ImportRequest.yaml(noCurrency),
                new WriteOptions("k-503", current.lockVersion(), null)))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.violations())
                        .extracting(Violation::ruleId, Violation::column)
                        .contains(org.assertj.core.groups.Tuple.tuple("R4", "balance")));
        // a document naming another rule set
        assertThatThrownBy(() -> versions.replaceDraft("card-fee", 2,
                ImportRequest.yaml(EditorApiTest.cardFee("0 VND", "kyc-risk")),
                new WriteOptions("k-504", current.lockVersion(), null)))
                .isInstanceOf(InputInvalidException.class).hasMessageContaining("kyc-risk");
        // the published v1
        assertThatThrownBy(() -> versions.replaceDraft("card-fee", 1, ImportRequest.yaml(r2Zero()),
                new WriteOptions("k-505", null, null))).isInstanceOf(InvalidStateException.class);
        // minh holds rules:author but is not the draft's author
        caller.become("bank-a", "minh", AUTHOR_APPROVER);
        assertThatThrownBy(() -> versions.replaceDraft("card-fee", 2, ImportRequest.yaml(CardFee.yaml()),
                new WriteOptions("k-506", current.lockVersion(), null)))
                .isInstanceOfSatisfying(PermissionDeniedException.class,
                        e -> assertThat(e.getMessage()).contains("Only the author", "POST .../rule-sets/card-fee/versions"));
        // dan only reads
        caller.become("bank-a", "dan", READER);
        assertThatThrownBy(() -> versions.replaceDraft("card-fee", 2, ImportRequest.yaml(CardFee.yaml()),
                new WriteOptions("k-507", current.lockVersion(), null))).isInstanceOf(PermissionDeniedException.class);
        // a caller of bank-b
        caller.become("bank-b", "lan", AUTHOR);
        assertThatThrownBy(() -> versions.replaceDraft("card-fee", 2, ImportRequest.yaml(CardFee.yaml()),
                new WriteOptions("k-508", current.lockVersion(), null))).isInstanceOf(RuleSetNotFoundException.class);
        // the host with draft authoring off
        caller.become("bank-a", "lan", AUTHOR);
        VersionService draftsOff = engines(false).versionService();
        assertThatThrownBy(() -> draftsOff.replaceDraft("card-fee", 2, ImportRequest.yaml(CardFee.yaml()),
                new WriteOptions("k-509", current.lockVersion(), null)))
                .isInstanceOfSatisfying(DraftAuthoringDisabledException.class,
                        e -> assertThat(e.code()).isEqualTo("DRAFT_AUTHORING_DISABLED"));
        // the same key with another document is a key reuse mismatch
        assertThatThrownBy(() -> versions.replaceDraft("card-fee", 2, ImportRequest.yaml(CardFee.yaml()),
                new WriteOptions("k-501", 0L, null))).isInstanceOf(IdempotencyKeyReuseException.class);

        // and v2 is exactly as before, with no new idempotency record
        assertThat(versions.find("card-fee", 2, true).orElseThrow()).isEqualTo(current);
        assertThat(stores.idempotency).hasSize(keys);
        assertThat(stores.history).hasSize(history);
        assertThat(stores.outbox).hasSize(events);
    }

    /**
     * Security review of #234 (#275): one version number can carry other content over time. An approver who reviewed
     * the first request must not be able to publish what replaced it. Sequence: request, sent back, replaced in place,
     * requested again, then approved with the lock version the approver saw.
     */
    @Test
    void RLE_047_an_approval_based_on_content_that_was_replaced_since_is_refused() {
        // Given lan's draft v2 (R2 at 10000 VND) requested; minh, the approver, reads it at lockVersion 1
        RuleSetVersion draft = lansDraftV2();
        RuleSetVersion requested = versions.requestPublish("card-fee", 2, new WriteOptions(null, draft.lockVersion(), null));
        caller.become("bank-a", "minh", APPROVER);
        RuleSetVersion reviewed = versions.find("card-fee", 2, false).orElseThrow();
        assertThat(reviewed.lockVersion()).isEqualTo(requested.lockVersion());

        // When the request goes back to draft, lan replaces R2 with 0 VND and requests publication again
        caller.become("bank-a", "bob", APPROVER);
        versions.reject("card-fee", 2, new WriteOptions(null, reviewed.lockVersion(), "Please double-check R2"));
        caller.become("bank-a", "lan", AUTHOR);
        RuleSetVersion afterReject = versions.find("card-fee", 2, false).orElseThrow();
        RuleSetVersion replaced = versions.replaceDraft("card-fee", 2, ImportRequest.yaml(r2Zero()),
                new WriteOptions("k-701", afterReject.lockVersion(), null));
        RuleSetVersion again = versions.requestPublish("card-fee", 2, new WriteOptions(null, replaced.lockVersion(), null));
        assertThat(again.canonicalHash()).isNotEqualTo(reviewed.canonicalHash());
        int events = stores.outbox.size();
        int history = stores.history.size();

        // Then minh's approval with the lock version he reviewed is refused, and so is one with no lock version
        caller.become("bank-a", "minh", APPROVER);
        assertThatThrownBy(() -> versions.approve("card-fee", 2, new WriteOptions(null, reviewed.lockVersion(), null)))
                .isInstanceOf(VersionConflictException.class);
        assertThatThrownBy(() -> versions.approve("card-fee", 2, WriteOptions.none()))
                .isInstanceOfSatisfying(io.github.dhluong90.bpm.rules.api.PreconditionRequiredException.class,
                        e -> assertThat(e.code()).isEqualTo("PRECONDITION_REQUIRED"));
        assertThatThrownBy(() -> versions.reject("card-fee", 2, new WriteOptions(null, null, "no lock")))
                .isInstanceOfSatisfying(io.github.dhluong90.bpm.rules.api.PreconditionRequiredException.class,
                        e -> assertThat(e.code()).isEqualTo("PRECONDITION_REQUIRED"));
        // and nothing was published, recorded or emitted
        RuleSetVersion still = versions.find("card-fee", 2, false).orElseThrow();
        assertThat(still.status()).isEqualTo(VersionStatus.PENDING_APPROVAL);
        assertThat(still.lockVersion()).isEqualTo(again.lockVersion());
        assertThat(stores.outbox).hasSize(events);
        assertThat(stores.history).hasSize(history);

        // When minh reviews the new content and approves with its lock version, it publishes
        assertThat(versions.approve("card-fee", 2, new WriteOptions(null, still.lockVersion(), null)).status())
                .isEqualTo(VersionStatus.PUBLISHED);
    }

    // ---------------------------------------------------------------- RLE-028 diff

    static final String R1 = "  - id: R1\n    when: { tier: \"== 'GOLD'\" }\n    then: { fee: \"0 VND\" }\n";
    static final String R2_30000 = "  - id: R2\n    when: { txCount: \">= 3\" }\n    then: { fee: \"30000 VND\" }\n";
    static final String R2_0 = "  - id: R2\n    when: { txCount: \">= 3\" }\n    then: { fee: \"0 VND\" }\n";
    static final String R6 = "  - id: R6\n    when: { tier: \"== 'PLATINUM'\" }\n    then: { fee: \"0 VND\" }\n";
    static final String R3 = "  - id: R3\n    when: {}\n    then: { fee: \"50000 VND\" }\n";

    static byte[] cardFeeDoc(String comment, String effectiveFrom, String... rows) {
        StringBuilder yaml = new StringBuilder();
        if (comment != null) {
            yaml.append("# ").append(comment).append('\n');
        }
        yaml.append("schemaVersion: 1\nruleSet: card-fee\nhitPolicy: first\n");
        if (effectiveFrom != null) {
            yaml.append("effectiveFrom: \"").append(effectiveFrom).append("\"\n");
        }
        yaml.append("inputs:\n  - { name: tier, type: string }\n  - { name: txCount, type: integer }\n")
                .append("outputs:\n  - { name: fee, type: money }\nrules:\n");
        for (String row : rows) {
            yaml.append(row);
        }
        return yaml.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** v1 PUBLISHED, v2 PENDING_APPROVAL, v3 DRAFT (v2 with other comments and effectiveFrom), v4 DRAFT (R2 first). */
    void cardFeeV1toV4() {
        caller.become("bank-a", "lan", AUTHOR);
        versions.importRuleSet(ImportRequest.yaml(cardFeeDoc("v1", null, R1, R2_30000, R3)));
        versions.requestPublish("card-fee", 1, WriteOptions.none());
        caller.become("bank-a", "bob", APPROVER);
        versions.approve("card-fee", 1, new WriteOptions(null, 1L, null));
        caller.become("bank-a", "lan", AUTHOR);
        versions.importVersion("card-fee", ImportRequest.yaml(cardFeeDoc("v2: R2 waived, PLATINUM added", null,
                R1, R2_0, R6, R3)));
        versions.requestPublish("card-fee", 2, WriteOptions.none());
        versions.importVersion("card-fee", ImportRequest.yaml(cardFeeDoc("same rows, scheduled for January",
                "2027-01-01T00:00:00Z", R1, R2_0, R6, R3)));
        versions.importVersion("card-fee", ImportRequest.yaml(cardFeeDoc("v2: R2 waived, PLATINUM added", null,
                R2_0, R1, R6, R3)));
    }

    @Test
    void RLE_028_version_diff_lists_added_changed_removed_and_moved_rows() {
        cardFeeV1toV4();
        caller.become("bank-a", "bob", READER);
        int events = stores.outbox.size();
        int history = stores.history.size();

        // v2 against v1: R2 CHANGED (/then/fee 30000 VND -> 0 VND), R6 ADDED at 3, nothing removed or moved
        VersionDiff d21 = versions.diff("card-fee", 2, 1);
        assertThat(d21.rows()).containsExactly(
                new RowChange("R2", Change.CHANGED, 2, 2, false, List.of(new FieldChange("/then/fee",
                        "{\"currency\":\"VND\",\"amount\":30000}", "{\"currency\":\"VND\",\"amount\":0}", false))),
                new RowChange("R6", Change.ADDED, 3, null, false, List.of()));
        assertThat(d21.unchangedCount()).isEqualTo(2);
        assertThat(d21.header()).isEmpty();
        assertThat(d21.inputs().added()).isEmpty();
        assertThat(d21.inputs().changed()).isEmpty();
        assertThat(d21.outputs().changed()).isEmpty();
        assertThat(d21.canonicalHash()).isNotEqualTo(d21.baseCanonicalHash());
        assertThat(d21.canonicalHash()).isEqualTo(versions.find("card-fee", 2, false).orElseThrow().canonicalHash());

        // v3 against v2: comments and effectiveFrom only -> no content change, equal canonical hashes (#233);
        // effectiveFrom is reported as metadata only
        VersionDiff d32 = versions.diff("card-fee", 3, 2);
        assertThat(d32.rows()).isEmpty();
        assertThat(d32.header()).isEmpty();
        assertThat(d32.inputs().changed()).isEmpty();
        assertThat(d32.outputs().changed()).isEmpty();
        assertThat(d32.unchangedCount()).isEqualTo(4);
        assertThat(d32.canonicalHash()).isEqualTo(d32.baseCanonicalHash());
        assertThat(d32.sourceHashChanged()).isTrue();
        assertThat(d32.effectiveFrom()).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
        assertThat(d32.baseEffectiveFrom()).isNull();

        // v4 against v2: only R1 MOVED from 1 to 2 (the earlier row of v4, R2, is kept)
        VersionDiff d42 = versions.diff("card-fee", 4, 2);
        assertThat(d42.rows()).containsExactly(new RowChange("R1", Change.MOVED, 2, 1, false, List.of()));
        assertThat(d42.unchangedCount()).isEqualTo(3);

        // v1 against v2 (reversed): R6 REMOVED with its base position, R2 changed back
        VersionDiff d12 = versions.diff("card-fee", 1, 2);
        assertThat(d12.rows()).extracting(RowChange::ruleId, RowChange::change, RowChange::position, RowChange::basePosition)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("R2", Change.CHANGED, 2, 2),
                        org.assertj.core.groups.Tuple.tuple("R6", Change.REMOVED, null, 3));

        // a repeated request is equal
        assertThat(versions.diff("card-fee", 2, 1)).isEqualTo(d21);

        // failures: base 9, base equal to the version, another tenant, a reader-less caller
        assertThatThrownBy(() -> versions.diff("card-fee", 2, 9)).isInstanceOf(VersionNotFoundException.class);
        assertThatThrownBy(() -> versions.diff("card-fee", 9, 2)).isInstanceOf(VersionNotFoundException.class);
        assertThatThrownBy(() -> versions.diff("card-fee", 2, 2)).isInstanceOf(InputInvalidException.class);
        caller.become("bank-b", "bob", READER);
        assertThatThrownBy(() -> versions.diff("card-fee", 2, 1)).isInstanceOf(RuleSetNotFoundException.class);
        caller.become("bank-a", "teller", Set.of("rules:evaluate"));
        assertThatThrownBy(() -> versions.diff("card-fee", 2, 1)).isInstanceOf(PermissionDeniedException.class);

        // and the diff wrote nothing
        assertThat(stores.outbox).hasSize(events);
        assertThat(stores.history).hasSize(history);
    }

    @Test
    void RLE_028_a_row_both_moved_and_changed_is_changed_with_moved_true() {
        caller.become("bank-a", "lan", AUTHOR);
        versions.importRuleSet(ImportRequest.yaml(cardFeeDoc(null, null, R1, R2_30000, R3)));
        versions.importVersion("card-fee", ImportRequest.yaml(cardFeeDoc(null, null, R2_30000, R3,
                R1.replace("0 VND", "5000 VND"))));

        VersionDiff diff = versions.diff("card-fee", 2, 1);

        assertThat(diff.rows()).extracting(RowChange::ruleId, RowChange::change, RowChange::moved)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("R1", Change.CHANGED, true));
        assertThat(diff.unchangedCount()).isEqualTo(2);
    }

    @Test
    void RLE_028_moved_set_is_the_complement_of_the_earliest_longest_increasing_subsequence() {
        // a swapped pair keeps the earlier row of the new order
        assertThat(VersionDiffs.earliestLongestIncreasing(new int[] {1, 0, 2, 3})).containsExactly(true, false, true, true);
        // a row taken from the end to the front moves only that row
        assertThat(VersionDiffs.earliestLongestIncreasing(new int[] {3, 0, 1, 2})).containsExactly(false, true, true, true);
        // a full reversal keeps one row, the first of the new order
        assertThat(VersionDiffs.earliestLongestIncreasing(new int[] {3, 2, 1, 0})).containsExactly(true, false, false, false);
        assertThat(VersionDiffs.earliestLongestIncreasing(new int[] {})).isEmpty();
        // seeded: the kept rows are increasing and as many as the longest increasing subsequence (O(n^2) oracle)
        java.util.Random random = new java.util.Random(165);
        for (int round = 0; round < 500; round++) {
            int n = random.nextInt(12);
            List<Integer> values = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                values.add(i);
            }
            java.util.Collections.shuffle(values, random);
            int[] a = values.stream().mapToInt(Integer::intValue).toArray();
            boolean[] kept = VersionDiffs.earliestLongestIncreasing(a);
            int count = 0;
            int last = -1;
            for (int i = 0; i < n; i++) {
                if (kept[i]) {
                    assertThat(a[i]).as("seed 165 round %d", round).isGreaterThan(last);
                    last = a[i];
                    count++;
                }
            }
            assertThat(count).as("seed 165 round %d %s", round, values).isEqualTo(lisLength(a));
        }
    }

    private static int lisLength(int[] a) {
        int[] best = new int[a.length];
        int max = 0;
        for (int i = 0; i < a.length; i++) {
            best[i] = 1;
            for (int j = 0; j < i; j++) {
                if (a[j] < a[i]) {
                    best[i] = Math.max(best[i], best[j] + 1);
                }
            }
            max = Math.max(max, best[i]);
        }
        return max;
    }

    // ---------------------------------------------------------------- RLE-048 non-ASCII, header and columns

    static byte[] kycRoute(String hitPolicy, String countryInput, String extraInput, String condition) {
        String yaml = "schemaVersion: 1\nruleSet: kyc-route\nhitPolicy: " + hitPolicy + "\n"
                + "inputs:\n  - " + countryInput + "\n" + (extraInput == null ? "" : "  - " + extraInput + "\n")
                + "outputs:\n  - { name: route, type: string }\n"
                + "rules:\n  - id: R1\n    when: { country: \"" + condition + "\" }\n    then: { route: \"'MANUAL'\" }\n";
        return yaml.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void RLE_048_diff_marks_non_ascii_literals_and_reports_header_and_column_changes() {
        caller.become("bank-a", "lan", AUTHOR);
        String country = "{ name: country, type: string }";
        versions.importRuleSet(ImportRequest.yaml(kycRoute("first", country, null, "!= 'VN'")));
        versions.importVersion("kyc-route", ImportRequest.yaml(kycRoute("first", country, null, "!= 'ВN'")));
        versions.importVersion("kyc-route", ImportRequest.yaml(kycRoute("first", country, null, "!= 'V\\u200BN'")));
        versions.importVersion("kyc-route", ImportRequest.yaml(kycRoute("unique",
                "{ name: country, type: string, mask: true }", "{ name: segment, type: string }", "!= 'VN'")));
        caller.become("bank-a", "bob", READER);

        // Cyrillic look-alike
        VersionDiff cyrillic = versions.diff("kyc-route", 2, 1);
        assertThat(cyrillic.rows()).singleElement().satisfies(row -> {
            assertThat(row.change()).isEqualTo(Change.CHANGED);
            assertThat(row.fields()).containsExactly(
                    new FieldChange("/when/country", "\"!= 'VN'\"", "\"!= 'ВN'\"", true));
        });
        assertThat(cyrillic.canonicalHash()).isNotEqualTo(cyrillic.baseCanonicalHash());

        // escaped zero-width space
        VersionDiff zeroWidth = versions.diff("kyc-route", 3, 1);
        assertThat(zeroWidth.rows()).singleElement().satisfies(row -> assertThat(row.fields()).containsExactly(
                new FieldChange("/when/country", "\"!= 'VN'\"", "\"!= 'V\\\\u200BN'\"", true)));

        // header and columns
        VersionDiff structure = versions.diff("kyc-route", 4, 1);
        assertThat(structure.rows()).isEmpty();
        assertThat(structure.unchangedCount()).isEqualTo(1);
        assertThat(structure.header()).containsExactly(new FieldChange("/hitPolicy", "\"first\"", "\"unique\"", false));
        assertThat(structure.inputs().added()).containsExactly("segment");
        assertThat(structure.inputs().removed()).isEmpty();
        assertThat(structure.inputs().reordered()).isFalse();
        assertThat(structure.inputs().changed()).singleElement().satisfies(column -> {
            assertThat(column.name()).isEqualTo("country");
            assertThat(column.fields()).containsExactly(new FieldChange("/mask", null, "true", false));
        });
        assertThat(structure.outputs().changed()).isEmpty();
    }

    @Test
    void RLE_048_printable_ascii_is_not_marked_and_escapes_are() {
        assertThat(VersionDiffs.nonAscii(io.github.dhluong90.bpm.rules.internal.json.EngineJson.NODES.stringNode("== 'GOLD'")))
                .isFalse();
        assertThat(VersionDiffs.nonAscii(io.github.dhluong90.bpm.rules.internal.json.EngineJson.NODES.stringNode("== 'GÖLD'")))
                .isTrue();
        assertThat(VersionDiffs.nonAscii(io.github.dhluong90.bpm.rules.internal.json.EngineJson.NODES.stringNode("== 'G\\u200BOLD'")))
                .isTrue();
        assertThat(VersionDiffs.nonAscii(io.github.dhluong90.bpm.rules.internal.json.EngineJson.NODES.stringNode("== 'a\\\\b'")))
                .isFalse();
        assertThat(VersionDiffs.nonAscii(null)).isFalse();
    }
}
