package io.github.dhluong90.bpm.examples.ruleandprocess;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The three demo scenarios of the {@code loan-origination} showcase, end to end over HTTP on PostgreSQL with the dev
 * seed (four rule sets, five demo roles, the process): an auto-approved loan; a referred loan with a guardrail review
 * and an SLA escalation; and a ledger failure whose compensation reverses the booking and then releases the limit.
 * Timers are shortened by {@code horus.demo.timers.*}; they are wall-clock, with no business calendar.
 *
 * <p>Users: {@code tuan} starts the cases; {@code hung} reviews the KYC guardrail and correlates messages;
 * {@code an} is the analyst (maker) who also holds the manager role, so four eyes refuse him on the approval;
 * {@code hoa} is the checker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "HORUS_DEMO_PASSWORD=it-pass", "horus.bpm.processes.definitions.max-nodes=500",
    "horus.demo.timers.manager-sla=PT3S", "horus.demo.timers.committee-sla=PT3S", "horus.demo.timers.offer-expired=PT120S"})
@ActiveProfiles("dev")
@Testcontainers
class LoanOriginationIT {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String PE = "/api/bpm/processes/v1";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Test
    void scenario_1_an_auto_approved_retail_loan_is_priced_accepted_and_disbursed_without_a_human() throws Exception {
        // Given a 250 million VND personal loan (CIC group 1, score 712, DSR 38: rule R5, grade B, AUTO)
        String id = start("LA-2026-000451", "CUS-100245", "RETAIL_PERSONAL", false, 250_000_000L, "");

        // Then the case runs by itself to the offer: priced P2, parallel preparation done, waiting on the customer
        JsonNode waiting = await(id, i -> i.at("/variables/offer/offerId").isString());
        assertThat(waiting.at("/variables/scoring/riskGrade").stringValue()).isEqualTo("B");
        assertThat(waiting.at("/variables/scoring/route").stringValue()).isEqualTo("AUTO");
        assertThat(waiting.at("/variables/pricing/pricingTier").stringValue()).isEqualTo("P2");
        assertThat(waiting.at("/variables/collateral/status").stringValue()).isEqualTo("NOT_REQUIRED");
        assertThat(ruleIds(id, "credit-risk-grade")).containsExactly("R5");
        assertThat(visited(id)).contains("kyc-aml-screening", "score-credit", "fork", "join", "send-offer", "g-offer")
                .doesNotContain("prepare-proposal", "approve-manager");

        // When the customer accepts
        accept("LA-2026-000451", "scenario-1");

        // Then the disbursement books and pays out, and the case ends with the disbursed notice
        JsonNode done = await(id, i -> "COMPLETED".equals(i.get("status").stringValue()));
        assertThat(visited(id)).contains("reserve-limit", "book-loan", "pay-out", "end-disbursed")
                .doesNotContain("undo-disbursement", "end-failed");
        assertThat(done.at("/variables/booking/journalId").isString()).isTrue();
        assertThat(messagesOf("LA-2026-000451")).contains("message.loan-disbursed");
    }

    @Test
    void scenario_2_a_pep_hit_opens_a_guardrail_review_and_the_slow_approval_escalates_while_the_task_stays_open()
            throws Exception {
        // Given a 900 million VND SME loan whose screening agent reports a PEP hit
        String id = start("LA-2026-000452", "CUS-100390", "SME_TERM", true, 900_000_000L, ",\"simulatePepHit\":true");

        // Then the guardrail refuses the agent's answer (R5, PEP_MATCH) and a compliance reviewer gets a four-eyes task
        JsonNode review = await(id, i -> openTasks(i).contains("kyc-aml-screening"));
        String reviewTask = taskId(review, "kyc-aml-screening");
        JsonNode view = JSON.readTree(get("hung", "/api/bpm/processes/v1/tasks/" + reviewTask).body());
        assertThat(view.at("/review/reasons").toString()).isEqualTo("[\"PEP_MATCH\"]");
        assertThat(view.at("/review/ruleIds").toString()).isEqualTo("[\"R5\"]");
        assertThat(view.get("fourEyes").booleanValue()).isTrue();

        // When the reviewer, who did not start the case, confirms the relative of a PEP with enhanced due diligence
        work("hung", reviewTask, "{\"kycStatus\":\"PASS\",\"idConfidence\":0.97,\"nameMatches\":true,"
                + "\"tamperingSuspected\":false,\"sanctionsHit\":false,\"pepHit\":true,\"adverseMedia\":false,"
                + "\"fraudFlag\":false}", "Relative of a PEP, enhanced due diligence noted");

        // Then credit grades it B and REFER (rule R6) and the analyst gets the proposal task
        JsonNode proposal = await(id, i -> openTasks(i).contains("prepare-proposal"));
        assertThat(ruleIds(id, "credit-risk-grade")).containsExactly("R6");
        work("an", taskId(proposal, "prepare-proposal"), "{\"amountMinor\":900000000,\"tenorMonths\":60,"
                + "\"recommendation\":\"APPROVE\"}", "Turnover covenant agreed");

        // And the authority matrix (A3) sends it to a manager, whose approval task opens
        JsonNode approval = await(id, i -> openTasks(i).contains("approve-manager"));
        assertThat(ruleIds(id, "approval-authority")).containsExactly("A3");
        String approvalTask = taskId(approval, "approve-manager");

        // When the SLA passes, the escalation notice is sent and the task stays open
        await(id, i -> visited(id).contains("end-escalated"));
        assertThat(messagesOf("LA-2026-000452")).contains("message.approval-escalated");
        assertThat(openTasks(instance(id))).contains("approve-manager");

        // And four eyes refuse the analyst, while the checker approves
        HttpResponse<String> own = send("an", "POST", "/api/bpm/processes/v1/tasks/" + approvalTask + ":claim", null,
                Map.of("If-Match", "\"0\"", "Idempotency-Key", "claim-own-" + approvalTask));
        assertThat(own.statusCode()).as(own.body()).isEqualTo(403);
        assertThat(JSON.readTree(own.body()).get("code").stringValue()).isEqualTo("SELF_APPROVAL_FORBIDDEN");
        work("hoa", approvalTask, "{\"decision\":\"APPROVED\",\"reason\":\"Within appetite\"}", "Approved");

        // Then it is priced P10 (SME, B), collateral is registered, and the offer waits for the customer
        JsonNode offered = await(id, i -> i.at("/variables/offer/offerId").isString());
        assertThat(offered.at("/variables/pricing/pricingTier").stringValue()).isEqualTo("P2");
        assertThat(ruleIds(id, "loan-pricing")).containsExactly("P10");
        assertThat(offered.at("/variables/collateral/status").stringValue()).isEqualTo("REGISTERED");
        accept("LA-2026-000452", "scenario-2");
        await(id, i -> "COMPLETED".equals(i.get("status").stringValue()));
        assertThat(visited(id)).contains("end-disbursed");
    }

    @Test
    void scenario_3_a_ledger_rejection_reverses_the_booking_then_releases_the_limit_and_opens_no_incident()
            throws Exception {
        // Given the scenario 1 loan with the ledger set to reject the pay-out
        String id = start("LA-2026-000453", "CUS-100245", "RETAIL_PERSONAL", false, 180_000_000L,
                ",\"simulateLedgerFailure\":true");
        await(id, i -> i.at("/variables/offer/offerId").isString());

        // When the customer accepts
        accept("LA-2026-000453", "scenario-3");

        // Then the case closes without payout, the compensation ran newest first, and nothing is left open
        JsonNode done = await(id, i -> "COMPLETED".equals(i.get("status").stringValue()));
        List<String> steps = visited(id);
        assertThat(steps).contains("reserve-limit", "book-loan", "pay-out", "ledger-failed", "undo-disbursement",
                "end-failed").doesNotContain("end-disbursed");
        // The history names the step each undo belongs to: the booking is reversed before the limit is released
        assertThat(steps.lastIndexOf("book-loan")).isGreaterThan(steps.indexOf("undo-disbursement"))
                .isLessThan(steps.lastIndexOf("reserve-limit"));
        assertThat(messagesOf("LA-2026-000453")).contains("message.loan-closed-unpaid");
        assertThat(JSON.readTree(get("hoa", PE + "/incidents?status=OPEN").body()).toString()).doesNotContain("LA-2026-000453");
        assertThat(done.get("status").stringValue()).isEqualTo("COMPLETED");
    }

    @Test
    void a_collateral_fraud_flag_while_the_parallel_preparation_runs_terminates_the_whole_case() throws Exception {
        // Given a secured loan whose collateral registry reports a double pledge
        String id = start("LA-2026-000454", "CUS-100245", "RETAIL_SECURED", true, 250_000_000L,
                ",\"simulateCollateralFraud\":true");

        // Then the error boundary leads to the terminate end: no offer is sent, nothing is left open
        JsonNode done = await(id, i -> "COMPLETED".equals(i.get("status").stringValue()));
        List<String> steps = visited(id);
        assertThat(steps).contains("fork", "register-collateral", "collateral-flagged", "end-fraud")
                .doesNotContain("send-offer", "end-disbursed");
        assertThat(openTasks(done)).isEmpty();
        assertThat(messagesOf("LA-2026-000454")).isEmpty();
    }

    @Test
    void a_committee_approval_escalates_through_the_same_end_as_the_manager_approval() throws Exception {
        // Given a 3 billion VND loan: referred (R6), and the authority matrix (A5) asks the committee
        String id = start("LA-2026-000455", "CUS-100245", "RETAIL_PERSONAL", false, 3_000_000_000L, "");
        JsonNode proposal = await(id, i -> openTasks(i).contains("prepare-proposal"));
        work("an", taskId(proposal, "prepare-proposal"), "{\"amountMinor\":3000000000,\"recommendation\":\"APPROVE\"}",
                "Large exposure");

        // Then the committee task opens and its own timer sends the escalation notice while the task stays open
        JsonNode approval = await(id, i -> openTasks(i).contains("approve-committee"));
        assertThat(ruleIds(id, "approval-authority")).containsExactly("A5");
        await(id, i -> visited(id).contains("committee-sla"));
        await(id, i -> messagesOf("LA-2026-000455").contains("message.approval-escalated"));
        assertThat(openTasks(instance(id))).contains("approve-committee");
        assertThat(taskId(approval, "approve-committee")).isNotBlank();
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private String start(String applicationId, String customerId, String family, boolean secured, long amount,
            String extra) throws Exception {
        // The demo switches are start variables the model reads; each defaults to off here, and extra turns one on
        String switches = ",\"simulatePepHit\":false,\"simulateFraud\":false,\"simulateCollateralFraud\":false,"
                + "\"simulateLedgerFailure\":false,\"simulateDebtGroup\":1";
        for (String on : extra.isEmpty() ? new String[0] : extra.substring(1).split(",")) {
            String key = on.substring(0, on.indexOf(':'));
            switches = switches.replaceAll(key + ":[a-z0-9]+", on);
        }
        String body = "{\"definitionKey\":\"loan-origination\",\"businessKey\":\"" + applicationId + "\",\"variables\":{"
                + "\"applicationId\":\"" + applicationId + "\",\"customerId\":\"" + customerId + "\","
                + "\"productFamily\":\"" + family + "\",\"secured\":" + secured + ",\"amountMinor\":" + amount
                + ",\"currency\":\"VND\",\"tenorMonths\":36,\"channel\":\"DIGITAL\"" + switches + "}}";
        HttpResponse<String> started = send("tuan", "POST", PE + "/instances", body,
                Map.of("Content-Type", "application/json", "Idempotency-Key", "start-" + applicationId));
        assertThat(started.statusCode()).as(started.body()).isEqualTo(201);
        return JSON.readTree(started.body()).get("id").stringValue();
    }

    private void accept(String applicationId, String key) throws Exception {
        HttpResponse<String> r = send("hung", "POST", PE + "/messages:correlate",
                "{\"messageName\":\"offer-accepted\",\"correlationKey\":\"" + applicationId + "\",\"payload\":{}}",
                Map.of("Content-Type", "application/json", "Idempotency-Key", "accept-" + key));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    }

    private void work(String user, String task, String result, String reason) throws Exception {
        HttpResponse<String> claimed = send(user, "POST", PE + "/tasks/" + task + ":claim", null,
                Map.of("If-Match", "\"0\"", "Idempotency-Key", "claim-" + task));
        assertThat(claimed.statusCode()).as(claimed.body()).isEqualTo(200);
        HttpResponse<String> done = send(user, "POST", PE + "/tasks/" + task + ":complete",
                "{\"result\":" + result + ",\"reason\":\"" + reason + "\"}",
                Map.of("If-Match", "\"1\"", "Idempotency-Key", "complete-" + task, "Content-Type", "application/json"));
        assertThat(done.statusCode()).as(done.body()).isEqualTo(200);
    }

    private JsonNode instance(String id) throws Exception {
        return JSON.readTree(get("hoa", PE + "/instances/" + id).body());
    }

    private JsonNode await(String id, Check check) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        JsonNode i = instance(id);
        while (!check.test(i)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting on " + id + "; last answer: " + i);
            }
            Thread.sleep(300);
            i = instance(id);
        }
        return i;
    }

    @FunctionalInterface
    private interface Check {
        boolean test(JsonNode instance) throws Exception;
    }

    private static List<String> openTasks(JsonNode instance) {
        List<String> open = new ArrayList<>();
        for (JsonNode t : instance.get("tasks")) {
            if (!"COMPLETED".equals(t.get("status").stringValue())) {
                open.add(t.get("nodeId").stringValue());
            }
        }
        return open;
    }

    private static String taskId(JsonNode instance, String nodeId) {
        for (JsonNode t : instance.get("tasks")) {
            if (nodeId.equals(t.get("nodeId").stringValue()) && !"COMPLETED".equals(t.get("status").stringValue())) {
                return t.get("id").stringValue();
            }
        }
        throw new AssertionError("no open task " + nodeId);
    }

    /** The nodes the instance passed, in order, from its history. */
    private List<String> visited(String id) {
        return jdbc.sql("SELECT node_id FROM pre_history WHERE instance_id = ? AND node_id IS NOT NULL "
                + "ORDER BY occurred_at, id").param(id).query(String.class).list();
    }

    /** The rules the given instance matched in the rule set, in the order of its decisions; the log's correlation id is the instance id. */
    private List<String> ruleIds(String instanceId, String ruleSet) {
        List<String> ids = new ArrayList<>();
        jdbc.sql("SELECT matched_rule_ids::text FROM rle_decision WHERE rule_set_name = ? AND correlation_id = ? "
                + "ORDER BY created_at").param(ruleSet).param(instanceId).query(String.class).list()
                .forEach(s -> ids.add(s.replaceAll("[^A-Z0-9]", "")));
        return ids.isEmpty() ? ids : List.of(ids.getLast());
    }

    private List<String> messagesOf(String applicationId) {
        return jdbc.sql("SELECT event_type FROM event_outbox WHERE business_key = ? AND event_type LIKE 'message.%'").param(applicationId)
                .query(String.class).list();
    }

    private HttpResponse<String> get(String user, String path) throws Exception {
        return send(user, "GET", path, null, Map.of());
    }

    private HttpResponse<String> send(String user, String method, String path, String body, Map<String, String> headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString((user + ":it-pass").getBytes(StandardCharsets.UTF_8)))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
