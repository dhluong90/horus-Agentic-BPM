package io.github.dhluong90.bpm.process.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.AgentReplayCase;
import io.github.dhluong90.bpm.process.api.AgentReplayRun;
import io.github.dhluong90.bpm.process.api.QueryInvalidException;
import io.github.dhluong90.bpm.process.internal.store.AgentReplayStore.RecordedAnswer;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The check of one recorded answer against a candidate (#98): the result check of the result transaction with the
 * candidate's simulated decision, at the recorded time, over the masked copy history keeps. A KYC document assessor's
 * answers; the candidate guardrail refuses a confidence below 0.85.
 */
class AgentReplayCheckTest {

    private static final Instant NOV_3 = Instant.parse("2026-11-03T01:00:00Z");
    private static final Map<String, Object> SCHEMA = Map.of("type", "object",
            "required", List.of("documentType", "confidence", "summary"), "additionalProperties", false,
            "properties", Map.of("documentType", Map.of("type", "string"), "confidence", Map.of("type", "number"),
                    "summary", Map.of("type", "string", "mask", true)));

    /** Simulates the candidate: allow from 0.85 (R9), refuse below (R5); records every case it was given. */
    private final List<DecisionPort.SimulatedCase> simulated = new ArrayList<>();
    private final DecisionPort candidate = new DecisionPort() {
        @Override
        public DecisionOutcome evaluate(String tenantId, DecisionCaller caller, String decisionRef,
                Map<String, Object> inputs, Instant evaluationTime, String idempotencyKey, String correlationId) {
            throw new AssertionError("a replay never evaluates for real");
        }

        @Override
        public List<DecisionOutcome> simulate(String tenantId, String decisionRef, int version,
                List<SimulatedCase> cases) {
            simulated.addAll(cases);
            return cases.stream().<DecisionOutcome>map(c -> ((BigDecimal) c.inputs().get("confidence"))
                    .compareTo(new BigDecimal("0.85")) >= 0
                    ? new DecisionOutcome.Matched(null, Map.of("allow", true), List.of("R9"))
                    : new DecisionOutcome.Matched(null, Map.of("allow", false, "reasons", "LOW_CONFIDENCE"),
                            List.of("R5"))).toList();
        }
    };

    private static Map<String, Object> guard(String onViolation) {
        Map<String, Object> guard = new LinkedHashMap<>();
        guard.put("outputSchema", SCHEMA);
        guard.put("decision", "kyc-document-guardrails");
        guard.put("onViolation", onViolation);
        if ("route-to-human".equals(onViolation)) {
            guard.put("reviewer", "role:KYC_REVIEWER");
        }
        guard.put("fourEyes", true);
        return guard;
    }

    private static RecordedAnswer refusedForReview(String confidence) {
        return new RecordedAnswer(NOV_3, "01H0000000000000000000000A", "I-1", "ASYNC_RESULT_REFUSED", "A-1",
                Map.of("route", "review", "code", "GUARDRAIL_VIOLATION", "ruleIds", List.of("R5"), "output",
                        Map.of("documentType", "PASSPORT", "confidence", new BigDecimal(confidence), "summary", "***")),
                Map.of("guard", guard("route-to-human")));
    }

    private static RecordedAnswer accepted(String confidence, Map<String, Object> payload) {
        return new RecordedAnswer(NOV_3, "01H0000000000000000000000B", "I-2", "ASYNC_RESULT_ACCEPTED", "A-2",
                Map.of("ruleIds", List.of("R9"), "output", Map.of("documentType", "PASSPORT", "confidence",
                        new BigDecimal(confidence), "summary", "***")), payload);
    }

    private AgentReplayCheck check(Map<String, Object> candidateSchema) {
        return new AgentReplayCheck(new JsonSchemaSubsetValidator(), candidate, "kyc-document-guardrails", 3,
                candidateSchema);
    }

    @Test
    void PRE_092_an_answer_refused_by_the_recorded_guardrail_and_allowed_by_the_candidate_is_newly_allowed() {
        // Given an answer of 0.87 that R5 of v2 sent to a reviewer, with its summary masked in history
        RecordedAnswer answer = refusedForReview("0.87");

        // When it is checked against candidate v3, and a reviewer had corrected it
        AgentReplayCase line = check(null).check("bank-a", answer, true);

        // Then it is newly allowed, flagged, and the masked summary was left out of the check
        assertThat(line.recordedOutcome()).isEqualTo("REVIEW");
        assertThat(line.candidateOutcome()).isEqualTo("ALLOWED");
        assertThat(line.change()).isEqualTo("NEWLY_ALLOWED");
        assertThat(line.recordedRuleIds()).containsExactly("R5");
        assertThat(line.candidateRuleIds()).containsExactly("R9");
        assertThat(line.code()).isNull();
        assertThat(line.overriddenByReviewer()).isTrue();
        assertThat(line.maskedFields()).isEqualTo(1);
        assertThat(simulated).singleElement().satisfies(c -> {
            assertThat(c.inputs()).containsOnlyKeys("documentType", "confidence");
            assertThat(c.evaluationTime()).isEqualTo(NOV_3);
        });
    }

    @Test
    void PRE_092_an_answer_the_candidate_refuses_follows_the_recorded_route() {
        // Given an allowed answer of 0.80 on a step whose guardrail fails the job, and one on a reviewed step
        AgentReplayCase failed = check(null).check("bank-a", accepted("0.80", Map.of("guard", guard("fail"))), false);
        AgentReplayCase reviewed = check(null).check("bank-a", accepted("0.80",
                Map.of("guard", guard("route-to-human"))), false);

        // Then the first is newly refused and the second newly routed to review, both with the violation code
        assertThat(failed.change()).isEqualTo("NEWLY_REFUSED");
        assertThat(failed.candidateOutcome()).isEqualTo("REFUSED");
        assertThat(failed.code()).isEqualTo("GUARDRAIL_VIOLATION");
        assertThat(reviewed.change()).isEqualTo("NEWLY_ROUTED_TO_REVIEW");
        assertThat(reviewed.candidateRuleIds()).containsExactly("R5");
    }

    @Test
    void PRE_092_a_candidate_output_schema_replaces_the_recorded_one() {
        // Given a candidate schema that allows only ID_CARD
        Map<String, Object> narrower = new LinkedHashMap<>(SCHEMA);
        narrower.put("properties", Map.of("documentType", Map.of("type", "string", "enum", List.of("ID_CARD")),
                "confidence", Map.of("type", "number"), "summary", Map.of("type", "string", "mask", true)));

        // When an allowed PASSPORT answer is checked
        AgentReplayCase line = check(narrower).check("bank-a", accepted("0.95",
                Map.of("guard", guard("route-to-human"))), false);

        // Then the schema refuses it before any decision, and it goes to the reviewer
        assertThat(line.change()).isEqualTo("NEWLY_ROUTED_TO_REVIEW");
        assertThat(line.code()).isEqualTo("AGENT_SCHEMA_INVALID");
        assertThat(simulated).isEmpty();
    }

    @Test
    void PRE_094_a_recorded_answer_without_a_guard_spec_is_not_replayed() {
        // Given an answer whose job payload has no guard spec, and one whose job row is gone
        AgentReplayCase noGuard = check(null).check("bank-a", accepted("0.95", Map.of("schemaRetries", 0)), false);
        AgentReplayCase noJob = check(null).check("bank-a", accepted("0.95", null), false);

        // Then both are GUARD_SPEC_INVALID and nothing was evaluated
        assertThat(List.of(noGuard, noJob)).allSatisfy(line -> {
            assertThat(line.change()).isEqualTo("NOT_REPLAYED");
            assertThat(line.candidateOutcome()).isEqualTo("NOT_REPLAYED");
            assertThat(line.code()).isEqualTo("GUARD_SPEC_INVALID");
        });
        assertThat(simulated).isEmpty();
    }

    @Test
    void PRE_092_the_summary_counts_each_change_and_overrides_only_among_newly_allowed() {
        // Given one line of each change, two overridden
        List<AgentReplayCase> lines = List.of(
                line("UNCHANGED", true, 1), line("NEWLY_ALLOWED", true, 1), line("NEWLY_ALLOWED", false, 0),
                line("NEWLY_REFUSED", false, 0), line("NEWLY_ROUTED_TO_REVIEW", false, 2), line("NOT_REPLAYED", false, 0));

        // When they are added to an empty summary
        AgentReplayRun.Summary summary = AgentReplays.add(AgentReplayRun.Summary.EMPTY, lines);

        // Then an override counts only where the candidate would let the corrected answer through
        assertThat(summary).isEqualTo(new AgentReplayRun.Summary(6, 1, 2, 1, 1, 1, 1, 3, 0));
    }

    /** A port whose candidate rule set declares the three schema fields and reads only the named ones. */
    private DecisionPort candidateReading(String... reads) {
        return new DecisionPort() {
            @Override
            public DecisionOutcome evaluate(String tenantId, DecisionCaller caller, String decisionRef,
                    Map<String, Object> inputs, Instant evaluationTime, String idempotencyKey, String correlationId) {
                throw new AssertionError("a replay never evaluates for real");
            }

            @Override
            public DecisionSignature signature(String tenantId, String decisionRef, int version) {
                return new DecisionSignature.Found(decisionRef, version, "FIRST",
                        Map.of("documentType", "string", "confidence", "decimal", "summary", "string"),
                        Map.of("allow", "boolean"), false, java.util.Set.of(reads));
            }

            @Override
            public List<DecisionOutcome> simulate(String tenantId, String decisionRef, int version,
                    List<SimulatedCase> cases) {
                simulated.addAll(cases);
                return cases.stream().<DecisionOutcome>map(c -> new DecisionOutcome.Matched(null,
                        Map.of("allow", true), List.of("R9"))).toList();
            }
        };
    }

    @Test
    void PRE_092_a_candidate_rule_that_reads_a_masked_field_is_not_replayed_and_never_unchanged() {
        // Given an allowed answer with the summary masked, and a candidate whose rule R7 reads the summary
        AgentReplayCheck reads = new AgentReplayCheck(new JsonSchemaSubsetValidator(), candidateReading("confidence",
                "summary"), "kyc-document-guardrails", 4, null);

        // When it is checked
        AgentReplayCase line = reads.check("bank-a", accepted("0.95", Map.of("guard", guard("route-to-human"))), false);

        // Then it is not replayed, says why, and nothing was evaluated
        assertThat(line.change()).isEqualTo("NOT_REPLAYED");
        assertThat(line.candidateOutcome()).isEqualTo("NOT_REPLAYED");
        assertThat(line.code()).isEqualTo("MASKED_INPUT_REQUIRED");
        assertThat(line.maskedFields()).isEqualTo(1);
        assertThat(line.candidateRuleIds()).isEmpty();
        assertThat(simulated).isEmpty();
    }

    @Test
    void PRE_092_a_declared_input_no_rule_reads_does_not_stop_the_replay() {
        // Given the summary is declared by the candidate rule set but only the confidence is read
        AgentReplayCheck declaredOnly = new AgentReplayCheck(new JsonSchemaSubsetValidator(),
                candidateReading("confidence"), "kyc-document-guardrails", 4, null);

        // When the answer with the masked summary is checked
        AgentReplayCase line = declaredOnly.check("bank-a", accepted("0.95", Map.of("guard", guard("fail"))), false);

        // Then it is replayed as before
        assertThat(line.change()).isEqualTo("UNCHANGED");
        assertThat(line.code()).isNull();
        assertThat(line.maskedFields()).isEqualTo(1);
    }

    @Test
    void PRE_092_a_candidate_schema_that_tests_the_value_of_a_masked_field_is_not_replayed() {
        // Given a candidate schema that newly requires the summary to be 20 characters, which history cannot show
        Map<String, Object> strict = new LinkedHashMap<>(SCHEMA);
        strict.put("properties", Map.of("documentType", Map.of("type", "string"), "confidence",
                Map.of("type", "number"), "summary", Map.of("type", "string", "mask", true, "minLength", 20)));

        // When an answer with the summary masked is checked, and one against a schema with only the type
        AgentReplayCase tested = check(strict).check("bank-a", accepted("0.95",
                Map.of("guard", guard("route-to-human"))), false);
        AgentReplayCase typeOnly = check(null).check("bank-a", accepted("0.95",
                Map.of("guard", guard("route-to-human"))), false);

        // Then the first is MASKED_INPUT_REQUIRED, and the second, whose recorded schema is unchanged, is replayed
        assertThat(tested.change()).isEqualTo("NOT_REPLAYED");
        assertThat(tested.code()).isEqualTo("MASKED_INPUT_REQUIRED");
        assertThat(typeOnly.change()).isEqualTo("UNCHANGED");
    }

    @Test
    void PRE_092_an_answer_with_nothing_masked_is_replayed_whatever_the_candidate_reads() {
        // Given a candidate that reads the summary and an answer whose summary was kept in plain text
        Map<String, Object> plain = new LinkedHashMap<>(Map.of("documentType", "PASSPORT", "confidence",
                new BigDecimal("0.95"), "summary", "Passport of the applicant"));
        RecordedAnswer answer = new RecordedAnswer(NOV_3, "01H0000000000000000000000C", "I-3", "ASYNC_RESULT_ACCEPTED",
                "A-3", Map.of("ruleIds", List.of("R9"), "output", plain), Map.of("guard", guard("fail")));
        AgentReplayCheck reads = new AgentReplayCheck(new JsonSchemaSubsetValidator(), candidateReading("summary"),
                "kyc-document-guardrails", 4, null);

        // When it is checked
        AgentReplayCase line = reads.check("bank-a", answer, false);

        // Then it is replayed
        assertThat(line.change()).isEqualTo("UNCHANGED");
        assertThat(line.maskedFields()).isZero();
    }

    @Test
    void PRE_092_the_summary_counts_masked_input_lines_apart_from_other_lines_not_replayed() {
        // Given one line not replayed for a missing guard spec and two for a masked input
        List<AgentReplayCase> lines = List.of(
                new AgentReplayCase("A", "I", NOV_3, "ALLOWED", "NOT_REPLAYED", "NOT_REPLAYED", List.of(), List.of(),
                        "GUARD_SPEC_INVALID", false, 0),
                new AgentReplayCase("B", "I", NOV_3, "ALLOWED", "NOT_REPLAYED", "NOT_REPLAYED", List.of(), List.of(),
                        "MASKED_INPUT_REQUIRED", false, 1),
                new AgentReplayCase("C", "I", NOV_3, "REVIEW", "NOT_REPLAYED", "NOT_REPLAYED", List.of(), List.of(),
                        "MASKED_INPUT_REQUIRED", true, 2));

        // When they are added
        AgentReplayRun.Summary summary = AgentReplays.add(AgentReplayRun.Summary.EMPTY, lines);

        // Then each reason has its own count, and no line is unchanged
        assertThat(summary).isEqualTo(new AgentReplayRun.Summary(3, 0, 0, 0, 0, 0, 1, 2, 2));
    }

    @Test
    void PRE_092_a_report_cursor_moves_only_within_its_own_run() {
        String cursor = AgentReplays.cursor("01JRUN0000000000000000000A", 499);

        assertThat(AgentReplays.afterSeq("01JRUN0000000000000000000A", cursor)).isEqualTo(499);
        assertThat(AgentReplays.afterSeq("01JRUN0000000000000000000A", null)).isEqualTo(-1);
        assertThatThrownBy(() -> AgentReplays.afterSeq("01JRUN0000000000000000000B", cursor))
                .isInstanceOf(QueryInvalidException.class);
        assertThatThrownBy(() -> AgentReplays.afterSeq("01JRUN0000000000000000000A", "%%%"))
                .isInstanceOf(QueryInvalidException.class);
    }

    @Test
    void PRE_094_replay_settings_may_be_lowered_never_raised() {
        assertThat(ReplaySettings.defaults()).isEqualTo(new ReplaySettings(500, 50_000, Duration.ofDays(30)));
        assertThat(new ReplaySettings(1, 1, Duration.ofHours(1)).maxCases()).isEqualTo(1);
        assertThatThrownBy(() -> new ReplaySettings(500, 50_001, Duration.ofDays(30)))
                .hasMessageContaining("replay.max-cases");
        assertThatThrownBy(() -> new ReplaySettings(0, 100, Duration.ofDays(30)))
                .hasMessageContaining("replay.page-size");
        assertThatThrownBy(() -> new ReplaySettings(500, 100, Duration.ofMinutes(59)))
                .hasMessageContaining("replay.retention");
    }

    private static AgentReplayCase line(String change, boolean overridden, int masked) {
        return new AgentReplayCase("A", "I", NOV_3, "REVIEW", "ALLOWED", change, List.of(), List.of(), null, overridden,
                masked);
    }
}
