package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionNotFoundException;
import io.github.dhluong90.bpm.rules.api.EvidenceExport;
import io.github.dhluong90.bpm.rules.api.EvidenceFormat;
import io.github.dhluong90.bpm.rules.api.EvidenceRequest;
import io.github.dhluong90.bpm.rules.api.EvidenceTooLargeException;
import io.github.dhluong90.bpm.rules.api.IdempotencyKeyReuseException;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.LifecycleAction;
import io.github.dhluong90.bpm.rules.api.MaskingKey;
import io.github.dhluong90.bpm.rules.api.PageRequest;
import io.github.dhluong90.bpm.rules.api.PermissionDeniedException;
import io.github.dhluong90.bpm.rules.api.QueryInvalidException;
import io.github.dhluong90.bpm.rules.api.RuleSetHistory;
import io.github.dhluong90.bpm.rules.api.RuleSetNotFoundException;
import io.github.dhluong90.bpm.rules.api.VersionHistoryEntry;
import io.github.dhluong90.bpm.rules.api.VersionNotFoundException;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import io.github.dhluong90.bpm.rules.internal.json.Hashes;
import io.github.dhluong90.bpm.rules.spi.MaskingPolicy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A rule set's history across its versions and the evidence export (PD-116, issue #118), through the public services
 * on the in-memory stores. The story: Lan writes card-fee v1, Minh approves it; v2 is rejected by Minh with a comment,
 * then approved, which retires v1; v3 is a draft. Ha, an auditor, reads the history and exports evidence.
 */
class RuleSetHistoryAndEvidenceTest {

    static final Instant START = Instant.parse("2027-01-10T01:00:00Z");
    static final String NATIONAL_ID = "079090001234";
    static final Set<String> AUDITOR = Set.of("rules:read", "rules:log:read");
    static final String REJECTION = "=HYPERLINK(\"http://x\"), R2 waiver needs pricing committee minutes";

    static final String CARD_FEE = """
            schemaVersion: 1
            ruleSet: card-fee
            hitPolicy: first
            inputs:
              - { name: nationalId, type: string, mask: true }
              - { name: tier, type: string }
              - { name: txCount, type: integer }
            outputs:
              - { name: fee, type: money }
            rules:
              - { id: R1, when: { tier: "== 'GOLD'" }, then: { fee: "0 VND" } }
              - { id: R2, when: { txCount: ">= 3" }, then: { fee: "10000 VND" } }
              - { id: R3, when: {}, then: { fee: "50000 VND" } }
            """;

    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "lan");
    final MaskedInputsTest.MovingClock clock = new MaskedInputsTest.MovingClock(START);
    RuleEngines engines;
    final List<String> decisions = new ArrayList<>();

    @BeforeEach
    void cardFeeWithThreeVersionsAndThreeDecisions() {
        engines = RuleEngines.create(new EngineContext(caller, caller, clock, stores, stores, EngineSettings.defaults(),
                MaskingPolicy.withKey(MaskingKey.of(MaskedInputsTest.KEY_A))), stores, stores, stores);
        engines.cursorKeys().load();
        var versions = engines.versionService();
        caller.become("bank-a", "lan");
        versions.importRuleSet(ImportRequest.yaml(CARD_FEE.getBytes(StandardCharsets.UTF_8)));
        long lock = versions.requestPublish("card-fee", 1, WriteOptions.none()).lockVersion();
        later();
        caller.become("bank-a", "minh");
        versions.approve("card-fee", 1, new WriteOptions(null, lock, "First fee table"));
        later();
        for (int i = 0; i < 3; i++) {
            caller.become("bank-a", "teller", Set.of("rules:evaluate"));
            decisions.add(engines.ruleEngine().evaluate("card-fee", DecisionInput.of(Map.of("nationalId", NATIONAL_ID,
                    "tier", "SILVER", "txCount", 3 + i))).decisionId());
            later();
        }
        caller.become("bank-a", "lan");
        versions.importVersion("card-fee", ImportRequest.yaml(CARD_FEE.replace("10000 VND", "8000 VND")
                .getBytes(StandardCharsets.UTF_8)));
        lock = versions.requestPublish("card-fee", 2, WriteOptions.none()).lockVersion();
        later();
        caller.become("bank-a", "minh");
        lock = versions.reject("card-fee", 2, new WriteOptions(null, lock, REJECTION)).lockVersion();
        later();
        caller.become("bank-a", "lan");
        lock = versions.requestPublish("card-fee", 2, new WriteOptions(null, lock, null)).lockVersion();
        later();
        caller.become("bank-a", "minh");
        versions.approve("card-fee", 2, new WriteOptions(null, lock, "Minutes attached"));
        later();
        caller.become("bank-a", "lan");
        versions.importVersion("card-fee", ImportRequest.yaml(CARD_FEE.replace("50000 VND", "45000 VND")
                .getBytes(StandardCharsets.UTF_8)));
        later();
        caller.become("bank-a", "ha", AUDITOR);
    }

    void later() {
        clock.advance(Duration.ofMinutes(5));
    }

    EvidenceRequest request(EvidenceFormat format, List<Integer> versions, List<String> ids, String key) {
        return new EvidenceRequest(format, versions, ids, key, "audit-case-17");
    }

    @Test
    void history_lists_every_version_newest_first_with_its_events_oldest_first() {
        RuleSetHistory page = engines.ruleSetHistory().history("card-fee", PageRequest.first());

        assertThat(page.versions()).extracting(RuleSetHistory.VersionLine::version).containsExactly(3, 2, 1);
        RuleSetHistory.VersionLine v3 = page.versions().get(0);
        RuleSetHistory.VersionLine v2 = page.versions().get(1);
        RuleSetHistory.VersionLine v1 = page.versions().get(2);
        assertThat(v3.status()).isEqualTo(VersionStatus.DRAFT);
        assertThat(v3.events()).extracting(VersionHistoryEntry::action).containsExactly(LifecycleAction.CREATED);
        assertThat(v2.status()).isEqualTo(VersionStatus.PUBLISHED);
        assertThat(v2.createdBy()).isEqualTo("lan");
        assertThat(v2.approvedBy()).isEqualTo("minh");
        assertThat(v2.approvalComment()).isEqualTo("Minutes attached");
        assertThat(v2.rejectedBy()).isEqualTo("minh");
        assertThat(v2.rejectionReason()).isEqualTo(REJECTION);
        assertThat(v2.events()).extracting(VersionHistoryEntry::action).containsExactly(LifecycleAction.CREATED,
                LifecycleAction.REQUESTED, LifecycleAction.REJECTED, LifecycleAction.REQUESTED, LifecycleAction.APPROVED);
        assertThat(v1.status()).isEqualTo(VersionStatus.RETIRED);
        assertThat(v1.effectiveTo()).isEqualTo(v2.approvedAt());
        assertThat(v1.events().getLast().action()).isEqualTo(LifecycleAction.RETIRED);
        assertThat(v1.events().getLast().supersededBy()).isEqualTo(2);
        assertThat(page.ruleSetEvents()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void history_is_paged_by_a_signed_cursor_and_scoped_to_the_tenant_and_permission() {
        RuleSetHistory first = engines.ruleSetHistory().history("card-fee", new PageRequest(2, null));
        engines.ruleSetHistory().exportEvidence("card-fee", request(EvidenceFormat.CSV, List.of(1), List.of(), null));
        RuleSetHistory second = engines.ruleSetHistory().history("card-fee", new PageRequest(2, first.nextCursor()));

        assertThat(first.versions()).extracting(RuleSetHistory.VersionLine::version).containsExactly(3, 2);
        assertThat(first.nextCursor()).isNotNull();
        assertThat(second.versions()).extracting(RuleSetHistory.VersionLine::version).containsExactly(1);
        assertThat(second.ruleSetEvents()).as("rule set events are on the first page only").isEmpty();
        assertThat(second.nextCursor()).isNull();
        assertThatThrownBy(() -> engines.ruleSetHistory().history("card-fee", new PageRequest(2, first.nextCursor() + "x")))
                .isInstanceOf(QueryInvalidException.class);
        assertThatThrownBy(() -> engines.ruleSetHistory().history("card-fee", new PageRequest(201, null)))
                .isInstanceOf(QueryInvalidException.class);

        caller.become("bank-b", "ha", AUDITOR);
        assertThatThrownBy(() -> engines.ruleSetHistory().history("card-fee", PageRequest.first()))
                .isInstanceOf(RuleSetNotFoundException.class);
        caller.become("bank-a", "teller", Set.of("rules:evaluate"));
        assertThatThrownBy(() -> engines.ruleSetHistory().history("card-fee", PageRequest.first()))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void csv_export_states_who_when_tenant_and_hash_keeps_masked_inputs_masked_and_is_recorded() {
        EvidenceExport export = engines.ruleSetHistory().exportEvidence("card-fee",
                request(EvidenceFormat.CSV, List.of(2, 1), decisions, "k-118"));

        String[] lines = export.content().split("\r\n");
        assertThat(lines[0]).isEqualTo("# HORUS Rule Engine evidence export");
        assertThat(lines[1]).isEqualTo("# Rule set: card-fee");
        assertThat(lines[2]).isEqualTo("# Tenant: bank-a");
        assertThat(lines[3]).isEqualTo("# Exported by: ha");
        assertThat(lines[4]).isEqualTo("# Exported at: " + clock.instant());
        assertThat(lines[5]).isEqualTo("# Content SHA-256: " + export.contentSha256());
        assertThat(lines[6]).isEqualTo(String.join(",", EvidenceFiles.CSV_COLUMNS));
        String content = export.content().substring(export.content().indexOf(lines[6]));
        assertThat(Hashes.sha256(content)).isEqualTo(export.contentSha256());
        assertThat(export.content()).endsWith("\r\n").doesNotContain(NATIONAL_ID).contains("hmac:k1:");
        assertThat(lines).filteredOn(l -> l.startsWith("version event,1,")).hasSize(4);
        assertThat(lines).filteredOn(l -> l.startsWith("version event,2,")).hasSize(5);
        assertThat(lines).filteredOn(l -> l.startsWith("decision,1,")).hasSize(3);
        assertThat(export.content()).as("a formula is never left for a spreadsheet to run")
                .contains(",\"'=HYPERLINK(\"\"http://x\"\"), R2 waiver needs pricing committee minutes\",");
        assertThat(export.versions()).containsExactly(1, 2);
        assertThat(export.fileName()).isEqualTo("card-fee-evidence-20270110T015000Z.csv");

        // Recorded once in the rule set's history, and as an event in the standard envelope
        RuleSetHistory history = engines.ruleSetHistory().history("card-fee", PageRequest.first());
        assertThat(history.ruleSetEvents()).hasSize(1);
        RuleSetHistory.RuleSetEvent recorded = history.ruleSetEvents().getFirst();
        assertThat(recorded.action()).isEqualTo(RuleSetHistory.Action.EVIDENCE_EXPORTED);
        assertThat(recorded.actor()).isEqualTo("ha");
        assertThat(recorded.evidence().versions()).containsExactly(1, 2);
        assertThat(recorded.evidence().decisionIds()).containsExactlyElementsOf(decisions);
        assertThat(recorded.evidence().contentSha256()).isEqualTo(export.contentSha256());
        assertThat(recorded.evidence().bytes()).isEqualTo(export.bytes().length);
        List<JsonNode> events = stores.outbox.stream().map(row -> EngineJson.JSON.readTree(row.payload()))
                .filter(p -> p.path("eventType").asString().equals("rules.evidence.exported.v1")).toList();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().path("actor").asString()).isEqualTo("ha");
        assertThat(events.getFirst().path("correlationId").asString()).isEqualTo("audit-case-17");
        assertThat(events.getFirst().path("contentSha256").asString()).isEqualTo(export.contentSha256());
        assertThat(events.getFirst().toString()).doesNotContain("hmac:").doesNotContain(NATIONAL_ID);

        // A repeat with the key returns the same bytes and writes nothing; a changed body with it is refused
        later();
        EvidenceExport replay = engines.ruleSetHistory().exportEvidence("card-fee",
                request(EvidenceFormat.CSV, List.of(1, 2), decisions, "k-118"));
        assertThat(replay.content()).isEqualTo(export.content());
        assertThat(stores.ruleSetEvents).hasSize(1);
        assertThatThrownBy(() -> engines.ruleSetHistory().exportEvidence("card-fee",
                request(EvidenceFormat.CSV, List.of(1), decisions, "k-118")))
                .isInstanceOf(IdempotencyKeyReuseException.class);

        // The same records exported later, by someone else, have the same content hash
        caller.become("bank-a", "an", AUDITOR);
        EvidenceExport again = engines.ruleSetHistory().exportEvidence("card-fee",
                request(EvidenceFormat.CSV, List.of(1, 2), decisions, null));
        assertThat(again.contentSha256()).isEqualTo(export.contentSha256());
        assertThat(again.content()).isNotEqualTo(export.content());
        assertThat(stores.ruleSetEvents).hasSize(2);
    }

    @Test
    void json_export_hashes_its_content_member_as_written() {
        EvidenceExport export = engines.ruleSetHistory().exportEvidence("card-fee",
                request(EvidenceFormat.JSON, List.of(1), decisions.subList(0, 1), null));

        JsonNode file = EngineJson.JSON.readTree(export.content());
        assertThat(file.path("evidence").path("tenantId").asString()).isEqualTo("bank-a");
        assertThat(file.path("evidence").path("exportedBy").asString()).isEqualTo("ha");
        assertThat(file.path("evidence").path("contentSha256").asString()).isEqualTo(export.contentSha256());
        String marker = ",\"content\":";
        String content = export.content().substring(export.content().indexOf(marker) + marker.length(),
                export.content().length() - 1);
        assertThat(Hashes.sha256(content)).isEqualTo(export.contentSha256());
        assertThat(file.at("/content/versions/0/events").size()).isEqualTo(4);
        assertThat(file.at("/content/decisions/0/inputs/nationalId").asString()).startsWith("hmac:k1:");
        assertThat(export.content()).doesNotContain(NATIONAL_ID);
    }

    @Test
    void limits_permissions_and_wrong_records_are_refused_before_anything_is_written() {
        var service = engines.ruleSetHistory();
        int outboxBefore = stores.outbox.size();

        // Without rules:log:read decisions are refused, and a versions-only export works
        caller.become("bank-a", "ha", Set.of("rules:read"));
        assertThatThrownBy(() -> service.exportEvidence("card-fee", request(EvidenceFormat.CSV, List.of(1), decisions,
                null))).isInstanceOf(PermissionDeniedException.class).hasMessageContaining("rules:log:read");
        assertThat(service.exportEvidence("card-fee", request(EvidenceFormat.CSV, List.of(1), List.of(), null))
                .content()).contains("version event,1,");
        caller.become("bank-a", "ha", AUDITOR);

        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            tooMany.add(String.format("01KF2Q8Z0RQ6V2Y8M1C4N5P%03d", i).replace('0', 'A'));
        }
        assertThatThrownBy(() -> service.exportEvidence("card-fee", request(EvidenceFormat.CSV, List.of(), tooMany,
                null))).isInstanceOf(InputInvalidException.class).hasMessageContaining("Choose at most 100 decisions");
        assertThatThrownBy(() -> service.exportEvidence("card-fee", request(EvidenceFormat.CSV, List.of(), List.of(),
                null))).isInstanceOf(InputInvalidException.class);
        assertThatThrownBy(() -> service.exportEvidence("card-fee", request(EvidenceFormat.CSV, List.of(1, 1),
                List.of(), null))).isInstanceOf(InputInvalidException.class).hasMessageContaining("twice");
        assertThatThrownBy(() -> service.exportEvidence("card-fee", request(EvidenceFormat.CSV, List.of(9), List.of(),
                null))).isInstanceOf(VersionNotFoundException.class);
        assertThatThrownBy(() -> service.exportEvidence("card-fee", request(EvidenceFormat.CSV, List.of(),
                List.of("01KF2Q8Z0RQ6V2Y8M1C4N5P7T9"), null))).isInstanceOf(DecisionNotFoundException.class)
                .hasMessageContaining("01KF2Q8Z0RQ6V2Y8M1C4N5P7T9");

        // A decision of another rule set is refused, naming it
        caller.become("bank-a", "lan");
        engines.versionService().importRuleSet(ImportRequest.yaml(CARD_FEE.replace("ruleSet: card-fee",
                "ruleSet: other-fee").getBytes(StandardCharsets.UTF_8)));
        long lock = engines.versionService().requestPublish("other-fee", 1, WriteOptions.none()).lockVersion();
        caller.become("bank-a", "minh");
        engines.versionService().approve("other-fee", 1, new WriteOptions(null, lock, null));
        caller.become("bank-a", "teller", Set.of("rules:evaluate"));
        String other = engines.ruleEngine().evaluate("other-fee", DecisionInput.of(Map.of("tier", "GOLD")))
                .decisionId();
        int outboxAfterSetUp = stores.outbox.size();
        caller.become("bank-a", "ha", AUDITOR);
        assertThatThrownBy(() -> service.exportEvidence("card-fee", request(EvidenceFormat.CSV, List.of(),
                List.of(decisions.getFirst(), other), null))).isInstanceOf(InputInvalidException.class)
                .hasMessageContaining(other).hasMessageContaining("other-fee");

        // A file over the limit is refused
        var small = new DefaultRuleSetHistoryService(new EngineContext(caller, caller, clock, stores, stores,
                EngineSettings.defaults()), stores, stores, stores, engines.cursorKeys(), 500);
        assertThatThrownBy(() -> small.exportEvidence("card-fee", request(EvidenceFormat.CSV, List.of(1, 2), decisions,
                null))).isInstanceOf(EvidenceTooLargeException.class);

        // Only the one versions-only export was recorded
        assertThat(stores.ruleSetEvents).hasSize(1);
        assertThat(stores.outbox.size() - outboxAfterSetUp).isZero();
        assertThat(outboxBefore).isLessThan(outboxAfterSetUp);
    }
}
