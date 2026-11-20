package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.AgentReplayCase;
import io.github.dhluong90.bpm.process.api.AgentReplayRequest;
import io.github.dhluong90.bpm.process.api.AgentReplayRun;
import io.github.dhluong90.bpm.process.api.GuardrailContractInvalidException;
import io.github.dhluong90.bpm.process.api.GuardrailNotFoundException;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.Page;
import io.github.dhluong90.bpm.process.api.PageRequest;
import io.github.dhluong90.bpm.process.api.PermissionDeniedException;
import io.github.dhluong90.bpm.process.api.QueryInvalidException;
import io.github.dhluong90.bpm.process.api.ReplayNotFoundException;
import io.github.dhluong90.bpm.process.api.ReplayTooLargeException;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StepCompletion;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.api.TaskView;
import io.github.dhluong90.bpm.process.internal.service.AgentLabelLimits;
import io.github.dhluong90.bpm.process.internal.service.AgentReplays;
import io.github.dhluong90.bpm.process.internal.service.AsyncStepExecutor.Claim;
import io.github.dhluong90.bpm.process.internal.service.ReplaySettings;
import io.github.dhluong90.bpm.process.internal.store.JobStore;
import io.github.dhluong90.bpm.process.spi.AdapterResult;
import io.github.dhluong90.bpm.process.test.StubAgentAdapter;
import io.github.dhluong90.bpm.process.test.TestClock;
import io.github.dhluong90.bpm.rules.api.MaskingKey;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Offline replay of recorded agent answers against a candidate guardrail (#98, PRE-092 to PRE-095), on PostgreSQL with
 * the real Rule Engine behind the process starter's decision port.
 *
 * <p>KYC onboarding: the agent {@code documents.kyc} reads a customer's identity document and answers its type, its
 * expiry date and a confidence. The bank's {@code kyc-document-guardrails} sends an expired document (R2) or a
 * low-confidence reading (R5) to a KYC reviewer. Version 2, in force, refuses a confidence below 0.90; draft version 3
 * lowers that to 0.85. Every answer is recorded by the engine itself: started, claimed, answered by the stub agent and
 * checked in its result transaction. Replicas share one {@link TestClock}; no test sleeps.
 */
class AgentReplayIT {

    private static final Instant NOV_1 = Instant.parse("2026-11-01T01:00:00Z");
    private static final String AGENT = "documents.kyc";
    private static final String GUARDRAILS = "kyc-document-guardrails";
    private static final String STEP = "assess-documents";
    private static final String KYC = "kyc-onboarding";
    private static final LocalDate FROM = LocalDate.parse("2026-11-01");
    private static final LocalDate TO = LocalDate.parse("2026-11-30");
    private static final MaskingKey HOST_KEY = MaskingKey.of("k1", "0123456789abcdef0123456789abcdef"
            .getBytes(StandardCharsets.UTF_8));

    /** Rule sets outlive a test on the shared database, so every test runs in a tenant of its own. */
    private static final AtomicInteger TENANTS = new AtomicInteger();

    private final TestClock clock = TestClock.at(NOV_1);
    private final String tenant = "bank-replay-" + TENANTS.incrementAndGet();
    /** The answer the stub agent gives per customer id. */
    private final Map<String, Map<String, Object>> answers = new HashMap<>();
    private final StubAgentAdapter agent = StubAgentAdapter.of(AGENT, invocation ->
            AdapterResult.completed(answers.get(String.valueOf(invocation.inputs().get("customerId")))));
    private final AtomicInteger customers = new AtomicInteger(10_000);
    private TestEngine pod;

    @BeforeEach
    void start() {
        TestEngine.reset();
        pod = TestEngine.replica("pod-1", clock).withRuleEngine().decisionPort(StarterDecisionPorts::over)
                .agents(agent).inputMasker((tenantId, plain) -> HOST_KEY.forTenant(tenantId).fingerprint(plain))
                .labelLimits(AgentLabelLimits.DEFAULTS).build();
        pipeline();
    }

    @AfterEach
    void stop() {
        pod.close();
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------

    private void pipeline() {
        pod.identity.as(tenant, "pipeline", "processes:deploy", "processes:start", "processes:read",
                "processes:task:work");
    }

    private void replayer() {
        pod.identity.as(tenant, "an", "processes:agent:replay", "processes:read");
    }

    /** v1 and v2 published through four eyes, v2 in force; v3 left a draft, the candidate. */
    private void publishGuardrails() {
        pod.ruleIdentity.as(tenant, "lan");
        pod.rules.versionService().importRuleSet(io.github.dhluong90.bpm.rules.api.ImportRequest.yaml(
                TestEngine.resource("/rules/kyc-document-guardrails-v1.yaml")));
        publish(GUARDRAILS, 1);
        pod.ruleIdentity.as(tenant, "lan");
        pod.rules.versionService().importVersion(GUARDRAILS, io.github.dhluong90.bpm.rules.api.ImportRequest.yaml(
                TestEngine.resource("/rules/kyc-document-guardrails-v2.yaml")));
        publish(GUARDRAILS, 2);
        pod.ruleIdentity.as(tenant, "lan");
        pod.rules.versionService().importVersion(GUARDRAILS, io.github.dhluong90.bpm.rules.api.ImportRequest.yaml(
                TestEngine.resource("/rules/kyc-document-guardrails-v3.yaml")));
        pod.ruleIdentity.as(null, null);
    }

    private void publish(String ruleSet, int version) {
        pod.ruleIdentity.as(tenant, "lan");
        long requested = pod.rules.versionService().requestPublish(ruleSet, version, WriteOptions.none()).lockVersion();
        pod.ruleIdentity.as(tenant, "minh");
        pod.rules.versionService().approve(ruleSet, version, new WriteOptions(null, requested, null));
        pod.ruleIdentity.as(null, null);
    }

    private void deploy() {
        pod.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                TestEngine.resource("/bpmn/kyc-agent-onboarding.bpmn"), null));
    }

    private static Map<String, Object> answer(String documentType, String confidence, String expiryDate) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documentType", documentType);
        out.put("confidence", new BigDecimal(confidence));
        out.put("expiryDate", expiryDate);
        out.put("summary", "Passport of the applicant, national id 079088001234");
        return out;
    }

    /**
     * Records one answer per entry through the engine: the instance starts, its agent job is claimed and the stub agent
     * answers it, checked in the result transaction. Batches of 16 share one clock reading; the clock then moves on.
     *
     * @return the instance id per customer id, in order
     */
    private Map<String, String> record(List<Map<String, Object>> planned, Duration step) {
        Map<String, String> instances = new LinkedHashMap<>();
        for (int from = 0; from < planned.size(); from += 16) {
            int to = Math.min(planned.size(), from + 16);
            for (int i = from; i < to; i++) {
                String customer = "C-%05d".formatted(customers.incrementAndGet());
                answers.put(customer, planned.get(i));
                instances.put(customer, pod.services.start(new StartRequest(KYC, customer,
                        Map.of("customerId", customer, "nationalId", "079088001234"), null)).id());
            }
            List<Claim> claims = pod.async.claim(JobStore.ASYNC_AGENT, 16);
            assertThat(claims).as(() -> TestEngine.jdbc().sql("SELECT event_type || ' ' || details::text FROM pre_history "
                    + "WHERE tenant_id = ? AND event_type LIKE 'ASYNC%' ORDER BY occurred_at, id").param(tenant)
                    .query(String.class).list().toString()).hasSize(to - from);
            for (Claim claim : claims) {
                assertThat(pod.async.runNow(claim)).hasValueSatisfying(outcome -> assertThat(outcome)
                        .isIn(StepCompletion.Outcome.ACCEPTED, StepCompletion.Outcome.REFUSED));
            }
            clock.advance(step);
        }
        return instances;
    }

    /** A KYC reviewer completes the review task of one instance with the given answer. */
    private void review(String instanceId, Map<String, Object> reviewed) {
        String taskId = TestEngine.jdbc().sql("SELECT id FROM pre_task WHERE instance_id = ? AND node_id = ?")
                .params(instanceId, STEP).query(String.class).single();
        pod.identity.as(tenant, "thu", "processes:task:work", "processes:read");
        pod.identity.roles = Set.of("KYC_REVIEWER");
        TaskView task = pod.services.task(taskId);
        TaskView claimed = pod.services.claim(taskId, task.lockVersion(), "claim-" + taskId);
        pod.services.complete(taskId, reviewed, "Checked against the scanned document", claimed.lockVersion(),
                "complete-" + taskId);
        pipeline();
    }

    /** Runs the job poller's replay work until the run is complete; returns the polls it took. */
    private static int replayUntilDone(AgentReplays replays, String runId) {
        for (int polls = 1; polls <= 100; polls++) {
            assertThat(replays.pollOnce()).isPositive();
            String status = TestEngine.jdbc().sql("SELECT status FROM pre_agent_replay WHERE id = ?").param(runId)
                    .query(String.class).single();
            if (!status.equals("QUEUED") && !status.equals("RUNNING")) {
                return polls;
            }
        }
        throw new AssertionError("The replay did not complete in 100 polls");
    }

    private static List<AgentReplayCase> allCases(AgentReplays replays, String runId) {
        List<AgentReplayCase> out = new ArrayList<>();
        String cursor = null;
        do {
            Page<AgentReplayCase> page = replays.cases(runId, new PageRequest(500, cursor));
            out.addAll(page.items());
            cursor = page.nextCursor();
        } while (cursor != null);
        return out;
    }

    private long count(String sql) {
        return TestEngine.jdbc().sql(sql).param(tenant).query(Long.class).single();
    }

    private String instancesFingerprint() {
        return TestEngine.jdbc().sql("SELECT md5(string_agg(id || status || seq || lock_version || variables::text, ','"
                + " ORDER BY id)) FROM pre_instance WHERE tenant_id = ?").param(tenant).query(String.class).single();
    }

    // ---- PRE-092 ----------------------------------------------------------------------------------------------

    @Test
    void PRE_092_candidate_guardrail_replay_reports_newly_allowed_cases_and_reviewer_overrides() {
        // Given 1,200 answers recorded from 1 to 26 November under v2: 1,104 read with confidence 0.95 and allowed,
        // and 96 read with 0.85 to 0.89 and routed to a KYC reviewer by R5
        publishGuardrails();
        deploy();
        List<Map<String, Object>> planned = new ArrayList<>();
        for (int i = 0; i < 1_200; i++) {
            boolean low = i % 12 == 5 && planned.stream().filter(AgentReplayIT::lowConfidence).count() < 96;
            planned.add(answer("PASSPORT", low ? "0.8" + (5 + i % 5) : "0.95", "2031-06-30"));
        }
        Map<String, String> instances = record(planned, Duration.ofHours(8));
        List<String> reviewed = new ArrayList<>();
        for (Map.Entry<String, String> e : instances.entrySet()) {
            if (lowConfidence(answers.get(e.getKey()))) {
                reviewed.add(e.getValue());
            }
        }
        assertThat(reviewed).hasSize(96);
        // And reviewers changed the document type in 7 of the 96, and confirmed 2 others as read
        for (int i = 0; i < 7; i++) {
            Map<String, Object> changed = answer("ID_CARD", "0.97", "2031-06-30");
            changed.remove("summary");
            review(reviewed.get(i), changed);
        }
        for (int i = 7; i < 9; i++) {
            Map<String, Object> same = new LinkedHashMap<>(answers.get(customerOf(instances, reviewed.get(i))));
            same.remove("summary");
            review(reviewed.get(i), same);
        }
        long history = count("SELECT count(*) FROM pre_history WHERE tenant_id = ?");
        long decisions = count("SELECT count(*) FROM rle_decision WHERE tenant_id = ?");
        String live = instancesFingerprint();
        int agentCalls = agent.invocations().size();
        assertThat(agentCalls).isEqualTo(1_200);

        // When a user with processes:agent:replay replays November against draft v3, then reads the report
        clock.advance(Duration.ofDays(10));
        replayer();
        AgentReplays replays = pod.agentReplays(ReplaySettings.defaults());
        AgentReplayRun queued = replays.start(AgentReplayRequest.of(KYC, STEP, FROM, TO, GUARDRAILS, 3));
        int polls = replayUntilDone(replays, queued.id());
        AgentReplayRun run = replays.run(queued.id());
        List<AgentReplayCase> cases = allCases(replays, queued.id());

        // Then the run was queued and replayed in three pages of 500, 500 and 200
        assertThat(queued.status()).isEqualTo("QUEUED");
        assertThat(polls).isEqualTo(3);
        assertThat(run.status()).isEqualTo("COMPLETED");
        // And the report shows 1,104 unchanged and 96 newly allowed, 7 of them answers a reviewer corrected
        assertThat(run.summary()).isEqualTo(new AgentReplayRun.Summary(1_200, 1_104, 96, 0, 0, 7, 0, 1_200));
        assertThat(cases).hasSize(1_200);
        assertThat(cases).filteredOn(c -> c.change().equals("NEWLY_ALLOWED")).hasSize(96).allSatisfy(c -> {
            assertThat(c.recordedOutcome()).isEqualTo("REVIEW");
            assertThat(c.candidateOutcome()).isEqualTo("ALLOWED");
            assertThat(c.recordedRuleIds()).containsExactly("R5");
            assertThat(c.candidateRuleIds()).containsExactly("R9");
            assertThat(c.code()).isNull();
        });
        assertThat(cases).filteredOn(AgentReplayCase::overriddenByReviewer).hasSize(7)
                .extracting(AgentReplayCase::instanceId).containsExactlyInAnyOrderElementsOf(reviewed.subList(0, 7));
        assertThat(cases).filteredOn(c -> c.change().equals("UNCHANGED")).hasSize(1_104)
                .allSatisfy(c -> assertThat(c.candidateRuleIds()).containsExactly("R9"));
        // And each line names the attempt and the instance, and the attempt ids are distinct
        assertThat(cases).extracting(AgentReplayCase::attemptId).doesNotHaveDuplicates().doesNotContainNull();
        assertThat(cases).extracting(AgentReplayCase::instanceId).containsAll(instances.values());
        // And no model or adapter was called, and no history, decision or instance changed
        assertThat(agent.invocations()).hasSize(agentCalls);
        assertThat(count("SELECT count(*) FROM pre_history WHERE tenant_id = ?")).isEqualTo(history);
        assertThat(count("SELECT count(*) FROM rle_decision WHERE tenant_id = ?")).isEqualTo(decisions);
        assertThat(instancesFingerprint()).isEqualTo(live);
    }

    private static boolean lowConfidence(Map<String, Object> answer) {
        return ((BigDecimal) answer.get("confidence")).compareTo(new BigDecimal("0.90")) < 0;
    }

    private String customerOf(Map<String, String> instances, String instanceId) {
        return instances.entrySet().stream().filter(e -> e.getValue().equals(instanceId)).findFirst().orElseThrow()
                .getKey();
    }

    // ---- PRE-093 ----------------------------------------------------------------------------------------------

    @Test
    void PRE_093_replay_is_deterministic_and_today_is_the_recorded_time() {
        // Given answers recorded on 2 and 3 November, one of 3 November for a passport that expires on 10 November
        publishGuardrails();
        deploy();
        clock.advance(Duration.ofDays(1));
        record(List.of(answer("PASSPORT", "0.95", "2031-06-30"), answer("ID_CARD", "0.88", "2030-01-01")),
                Duration.ofDays(1));
        Map<String, String> expiring = record(List.of(answer("PASSPORT", "0.96", "2026-11-10")), Duration.ofDays(1));
        String expiringInstance = expiring.values().iterator().next();

        // When the same replay against v3 runs twice on 20 January 2027, after the passport expired
        clock.advance(Duration.between(clock.instant(), Instant.parse("2027-01-20T03:00:00Z")));
        replayer();
        AgentReplays replays = pod.agentReplays(ReplaySettings.defaults());
        String first = replays.start(AgentReplayRequest.of(KYC, STEP, FROM, TO, GUARDRAILS, 3)).id();
        replayUntilDone(replays, first);
        String second = replays.start(AgentReplayRequest.of(KYC, STEP, FROM, TO, GUARDRAILS, 3)).id();
        replayUntilDone(replays, second);

        // Then today() was 3 November for that case: the passport was valid then, so it is not newly refused
        List<AgentReplayCase> lines = allCases(replays, first);
        assertThat(lines).filteredOn(c -> c.instanceId().equals(expiringInstance)).singleElement().satisfies(c -> {
            assertThat(c.change()).isEqualTo("UNCHANGED");
            assertThat(c.candidateOutcome()).isEqualTo("ALLOWED");
            assertThat(c.candidateRuleIds()).containsExactly("R9");
            assertThat(c.recordedAt()).isEqualTo(Instant.parse("2026-11-03T01:00:00Z"));
        });
        // And the two reports are identical apart from the run id and times
        assertThat(allCases(replays, second)).isEqualTo(lines);
        assertThat(replays.run(second).summary()).isEqualTo(replays.run(first).summary())
                .isEqualTo(new AgentReplayRun.Summary(3, 2, 1, 0, 0, 0, 0, 3));
    }

    // ---- PRE-094 ----------------------------------------------------------------------------------------------

    @Test
    void PRE_094_replay_is_bounded_and_a_candidate_that_breaks_the_contract_fails_before_any_case_runs() {
        // Given four recorded answers, a host whose replay.max-cases is 3, and a candidate without allow
        publishGuardrails();
        pod.ruleIdentity.as(tenant, "lan");
        pod.rules.versionService().importRuleSet(io.github.dhluong90.bpm.rules.api.ImportRequest.yaml(
                TestEngine.resource("/rules/kyc-document-decision.yaml")));
        pod.ruleIdentity.as(null, null);
        deploy();
        record(List.of(answer("PASSPORT", "0.95", "2031-06-30"), answer("PASSPORT", "0.95", "2031-06-30"),
                answer("PASSPORT", "0.95", "2031-06-30"), answer("PASSPORT", "0.95", "2031-06-30")), Duration.ofHours(1));
        replayer();
        AgentReplays small = pod.agentReplays(new ReplaySettings(500, 3, Duration.ofDays(30)));
        AgentReplays replays = pod.agentReplays(ReplaySettings.defaults());

        // When a period of 45 days is asked for, then four answers over a limit of 3, then a candidate without allow
        // Then (a) the period is refused as longer than query.max-range
        assertThatThrownBy(() -> replays.start(AgentReplayRequest.of(KYC, STEP, LocalDate.parse("2026-10-17"), TO,
                GUARDRAILS, 3))).isInstanceOf(QueryInvalidException.class).hasMessageContaining("45 days");
        // (b) the run is refused naming the count and the limit
        assertThatThrownBy(() -> small.start(AgentReplayRequest.of(KYC, STEP, FROM, TO, GUARDRAILS, 3)))
                .isInstanceOfSatisfying(ReplayTooLargeException.class, e -> {
                    assertThat(e.code()).isEqualTo("REPLAY_TOO_LARGE");
                    assertThat(e.cases()).isEqualTo(4);
                    assertThat(e.limit()).isEqualTo(3);
                });
        // (c) the candidate breaks the guardrail contract, and no run is created
        assertThatThrownBy(() -> replays.start(AgentReplayRequest.of(KYC, STEP, FROM, TO, "kyc-document-decision", 1)))
                .isInstanceOfSatisfying(GuardrailContractInvalidException.class, e -> assertThat(e.problems())
                        .anySatisfy(p -> assertThat(p.message()).contains("declares no output allow: boolean")));
        assertThat(count("SELECT count(*) FROM pre_agent_replay WHERE tenant_id = ?")).isZero();

        // And (d) when one recorded job has lost its guard spec, that case is GUARD_SPEC_INVALID and the run goes on
        String brokenJob = TestEngine.jdbc().sql("SELECT id FROM pre_timer WHERE tenant_id = ? ORDER BY id LIMIT 1")
                .param(tenant).query(String.class).single();
        TestEngine.jdbc().sql("UPDATE pre_timer SET payload = payload - 'guard' WHERE tenant_id = ? AND id = ?")
                .params(tenant, brokenJob).update();
        String runId = replays.start(AgentReplayRequest.of(KYC, STEP, FROM, TO, GUARDRAILS, 3)).id();
        replayUntilDone(replays, runId);
        AgentReplayRun run = replays.run(runId);
        assertThat(run.status()).isEqualTo("COMPLETED");
        assertThat(run.summary().cases()).isEqualTo(4);
        assertThat(run.summary().notReplayed()).isEqualTo(1);
        assertThat(run.summary().unchanged()).isEqualTo(3);
        assertThat(allCases(replays, runId)).filteredOn(c -> c.code() != null).singleElement().satisfies(c -> {
            assertThat(c.code()).isEqualTo("GUARD_SPEC_INVALID");
            assertThat(c.change()).isEqualTo("NOT_REPLAYED");
        });

        // And (e) a run is kept for replay.retention and then deleted with its report
        clock.advance(Duration.ofDays(30));
        replays.pollOnce();
        assertThatThrownBy(() -> replays.run(runId)).isInstanceOf(ReplayNotFoundException.class);
        assertThat(count("SELECT count(*) FROM pre_agent_replay_case WHERE tenant_id = ?")).isZero();
    }

    // ---- PRE-095 ----------------------------------------------------------------------------------------------

    @Test
    void PRE_095_replay_reads_only_the_callers_tenant_and_needs_its_own_permission() {
        // Given run R1 of this tenant over one recorded answer
        publishGuardrails();
        deploy();
        record(List.of(answer("PASSPORT", "0.95", "2031-06-30")), Duration.ofHours(1));
        replayer();
        AgentReplays replays = pod.agentReplays(ReplaySettings.defaults());
        String r1 = replays.start(AgentReplayRequest.of(KYC, STEP, FROM, TO, GUARDRAILS, 3)).id();
        replayUntilDone(replays, r1);
        AgentReplayCase line = allCases(replays, r1).get(0);

        // When a user of another bank reads R1, opens its case and names this tenant's candidate
        pod.identity.as("bank-other", "eve", "processes:agent:replay", "processes:read");

        // Then each is not found, and nothing is returned
        assertThatThrownBy(() -> replays.run(r1)).isInstanceOf(ReplayNotFoundException.class);
        assertThatThrownBy(() -> replays.cases(r1, PageRequest.first())).isInstanceOf(ReplayNotFoundException.class);
        assertThatThrownBy(() -> replays.recordedAnswer(r1, line.attemptId()))
                .isInstanceOf(ReplayNotFoundException.class);
        assertThatThrownBy(() -> replays.start(AgentReplayRequest.of(KYC, STEP, FROM, TO, GUARDRAILS, 3)))
                .isInstanceOf(GuardrailNotFoundException.class);

        // And a user of this bank with processes:read only can neither start nor read a replay
        pod.identity.as(tenant, "kim", "processes:read");
        assertThatThrownBy(() -> replays.start(AgentReplayRequest.of(KYC, STEP, FROM, TO, GUARDRAILS, 3)))
                .isInstanceOf(PermissionDeniedException.class);
        assertThatThrownBy(() -> replays.run(r1)).isInstanceOf(PermissionDeniedException.class);

        // And with processes:agent:replay the recorded answer shows masked fields as recorded and masked inputs as hashes
        replayer();
        Map<String, Object> recorded = replays.recordedAnswer(r1, line.attemptId());
        assertThat(recorded).containsEntry("attemptId", line.attemptId());
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) recorded.get("output");
        @SuppressWarnings("unchecked")
        Map<String, Object> inputs = (Map<String, Object>) recorded.get("inputs");
        assertThat(output).containsEntry("summary", "***").containsEntry("documentType", "PASSPORT");
        assertThat(inputs.get("nationalId")).asString().startsWith("hmac:k1:");
        assertThat(recorded.toString()).doesNotContain("079088001234");
        // And a repeat of the start with the same idempotency key returns the original run
        AgentReplayRun keyed = replays.start(new AgentReplayRequest(KYC, STEP, FROM, TO, GUARDRAILS, 3, null, "k-1"));
        assertThat(replays.start(new AgentReplayRequest(KYC, STEP, FROM, TO, GUARDRAILS, 3, null, "k-1")).id())
                .isEqualTo(keyed.id());
    }
}
