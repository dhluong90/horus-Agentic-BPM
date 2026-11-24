package io.github.dhluong90.bpm.examples.ruleandprocess;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The API side of the 1.0 acceptance checklist, run against the example host with the seeded demo users. Each test
 * names its checklist item: E (BPMN import), F (running a process), G (BPMN elements), C (rule lifecycle) and I
 * (bank-grade controls). The browser side of the same checklist is in cockpit/web/e2e-live.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "HORUS_DEMO_PASSWORD=it-pass", "horus.bpm.processes.jobs.enabled=true"})
@ActiveProfiles("dev")
@Testcontainers
class AcceptanceIT {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String PE = "/api/bpm/processes/v1";
    static final String RULES = "/api/bpm/rules/v1/rule-sets/card-fee";

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

    // ---- E: importing BPMN -----------------------------------------------------------------------------------

    @Test
    void ACC_E2_a_document_with_a_doctype_is_refused_as_unsafe_xml() throws Exception {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" id=\"d\""
                + " targetNamespace=\"urn:t\"><bpmn:process id=\"p\" isExecutable=\"true\"/></bpmn:definitions>";

        HttpResponse<String> r = send("lan", "POST", PE + "/definitions", xxe, null, "application/xml");

        assertThat(r.statusCode()).as(r.body()).isBetween(400, 422);
        assertThat(JSON.readTree(r.body()).get("code").stringValue()).isEqualTo("BPMN_UNSAFE_XML");
    }

    @Test
    void ACC_E2_an_unsupported_element_is_refused_with_its_name_not_accepted_silently() throws Exception {
        String bpmn = "<?xml version=\"1.0\"?><bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " id=\"d\" targetNamespace=\"urn:t\"><bpmn:process id=\"acc-unsupported\" isExecutable=\"true\">"
                + "<bpmn:startEvent id=\"start\"/><bpmn:globalTask id=\"do-1\"/><bpmn:endEvent id=\"end\"/>"
                + "<bpmn:sequenceFlow id=\"f\" sourceRef=\"start\" targetRef=\"end\"/></bpmn:process></bpmn:definitions>";

        HttpResponse<String> r = send("lan", "POST", PE + "/definitions", bpmn, null, "application/xml");

        assertThat(r.statusCode()).as(r.body()).isBetween(400, 422);
        assertThat(r.body()).contains("globalTask").contains("do-1");
        assertThat(send("lan", "GET", PE + "/definitions/acc-unsupported/versions/1", null, null, null).statusCode())
                .isEqualTo(404);
    }

    // ---- F: running a process --------------------------------------------------------------------------------

    @Test
    void ACC_F13_a_gold_customer_is_approved_at_once_and_a_standard_customer_waits_for_an_officer() throws Exception {
        JsonNode gold = post("tuan", "/api/cards/applications", "{\"applicationId\":\"ACC-F1\",\"customerId\":\"C-001\"}");
        JsonNode standard = post("tuan", "/api/cards/applications", "{\"applicationId\":\"ACC-F2\",\"customerId\":\"C-002\"}");

        JsonNode goldInstance = get("an", PE + "/instances/" + gold.get("instanceId").stringValue());
        JsonNode standardInstance = get("an", PE + "/instances/" + standard.get("instanceId").stringValue());

        assertThat(goldInstance.get("status").stringValue()).isEqualTo("COMPLETED");
        assertThat(goldInstance.at("/variables/pricing/fee/amount").longValue()).isZero();
        assertThat(standardInstance.get("status").stringValue()).isEqualTo("RUNNING");
        assertThat(standardInstance.get("tasks")).hasSize(1);
    }

    @Test
    void ACC_F14_the_submitter_cannot_complete_the_four_eyes_review_and_the_officer_can() throws Exception {
        JsonNode started = post("tuan", "/api/cards/applications", "{\"applicationId\":\"ACC-F3\",\"customerId\":\"C-002\"}");
        JsonNode instance = get("an", PE + "/instances/" + started.get("instanceId").stringValue());
        String task = PE + "/tasks/" + instance.at("/tasks/0/id").stringValue();

        HttpResponse<String> own = send("tuan", "POST", task + ":claim", "", "\"0\"", "application/json");
        send("an", "POST", task + ":claim", "", "\"0\"", "application/json");
        HttpResponse<String> done = send("an", "POST", task + ":complete", "{\"result\":{\"approved\":true}}", "\"1\"",
                "application/json");

        assertThat(own.statusCode()).isIn(403, 409);
        assertThat(done.statusCode()).as(done.body()).isEqualTo(200);
        assertThat(get("an", PE + "/instances/" + started.get("instanceId").stringValue()).get("status").stringValue())
                .isEqualTo("COMPLETED");
    }

    // ---- G: BPMN elements ------------------------------------------------------------------------------------

    @Test
    void ACC_G19_a_closed_target_undoes_the_transfer_newest_step_first_and_an_open_one_completes() throws Exception {
        String cancelled = start("fund-transfer", "ACC-G-1", "{\"amountMinor\":2000000,\"currency\":\"VND\","
                + "\"sourceAccount\":\"ACC-1\",\"targetClosed\":true}");
        String ok = start("fund-transfer", "ACC-G-2", "{\"amountMinor\":2000000,\"currency\":\"VND\","
                + "\"sourceAccount\":\"ACC-1\",\"targetClosed\":false}");

        JsonNode undone = awaitStatus(cancelled, "COMPLETED");
        JsonNode moved = awaitStatus(ok, "COMPLETED");

        List<String> steps = new ArrayList<>();
        get("an", PE + "/instances/" + cancelled + "/activities").get("entries").forEach(s -> {
            if (s.hasNonNull("nodeId")) {
                steps.add(s.get("nodeId").stringValue());
            }
        });
        List<String> undo = steps.subList(steps.indexOf("transfer-cancel"), steps.size());
        assertThat(steps).contains("cancelled-end");
        assertThat(undo.indexOf("debit-source")).isGreaterThan(-1).isLessThan(undo.indexOf("hold-funds"));
        assertThat(steps).doesNotContain("end");
        assertThat(undone.get("status").stringValue()).isEqualTo("COMPLETED");
        List<String> movedSteps = new ArrayList<>();
        get("an", PE + "/instances/" + ok + "/activities").get("nodes").forEach(s -> movedSteps.add(
                s.get("nodeId").stringValue()));
        assertThat(movedSteps).contains("end").doesNotContain("transfer-cancel");
        assertThat(moved.get("status").stringValue()).isEqualTo("COMPLETED");
    }

    // ---- C and I: rule lifecycle and four eyes -----------------------------------------------------------------

    @Test
    void ACC_C7_to_C9_I2_the_requester_cannot_approve_withdraw_returns_to_draft_and_history_names_each_actor()
            throws Exception {
        String v2 = Files.readString(Path.of("src/main/resources/rules/card-fee.yaml"))
                .replace("then: { fee: \"10000 VND\" }", "then: { fee: \"30000 VND\" }");
        JsonNode draft = json(send("lan", "POST", RULES + "/versions", v2, null, "application/yaml"), 201);
        String v = RULES + "/versions/" + draft.get("version").intValue();
        JsonNode requested = json(send("lan", "POST", v + ":request-publish", "", null, "application/json"), 200);
        String reviewed = "{\"expectedCanonicalHash\":\"" + requested.get("canonicalHash").stringValue() + "\"}";

        HttpResponse<String> self = send("lan", "POST", v + ":approve", reviewed, lock(requested), "application/json");
        JsonNode withdrawn = json(send("lan", "POST", v + ":withdraw", "{}", lock(requested), "application/json"), 200);
        JsonNode again = json(send("lan", "POST", v + ":request-publish", "", lock(withdrawn), "application/json"), 200);
        String reviewedAgain = "{\"expectedCanonicalHash\":\"" + again.get("canonicalHash").stringValue() + "\"}";
        JsonNode approved = json(send("minh", "POST", v + ":approve", reviewedAgain, lock(again), "application/json"), 200);

        assertThat(self.statusCode()).isEqualTo(403);
        assertThat(withdrawn.get("status").stringValue()).isEqualTo("DRAFT");
        assertThat(approved.get("status").stringValue()).isIn("PUBLISHED", "ACTIVE");
        String history = send("minh", "GET", v + "/history", null, null, null).body();
        assertThat(history).contains("lan").contains("minh").contains("WITHDRAWN");
        // The earlier published version is retired by the newer one
        assertThat(json(send("minh", "GET", RULES + "/versions/1", null, null, null), 200).get("status").stringValue())
                .isEqualTo("RETIRED");
    }

    @Test
    void ACC_I22_starting_twice_with_the_same_idempotency_key_gives_one_instance_and_the_same_answer() throws Exception {
        String body = "{\"definitionKey\":\"card-welcome\",\"businessKey\":\"ACC-I22\"}";
        String key = java.util.UUID.randomUUID().toString();

        HttpResponse<String> first = sendWithKey("hoa", PE + "/instances", body, key);
        HttpResponse<String> replay = sendWithKey("hoa", PE + "/instances", body, key);

        assertThat(first.statusCode()).as(first.body()).isIn(200, 201);
        assertThat(replay.statusCode()).as(replay.body()).isIn(200, 201);
        assertThat(JSON.readTree(replay.body()).get("id").stringValue())
                .isEqualTo(JSON.readTree(first.body()).get("id").stringValue());
    }

    @Test
    void ACC_I24_a_form_refuses_a_decision_outside_its_list_and_accepts_one_inside_it() throws Exception {
        String bad = start("credit-review-form", "ACC-CR-1", "{}");
        String good = start("credit-review-form", "ACC-CR-2", "{}");

        HttpResponse<String> refused = complete("an", get("an", PE + "/instances/" + bad).at("/tasks/0/id")
                .stringValue(), "{\"result\":{\"decision\":\"MAYBE\"}}");
        HttpResponse<String> accepted = complete("an", get("an", PE + "/instances/" + good).at("/tasks/0/id")
                .stringValue(), "{\"result\":{\"decision\":\"APPROVE\",\"reason\":\"ok\"}}");

        assertThat(refused.statusCode()).as(refused.body()).isBetween(400, 422);
        assertThat(get("an", PE + "/instances/" + bad).get("status").stringValue()).isEqualTo("RUNNING");
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
    }

    @Test
    void ACC_G17_a_customer_cancel_message_ends_the_payment_review_on_the_cancel_path() throws Exception {
        String id = start("payment-review", "ACC-PAY-1", "{\"paymentId\":\"ACC-PAY-1\",\"riskScore\":10}");

        HttpResponse<String> r = send("hung", "POST", PE + "/messages:correlate",
                "{\"messageName\":\"payment-cancelled\",\"correlationKey\":\"ACC-PAY-1\"}", null,
                "application/json");

        assertThat(r.statusCode()).as(r.body()).isBetween(200, 202);
        assertThat(awaitStatus(id, "COMPLETED").get("status").stringValue()).isEqualTo("COMPLETED");
        List<String> nodes = new ArrayList<>();
        get("an", PE + "/instances/" + id + "/activities").get("nodes").forEach(n -> nodes.add(
                n.get("nodeId").stringValue()));
        assertThat(nodes).contains("withdrawn").doesNotContain("settled");
    }

    @Test
    void ACC_G18_the_complex_gateway_lets_the_payment_go_after_two_of_three_sign_offs() throws Exception {
        JsonNode started = post("tuan", "/api/bpm/processes/v1/instances", "{\"definitionKey\":\"payment-approval\","
                + "\"businessKey\":\"ACC-PA-1\"}");
        String id = started.get("id").stringValue();
        JsonNode instance = get("an", PE + "/instances/" + id);
        String cardOps = taskOf(instance, "card-ops-signoff");
        String operations = taskOf(instance, "operations-signoff");

        sign("an", cardOps);
        sign("hung", operations);

        boolean released = false;
        for (int i = 0; i < 100 && !released; i++) {
            for (JsonNode n : get("an", PE + "/instances/" + id + "/activities").get("nodes")) {
                released |= "release-payment".equals(n.get("nodeId").stringValue());
            }
            if (!released) {
                Thread.sleep(200);
            }
        }
        assertThat(released).as("the payment is released after two of three sign-offs").isTrue();
        sign("hoa", taskOf(get("an", PE + "/instances/" + id), "supervisor-signoff"));
        assertThat(awaitStatus(id, "COMPLETED").get("status").stringValue()).isEqualTo("COMPLETED");
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private static String lock(JsonNode version) {
        return "\"" + version.get("lockVersion").asLong() + "\"";
    }

    private static String taskOf(JsonNode instance, String nodeId) {
        for (JsonNode t : instance.get("tasks")) {
            if (nodeId.equals(t.get("nodeId").stringValue())) {
                return t.get("id").stringValue();
            }
        }
        throw new AssertionError("no task at " + nodeId + " in " + instance);
    }

    private void sign(String user, String taskId) throws Exception {
        String task = PE + "/tasks/" + taskId;
        send(user, "POST", task + ":claim", "", "\"0\"", "application/json");
        HttpResponse<String> done = send(user, "POST", task + ":complete", "{\"result\":{\"approved\":true}}", "\"1\"",
                "application/json");
        assertThat(done.statusCode()).as(done.body()).isEqualTo(200);
    }

    private HttpResponse<String> complete(String user, String taskId, String body) throws Exception {
        String task = PE + "/tasks/" + taskId;
        send(user, "POST", task + ":claim", "", "\"0\"", "application/json");
        return send(user, "POST", task + ":complete", body, "\"1\"", "application/json");
    }

    private HttpResponse<String> sendWithKey(String user, String path, String body, String key) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":it-pass")
                        .getBytes(StandardCharsets.UTF_8)))
                .header("Idempotency-Key", key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String start(String key, String businessKey, String variables) throws Exception {
        String body = "{\"definitionKey\":\"" + key + "\",\"businessKey\":\"" + businessKey + "\",\"variables\":"
                + variables + "}";
        return json(send("hoa", "POST", PE + "/instances", body, null, "application/json"), 201).get("id").stringValue();
    }

    private JsonNode awaitStatus(String id, String status) throws Exception {
        JsonNode last = null;
        for (int i = 0; i < 100; i++) {
            last = get("an", PE + "/instances/" + id);
            if (status.equals(last.get("status").stringValue())) {
                return last;
            }
            Thread.sleep(200);
        }
        return last;
    }

    private static JsonNode json(HttpResponse<String> r, int expected) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(expected);
        return JSON.readTree(r.body());
    }

    private JsonNode post(String user, String path, String body) throws Exception {
        HttpResponse<String> r = send(user, "POST", path, body, null, "application/json");
        assertThat(r.statusCode()).as(r.body()).isIn(200, 201);
        return JSON.readTree(r.body());
    }

    private JsonNode get(String user, String path) throws Exception {
        return json(send(user, "GET", path, null, null, null), 200);
    }

    private HttpResponse<String> send(String user, String method, String path, String body, String ifMatch,
            String contentType) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":it-pass")
                        .getBytes(StandardCharsets.UTF_8)))
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString());
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
