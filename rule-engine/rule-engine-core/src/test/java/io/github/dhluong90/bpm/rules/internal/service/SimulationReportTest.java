package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionRequest;
import io.github.dhluong90.bpm.rules.api.HistorySimulation;
import io.github.dhluong90.bpm.rules.api.HorusBpmException;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.MaskingKey;
import io.github.dhluong90.bpm.rules.api.PermissionDeniedException;
import io.github.dhluong90.bpm.rules.api.RuleEngine;
import io.github.dhluong90.bpm.rules.api.RuleReadings;
import io.github.dhluong90.bpm.rules.api.RuleSetNotFoundException;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.SimulationReport;
import io.github.dhluong90.bpm.rules.api.SimulationReportNotFoundException;
import io.github.dhluong90.bpm.rules.api.SimulationReportOutOfDateException;
import io.github.dhluong90.bpm.rules.api.SimulationReportRequest;
import io.github.dhluong90.bpm.rules.api.SimulationReportRequiredException;
import io.github.dhluong90.bpm.rules.api.SimulationReportService;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.store.SimulationReportStore;
import io.github.dhluong90.bpm.rules.spi.MaskingPolicy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Simulation reports (PD-115, RLE-199 to RLE-202). Card fee v1 charges every customer but GOLD 50,000 VND; in November
 * 2026 the tellers of {@code bank-a} made 100 decisions, 18 of them for STANDARD customers. Draft v2 lowers the fee to
 * 30,000 VND. Lan authors and runs the report, Minh approves, Audit reads the decision log.
 */
class SimulationReportTest {

    static final Set<String> AUTHOR = Set.of("rules:read", "rules:author", "rules:simulate");
    static final Set<String> APPROVER = Set.of("rules:read", "rules:approve");
    static final Set<String> TELLER = Set.of("rules:evaluate");
    static final Set<String> APPROVER_READER = Set.of("rules:read");
    static final Set<String> AUDITOR = Set.of("rules:read", "rules:log:read");
    static final Set<String> SIMULATE_ONLY = Set.of("rules:simulate");

    static final Instant NOV_01 = Instant.parse("2026-11-01T00:00:00Z");
    static final Instant DEC_01 = Instant.parse("2026-12-01T00:00:00Z");
    static final Instant DEC_15 = Instant.parse("2026-12-15T02:00:00Z");

    final EditorApiTest.MovableClock clock = new EditorApiTest.MovableClock(Instant.parse("2026-10-20T00:00:00Z"));
    final InMemoryStores stores = new InMemoryStores();
    final SimulationReportStore reports = SimulationReportStore.inMemory();
    final TestCaller caller = new TestCaller("bank-a", "lan");
    RuleEngines engines = engines(EngineSettings.defaults().withDraftAuthoring(true));
    VersionService versions = engines.versionService();
    RuleEngine engine = engines.ruleEngine();
    SimulationReportService service = engines.simulationReports();

    RuleEngines engines(EngineSettings settings) {
        return RuleEngines.create(new EngineContext(caller, caller, clock, stores, stores, settings,
                MaskingPolicy.withKey(MaskingKey.of("report-test-key-of-32-bytes-long!".getBytes(StandardCharsets.UTF_8))),
                HostFunctions.none(), reports), stores, stores, stores);
    }

    void useSettings(EngineSettings settings) {
        engines = engines(settings);
        versions = engines.versionService();
        engine = engines.ruleEngine();
        service = engines.simulationReports();
    }

    void publish(String tenant, String ruleSet, byte[] document) {
        caller.become(tenant, "lan", AUTHOR);
        int number = versions.findRuleSet(ruleSet).isPresent()
                ? versions.importVersion(ruleSet, ImportRequest.yaml(document)).version()
                : versions.importRuleSet(ImportRequest.yaml(document)).version();
        RuleSetVersion requested = versions.requestPublish(ruleSet, number, WriteOptions.none());
        caller.become(tenant, "minh", APPROVER);
        versions.approve(ruleSet, number,
                new WriteOptions(null, requested.lockVersion(), null).expectingContent(requested.canonicalHash()));
    }

    RuleSetVersion draft(String ruleSet, byte[] document) {
        caller.become("bank-a", "lan", AUTHOR);
        return versions.importVersion(ruleSet, ImportRequest.yaml(document));
    }

    void cardFeeWithNovemberHistory() {
        publish("bank-a", "card-fee", EffectiveDatesAndReplayTest.cardFee("2026-11-01T00:00:00Z", "50000 VND"));
        caller.become("bank-a", "teller", TELLER);
        for (int i = 0; i < 100; i++) {
            clock.set(NOV_01.plus(Duration.ofHours(7 * i + 1)));
            String tier = (i % 6 == 0 || i == 99) ? "STANDARD" : "GOLD";
            engine.evaluate(DecisionRequest.of("card-fee", DecisionInput.of(Map.of("tier", tier, "txCount", 1))));
        }
        clock.set(DEC_15);
        draft("card-fee", EffectiveDatesAndReplayTest.cardFee("2026-11-01T00:00:00Z", "30000 VND"));
    }

    SimulationReport replayReport(int version) {
        caller.become("bank-a", "lan", AUTHOR);
        return service.create("card-fee", version, SimulationReportRequest.replay(NOV_01, DEC_01, null));
    }

    @Test
    void RLE_199_a_replay_report_counts_changed_unchanged_and_keeps_readings_of_the_changed_rows() {
        // Given card-fee v1 with 100 logged November decisions and v2 in DRAFT, which lowers the fee to 30,000 VND
        cardFeeWithNovemberHistory();
        caller.become("bank-a", "lan", AUTHOR);

        // When Lan runs a report over November
        SimulationReport report = service.create("card-fee", 2, SimulationReportRequest.replay(NOV_01, DEC_01, 100));

        // Then 18 of 100 changed, bound to the hash of v2, stored once
        assertThat(report.counts()).isEqualTo(new SimulationReport.Counts(100, 18, 82, 0, 0));
        assertThat(report.status()).isEqualTo(SimulationReport.Status.CURRENT);
        assertThat(report.attached()).isFalse();
        assertThat(report.createdBy()).isEqualTo("lan");
        assertThat(report.canonicalHash())
                .isEqualTo(versions.find("card-fee", 2, false).orElseThrow().canonicalHash());
        assertThat(report.scope().source()).isEqualTo(SimulationReportRequest.Source.REPLAY);
        assertThat(report.scope().limit()).isEqualTo(100);
        assertThat(report.languages()).contains("en", "vi");
        assertThat(report.rows()).hasSize(18).allSatisfy(row -> {
            assertThat(row.category()).isEqualTo("CHANGED");
            assertThat(row.before().readings().get("en").shortForm()).contains("50,000 VND");
            assertThat(row.after().readings().get("en").shortForm()).contains("30,000 VND");
        });
        assertThat(report.rowsTruncated()).isFalse();
        assertThat(reports.list("bank-a", "card-fee", 2, 50)).hasSize(1);
    }

    @Test
    void RLE_199_decision_ids_are_given_only_to_a_holder_of_log_read() {
        // Given a report with changed rows
        cardFeeWithNovemberHistory();
        SimulationReport created = replayReport(2);

        // When Lan (no log read) and an auditor read it
        caller.become("bank-a", "minh", APPROVER_READER);
        SimulationReport approverView = service.get("card-fee", 2, created.id());
        caller.become("bank-a", "audit", AUDITOR);
        SimulationReport auditorView = service.get("card-fee", 2, created.id());

        // Then the ids are in the auditor's view only, and nothing else differs
        assertThat(created.rows()).allSatisfy(row -> assertThat(row.decisionId()).isNull());
        assertThat(approverView.rows()).allSatisfy(row -> assertThat(row.decisionId()).isNull());
        assertThat(auditorView.rows()).hasSize(18).allSatisfy(row -> assertThat(row.decisionId()).isNotBlank());
        assertThat(auditorView.counts()).isEqualTo(approverView.counts());
    }

    @Test
    void RLE_199_a_sample_report_names_samples_and_stores_no_input_or_label() {
        // Given loan-tier v1 in force (STANDARD pays 10,000 VND) and draft v2 (5,000 VND)
        publish("bank-a", "loan-tier", tieredFee("loan-tier", "10000 VND"));
        draft("loan-tier", tieredFee("loan-tier", "5000 VND"));
        caller.become("bank-a", "lan", AUTHOR);

        // When Lan runs a report over a GOLD sample, which does not change, and a STANDARD one, which does
        SimulationReport report = service.create("loan-tier", 2, SimulationReportRequest.samples(List.of(
                DecisionInput.of(Map.of("tier", "GOLD")), DecisionInput.of(Map.of("tier", "STANDARD")))));

        // Then one changed, one unchanged; the changed row is S2, and the stored rows hold no sample value
        assertThat(report.counts()).isEqualTo(new SimulationReport.Counts(2, 1, 1, 0, 0));
        assertThat(report.rows()).hasSize(1);
        assertThat(report.rows().getFirst().reference()).isEqualTo("S2");
        assertThat(report.rows().getFirst().decisionId()).isNull();
        assertThat(report.rows().getFirst().before().readings().get("en").shortForm()).contains("10,000 VND");
        assertThat(report.rows().getFirst().after().readings().get("en").shortForm()).contains("5,000 VND");
        String stored = reports.find("bank-a", "loan-tier", 2, report.id()).orElseThrow().rowsJson();
        assertThat(stored).doesNotContain("STANDARD").doesNotContain("GOLD").doesNotContain("label");
        assertThat(report.scope().source()).isEqualTo(SimulationReportRequest.Source.SAMPLES);
        assertThat(report.scope().from()).isNull();
    }

    @Test
    void RLE_199_a_sample_the_version_fails_on_is_counted_as_failed_and_listed() {
        // Given card-fee, whose rows call today(), which a sample without a clock cannot evaluate
        cardFeeWithNovemberHistory();
        caller.become("bank-a", "lan", AUTHOR);

        // When Lan runs a report over one sample
        SimulationReport report = service.create("card-fee", 2, SimulationReportRequest.samples(List.of(
                DecisionInput.of(Map.of("tier", "STANDARD", "txCount", 1)))));

        // Then it is counted as failed, not as changed, and its row says so on both sides
        assertThat(report.counts()).isEqualTo(new SimulationReport.Counts(1, 0, 0, 1, 0));
        assertThat(report.rows()).singleElement().satisfies(row -> {
            assertThat(row.category()).isEqualTo("FAILED");
            assertThat(row.after().kind()).isEqualTo("FAILED");
        });
    }

    @Test
    void RLE_199_a_repeat_with_the_same_key_returns_the_first_report_and_runs_nothing() {
        // Given a report run with an Idempotency-Key
        cardFeeWithNovemberHistory();
        caller.become("bank-a", "lan", AUTHOR);
        SimulationReportRequest request = SimulationReportRequest.replay(NOV_01, DEC_01, null)
                .withIdempotencyKey("rep-1");
        SimulationReport first = service.create("card-fee", 2, request);

        // When the same request is sent again
        SimulationReport again = service.create("card-fee", 2, request);

        // Then it is the same report and only one is stored
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.counts()).isEqualTo(first.counts());
        assertThat(reports.list("bank-a", "card-fee", 2, 50)).hasSize(1);
        // And the same key for another request is a mismatch, as for every write
        assertThatThrownBy(() -> service.create("card-fee", 2, SimulationReportRequest.replay(NOV_01, DEC_01, 5)
                .withIdempotencyKey("rep-1"))).isInstanceOfSatisfying(HorusBpmException.class,
                        e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_KEY_REUSE_MISMATCH"));
    }

    @Test
    void RLE_199_needs_rules_simulate_and_stores_nothing_without_it() {
        // Given a draft
        cardFeeWithNovemberHistory();
        caller.become("bank-a", "minh", APPROVER);

        // When a caller without rules:simulate asks
        // Then 403 and no report
        assertThatThrownBy(() -> service.create("card-fee", 2, SimulationReportRequest.replay(NOV_01, DEC_01, null)))
                .isInstanceOf(PermissionDeniedException.class);
        assertThat(reports.list("bank-a", "card-fee", 2, 50)).isEmpty();
    }

    @Test
    void RLE_199_a_simulate_only_caller_runs_a_replay_without_seeing_decision_ids() {
        // Given a draft and a caller who may simulate but not read the decision log
        cardFeeWithNovemberHistory();
        caller.become("bank-a", "sim", SIMULATE_ONLY);

        // When the report is run
        SimulationReport report = service.create("card-fee", 2, SimulationReportRequest.replay(NOV_01, DEC_01, null));

        // Then it runs and holds no decision id for this caller; the stored report keeps them for an auditor
        assertThat(report.counts().changed()).isEqualTo(18);
        assertThat(report.rows()).allSatisfy(row -> assertThat(row.decisionId()).isNull());
        assertThat(reports.find("bank-a", "card-fee", 2, report.id()).orElseThrow().rowsJson()).contains("decisionId");
    }

    @Test
    void RLE_199_the_period_is_bounded_like_the_history_simulation() {
        // Given a draft
        cardFeeWithNovemberHistory();
        caller.become("bank-a", "lan", AUTHOR);

        // When the period is 40 days
        // Then it is refused as :simulate-history refuses it
        assertThatThrownBy(() -> service.create("card-fee", 2,
                SimulationReportRequest.replay(NOV_01, NOV_01.plus(Duration.ofDays(40)), null)))
                .isInstanceOfSatisfying(HorusBpmException.class,
                        e -> assertThat(e.code()).isEqualTo("QUERY_INVALID"));
    }

    @Test
    void RLE_200_a_report_is_out_of_date_once_the_draft_changes() {
        // Given a report on draft v2, then the draft replaced by a fee of 20,000 VND
        cardFeeWithNovemberHistory();
        SimulationReport report = replayReport(2);
        RuleSetVersion v2 = versions.find("card-fee", 2, false).orElseThrow();
        versions.replaceDraft("card-fee", 2,
                ImportRequest.yaml(EffectiveDatesAndReplayTest.cardFee("2026-11-01T00:00:00Z", "20000 VND")),
                new WriteOptions(null, v2.lockVersion(), null));

        // When the report is read
        SimulationReport read = service.get("card-fee", 2, report.id());

        // Then it reads as out of date and still holds the hash it was run on
        assertThat(read.status()).isEqualTo(SimulationReport.Status.OUT_OF_DATE);
        assertThat(read.canonicalHash()).isEqualTo(v2.canonicalHash());
        assertThat(service.list("card-fee", 2)).singleElement()
                .satisfies(listed -> assertThat(listed.status()).isEqualTo(SimulationReport.Status.OUT_OF_DATE));
    }

    @Test
    void RLE_200_a_report_is_never_changed_and_a_list_has_no_rows() {
        // Given two reports on the same draft
        cardFeeWithNovemberHistory();
        SimulationReport first = replayReport(2);
        clock.set(DEC_15.plusSeconds(60));
        SimulationReport second = replayReport(2);

        // When the list is read
        List<SimulationReport> listed = service.list("card-fee", 2);

        // Then newest first, without rows, and storing the same id again is refused
        assertThat(listed).extracting(SimulationReport::id).containsExactly(second.id(), first.id());
        assertThat(listed).allSatisfy(r -> assertThat(r.rows()).isEmpty());
        assertThat(listed.getFirst().counts().changed()).isEqualTo(18);
        assertThatThrownBy(() -> reports.insert(reports.find("bank-a", "card-fee", 2, first.id()).orElseThrow()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void RLE_200_the_report_of_another_tenant_is_not_found() {
        // Given a report of bank-a
        cardFeeWithNovemberHistory();
        SimulationReport report = replayReport(2);
        publish("bank-b", "other-fee", other("other-fee"));
        caller.become("bank-b", "lan", AUTHOR);

        // When bank-b asks for bank-a's report by its id, under its own rule set and under bank-a's name
        // Then it is not found and the answer does not say whether it exists
        assertThatThrownBy(() -> service.get("other-fee", 1, report.id()))
                .isInstanceOf(SimulationReportNotFoundException.class);
        assertThatThrownBy(() -> service.get("card-fee", 2, report.id())).isInstanceOf(RuleSetNotFoundException.class);
        assertThatThrownBy(() -> service.list("card-fee", 2)).isInstanceOf(RuleSetNotFoundException.class);
    }

    @Test
    void RLE_200_masked_values_are_never_stored_and_masked_decisions_are_counted_as_not_replayable() {
        // Given a fee whose national id is masked in the log, one decision with a value and one with null
        String masked = """
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
                  - { id: R2, when: {}, then: { fee: "%s" } }
                """;
        publish("bank-a", "id-check-fee", masked.formatted("20000 VND").getBytes(StandardCharsets.UTF_8));
        caller.become("bank-a", "teller", TELLER);
        clock.set(Instant.parse("2026-11-10T03:00:00Z"));
        engine.evaluate(DecisionRequest.of("id-check-fee",
                DecisionInput.of(Map.of("nationalId", "079201001234", "tier", "STANDARD"))));
        clock.set(Instant.parse("2026-11-11T03:00:00Z"));
        Map<String, Object> nullId = new HashMap<>();
        nullId.put("nationalId", null);
        nullId.put("tier", "STANDARD");
        engine.evaluate(DecisionRequest.of("id-check-fee", DecisionInput.of(nullId)));
        clock.set(DEC_15);
        draft("id-check-fee", masked.formatted("15000 VND").getBytes(StandardCharsets.UTF_8));

        // When Lan runs a report over November
        caller.become("bank-a", "lan", AUTHOR);
        SimulationReport report = service.create("id-check-fee", 2, SimulationReportRequest.replay(NOV_01, DEC_01, null));

        // Then one decision is not replayable, one changed, and no value or masked fingerprint is stored
        assertThat(report.counts()).isEqualTo(new SimulationReport.Counts(2, 1, 0, 0, 1));
        assertThat(report.rows()).hasSize(1);
        String stored = reports.find("bank-a", "id-check-fee", 2, report.id()).orElseThrow().rowsJson();
        assertThat(stored).doesNotContain("079201001234").doesNotContain("nationalId");
        assertThat(String.valueOf(report)).doesNotContain("079201001234");
    }

    @Test
    void RLE_201_request_publish_attaches_a_current_report_and_the_event_names_it() {
        // Given a report run on draft v2
        cardFeeWithNovemberHistory();
        SimulationReport report = replayReport(2);

        // When Lan requests publication with the report
        caller.become("bank-a", "lan", AUTHOR);
        RuleSetVersion requested = versions.requestPublish("card-fee", 2,
                WriteOptions.none().withSimulationReport(report.id()));

        // Then the request carries it, and the event of the request names its id
        assertThat(requested.status().name()).isEqualTo("PENDING_APPROVAL");
        assertThat(service.get("card-fee", 2, report.id()).attached()).isTrue();
        assertThat(reports.attachedReport("bank-a", "card-fee", 2)).contains(report.id());
        assertThat(stores.outbox).anySatisfy(row -> assertThat(row.payload())
                .contains("rules.version.publish-requested.v1").contains(report.id()));
    }

    @Test
    void RLE_201_a_report_of_changed_content_cannot_be_attached() {
        // Given a report, then the draft replaced
        cardFeeWithNovemberHistory();
        SimulationReport report = replayReport(2);
        RuleSetVersion v2 = versions.find("card-fee", 2, false).orElseThrow();
        versions.replaceDraft("card-fee", 2,
                ImportRequest.yaml(EffectiveDatesAndReplayTest.cardFee("2026-11-01T00:00:00Z", "20000 VND")),
                new WriteOptions(null, v2.lockVersion(), null));

        // When Lan requests publication with it
        // Then 409 SIMULATION_REPORT_OUT_OF_DATE and the draft stays a draft
        assertThatThrownBy(() -> versions.requestPublish("card-fee", 2,
                WriteOptions.none().withSimulationReport(report.id())))
                .isInstanceOf(SimulationReportOutOfDateException.class);
        assertThat(versions.find("card-fee", 2, false).orElseThrow().status().name()).isEqualTo("DRAFT");
        assertThat(reports.attachedReport("bank-a", "card-fee", 2)).isEmpty();
    }

    @Test
    void RLE_201_a_report_of_another_version_or_an_unknown_id_is_not_found() {
        // Given a report on v2 and a second draft v3
        cardFeeWithNovemberHistory();
        SimulationReport report = replayReport(2);
        draft("card-fee", EffectiveDatesAndReplayTest.cardFee("2026-11-01T00:00:00Z", "10000 VND"));

        // When Lan attaches v2's report to v3, or an id nobody issued
        // Then 404 SIMULATION_REPORT_NOT_FOUND for both
        assertThatThrownBy(() -> versions.requestPublish("card-fee", 3,
                WriteOptions.none().withSimulationReport(report.id())))
                .isInstanceOf(SimulationReportNotFoundException.class);
        assertThatThrownBy(() -> versions.requestPublish("card-fee", 3,
                WriteOptions.none().withSimulationReport("01KB2R6Q3C0000000000000009")))
                .isInstanceOf(SimulationReportNotFoundException.class);
    }

    @Test
    void RLE_201_a_rule_set_the_host_lists_needs_a_report_and_others_need_none() {
        // Given the host lists card-fee, and draft v2 of card-fee and a draft of loan-fee
        cardFeeWithNovemberHistory();
        useSettings(EngineSettings.defaults().withDraftAuthoring(true).withRequireSimulationReport(List.of("card-fee")));
        publish("bank-a", "loan-fee", other("loan-fee"));
        draft("loan-fee", other("loan-fee"));
        caller.become("bank-a", "lan", AUTHOR);

        // When Lan requests publication of card-fee v2 without a report
        // Then 409 SIMULATION_REPORT_REQUIRED, and the draft is unchanged
        assertThatThrownBy(() -> versions.requestPublish("card-fee", 2, WriteOptions.none()))
                .isInstanceOf(SimulationReportRequiredException.class);
        assertThat(versions.find("card-fee", 2, false).orElseThrow().status().name()).isEqualTo("DRAFT");

        // And with a report it goes through, while loan-fee, not listed, needs none
        SimulationReport report = replayReport(2);
        assertThat(versions.requestPublish("card-fee", 2, WriteOptions.none().withSimulationReport(report.id()))
                .status().name()).isEqualTo("PENDING_APPROVAL");
        assertThat(versions.requestPublish("loan-fee", 2, WriteOptions.none()).status().name())
                .isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void RLE_201_the_settings_reject_a_name_that_is_not_a_rule_set_name() {
        assertThatThrownBy(() -> EngineSettings.defaults().withRequireSimulationReport(List.of("Card Fee")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("horus.bpm.rules.lifecycle.require-simulation-report");
        assertThatThrownBy(() -> EngineSettings.defaults().withRequireSimulationReport(List.of("card-fee", "card-fee")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("twice");
        assertThat(EngineSettings.defaults().requireSimulationReport()).isEmpty();
    }

    @Test
    void RLE_202_history_readings_read_each_decision_in_the_version_that_made_it() {
        // Given card-fee v1 with November history and draft v2
        cardFeeWithNovemberHistory();
        caller.become("bank-a", "audit", Set.of("rules:read", "rules:simulate", "rules:log:read"));
        var simulation = engine.simulateHistory("card-fee", 2, HistorySimulation.between(NOV_01, DEC_01));

        // When the replay is read
        RuleReadings.HistoryReadings readings = versions.readHistorySimulation(simulation);

        // Then 100 decisions, 18 changed, each side read in its own version, no input in the reading
        assertThat(readings.results()).hasSize(100);
        assertThat(readings.replayedCount()).isEqualTo(100);
        assertThat(readings.changedCount()).isEqualTo(18);
        assertThat(readings.notReplayableCount()).isZero();
        RuleReadings.HistoryItemReading newest = readings.results().getFirst();
        assertThat(newest.changed()).isTrue();
        assertThat(newest.simulated().readings().get("en").shortForm()).contains("30,000 VND");
        assertThat(newest.logged().readings().get("en").shortForm()).contains("50,000 VND");
        assertThat(newest.logged().readings().get("vi").full()).isNotBlank();
        assertThat(String.valueOf(readings)).doesNotContain("STANDARD");
    }

    @Test
    void RLE_202_a_masked_decision_is_listed_as_replay_not_possible_and_never_read() {
        // Given a masked national id, one decision with a value and one without
        String masked = """
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
                  - { id: R2, when: {}, then: { fee: "%s" } }
                """;
        publish("bank-a", "id-check-fee", masked.formatted("20000 VND").getBytes(StandardCharsets.UTF_8));
        caller.become("bank-a", "teller", TELLER);
        clock.set(Instant.parse("2026-11-10T03:00:00Z"));
        engine.evaluate(DecisionRequest.of("id-check-fee",
                DecisionInput.of(Map.of("nationalId", "079201001234", "tier", "STANDARD"))));
        clock.set(DEC_15);
        draft("id-check-fee", masked.formatted("15000 VND").getBytes(StandardCharsets.UTF_8));
        caller.become("bank-a", "audit", Set.of("rules:read", "rules:simulate", "rules:log:read"));

        // When the replay is read in plain language
        RuleReadings.HistoryReadings readings = versions.readHistorySimulation(
                engine.simulateHistory("id-check-fee", 2, HistorySimulation.between(NOV_01, DEC_01)));

        // Then it fails closed: the code, the input name, no sentence and no value
        assertThat(readings.results()).singleElement().satisfies(item -> {
            assertThat(item.code()).isEqualTo(RuleReadings.REPLAY_NOT_POSSIBLE);
            assertThat(item.maskedInputs()).containsExactly("nationalId");
            assertThat(item.simulated()).isNull();
            assertThat(item.logged()).isNull();
            assertThat(item.changed()).isFalse();
        });
        assertThat(readings.notReplayableCount()).isEqualTo(1);
        assertThat(String.valueOf(readings)).doesNotContain("079201001234");
    }

    @Test
    void RLE_202_reading_needs_rules_read() {
        // Given a replay result and a caller without rules:read
        cardFeeWithNovemberHistory();
        caller.become("bank-a", "audit", Set.of("rules:simulate", "rules:log:read"));
        var simulation = engine.simulateHistory("card-fee", 2, HistorySimulation.between(NOV_01, DEC_01));

        // When it is read
        // Then 403
        assertThatThrownBy(() -> versions.readHistorySimulation(simulation))
                .isInstanceOf(PermissionDeniedException.class);
    }

    static byte[] tieredFee(String name, String fee) {
        return ("""
                schemaVersion: 1
                ruleSet: %s
                hitPolicy: first
                inputs:
                  - { name: tier, type: string }
                outputs:
                  - { name: fee, type: money }
                rules:
                  - { id: R1, when: { tier: "== 'GOLD'" }, then: { fee: "0 VND" } }
                  - { id: R2, when: {}, then: { fee: "%s" } }
                """).formatted(name, fee).getBytes(StandardCharsets.UTF_8);
    }

    static byte[] other(String name) {
        return ("""
                schemaVersion: 1
                ruleSet: %s
                hitPolicy: first
                inputs:
                  - { name: tier, type: string }
                outputs:
                  - { name: fee, type: money }
                rules:
                  - { id: R1, when: {}, then: { fee: "10000 VND" } }
                """).formatted(name).getBytes(StandardCharsets.UTF_8);
    }
}
