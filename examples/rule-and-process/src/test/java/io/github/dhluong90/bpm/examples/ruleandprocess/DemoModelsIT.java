package io.github.dhluong90.bpm.examples.ruleandprocess;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
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
 * The default start of the example host (dev profile, nothing else switched on) is ready for the live demo: every BPMN
 * file of {@code src/main/resources/processes}, the demo models and the conformance models alike, is imported at
 * start-up and ACTIVE, and every process in it starts, with its handlers, forms and data stores registered. A file
 * added to that folder and not listed in {@code application-dev.yml} fails this test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "HORUS_DEMO_PASSWORD=it-pass", "horus.bpm.processes.definitions.max-nodes=500",
    "horus.demo.timers.assess-overdue=PT2S"})
@ActiveProfiles("dev")
@Testcontainers
class DemoModelsIT {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final Path MODELS = Path.of("src/main/resources/processes");
    static final Pattern PROCESS = Pattern.compile("<bpmn:process\\s+id=\"([^\"]+)\"");

    /** Start variables of the processes that read some; any other process starts with none. */
    static final Map<String, Map<String, Object>> VARIABLES = Map.ofEntries(
            Map.entry("fund-transfer", Map.of("amountMinor", 2_000_000, "currency", "VND", "sourceAccount", "ACC-1",
                    "targetClosed", false)),
            Map.entry("card-dispute", Map.of("amountMinor", 1_000_000)),
            Map.entry("loan-payout", Map.of("failCredit", false, "amountMinor", 90_000_000, "currency", "VND",
                    "sourceAccount", "ACC-9")),
            Map.entry("card-welcome", Map.of("monthlyFee", 20_000)),
            Map.entry("card-delivery", Map.of("monthlyFee", 20_000)),
            Map.entry("customer-onboarding", Map.of("monthlyFee", 20_000)),
            Map.entry("payment-routing", Map.of("amount", 2_500_000)),
            Map.entry("order-desk", Map.of("orderId", "ORD-1")),
            Map.entry("order-payment", Map.of("orderId", "ORD-2", "amount", 150_000)),
            Map.entry("payment-review", Map.of("paymentId", "PAY-1")),
            Map.entry("loan-data", Map.of("creditScore", 0, "profile", Map.of("name", "Nguyen Van An"))));

    /** Processes a plain start refuses because only a message or a signal starts them; each has its own test below. */
    static final List<String> TRIGGERED = List.of("loan-intake", "onboarding");

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

    @Test
    void every_demo_and_conformance_model_is_imported_active_and_starts() throws Exception {
        List<String> keys = processKeys();
        assertThat(keys).as("the process keys found in " + MODELS).hasSizeGreaterThan(25);
        List<String> problems = new ArrayList<>();
        for (String key : keys) {
            HttpResponse<String> definition = get("lan", "/api/bpm/processes/v1/definitions/" + key + "/versions/1");
            if (definition.statusCode() != 200 || !"ACTIVE".equals(JSON.readTree(definition.body()).get("status").stringValue())) {
                problems.add(key + " is not ACTIVE: " + definition.statusCode() + " " + definition.body());
                continue;
            }
            if (TRIGGERED.contains(key)) {
                continue;
            }
            if (key.equals("card-fee-review")) {
                HttpResponse<String> application = send("tuan", "POST", "/api/cards/applications",
                        "{\"applicationId\":\"APP-DEMO\",\"customerId\":\"C-001\"}");
                if (application.statusCode() != 201) {
                    problems.add(key + " did not start: " + application.statusCode() + " " + application.body());
                }
                continue;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("definitionKey", key);
            body.put("businessKey", "DEMO-" + key);
            body.put("variables", VARIABLES.getOrDefault(key, Map.of()));
            HttpResponse<String> started = send("hoa", "POST", "/api/bpm/processes/v1/instances",
                    JSON.writeValueAsString(body));
            if (started.statusCode() != 201) {
                problems.add(key + " did not start: " + started.statusCode() + " " + started.body());
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void the_compensation_event_subprocess_demo_runs_to_completion_and_every_task_role_has_a_holder() throws Exception {
        // When hoa starts loan-disbursement, whose steps are all registered handlers
        HttpResponse<String> started = send("hoa", "POST", "/api/bpm/processes/v1/instances",
                "{\"definitionKey\":\"loan-disbursement\",\"businessKey\":\"DISB-1\",\"variables\":{}}");

        // Then the instance completes: the disbursement sub-process and its compensation event sub-process load and run
        assertThat(started.statusCode()).as(started.body()).isEqualTo(201);
        String id = JSON.readTree(started.body()).get("id").stringValue();
        HttpResponse<String> instance = get("hoa", "/api/bpm/processes/v1/instances/" + id);
        assertThat(JSON.readTree(instance.body()).get("status").stringValue()).as(instance.body()).isEqualTo("COMPLETED");

        // And every role a model assigns a user task to is held by a demo user
        assertThat(permissions("an")).contains("processes:task:work");
        assertThat(permissions("hung")).contains("processes:task:work");
        assertThat(permissions("hoa")).contains("processes:task:work");
    }

    @Test
    void a_message_starts_the_event_gateway_process_and_the_parallel_one_then_a_signal_continues_it() throws Exception {
        // When operations delivers the loan application message, then the KYC documents message and the signal
        HttpResponse<String> intake = post("hung", "/api/bpm/processes/v1/messages:correlate",
                "{\"messageName\":\"loan-application\",\"correlationKey\":\"LA-1\",\"payload\":{}}", "demo-msg-1");
        HttpResponse<String> documents = post("hung", "/api/bpm/processes/v1/messages:correlate",
                "{\"messageName\":\"kyc-documents\",\"correlationKey\":\"ONB-1\",\"payload\":{}}", "demo-msg-2");
        HttpResponse<String> signal = post("hung", "/api/bpm/processes/v1/signals",
                "{\"name\":\"sanction-screening-done\"}", "demo-sig-1");

        // Then the first message starts loan-intake, the second starts onboarding, and the signal is accepted
        assertThat(intake.statusCode()).as(intake.body()).isEqualTo(200);
        assertThat(JSON.readTree(intake.body()).get("outcome").stringValue()).isEqualTo("STARTED");
        assertThat(documents.statusCode()).as(documents.body()).isEqualTo(200);
        assertThat(signal.statusCode()).as(signal.body()).isEqualTo(202);
    }

    @Test
    void every_demo_scene_has_a_user_who_may_run_it_and_only_lan_has_two_tenants() throws Exception {
        // Then each user's permissions on the default start cover the scenes of the demo
        assertThat(permissions("quynh")).contains("rules:log:read").doesNotContain("rules:author", "processes:start");
        assertThat(permissions("minh")).contains("rules:log:read", "rules:simulate", "rules:approve", "processes:approve")
                .doesNotContain("rules:author", "processes:author");
        assertThat(permissions("lan")).contains("rules:author", "processes:author", "processes:deploy")
                .doesNotContain("rules:approve");
        assertThat(permissions("hung")).contains("processes:message:correlate", "processes:signal:broadcast",
                "processes:migration:manage", "processes:calendar:manage");
        assertThat(permissions("hoa")).contains("processes:migration:manage", "processes:calendar:manage",
                "processes:cancel");

        // And lan alone may select bank-b; her own process changes are refused by person (DefinitionLifecycleIT), and
        // migration and calendar changes need two holders, hung and hoa
        assertThat(tenants("lan")).containsExactly("bank-a", "bank-b");
        assertThat(tenants("minh")).containsExactly("bank-a");
        HttpResponse<String> meta = get("lan", "/api/bpm/meta");
        assertThat(meta.body()).contains("\"authoring.cockpit-drafts\":true");
    }

    @Test
    void a_dispute_refund_above_the_limit_raises_the_error_and_goes_to_the_senior_approval() throws Exception {
        // When the officer finishes the assessment of a 6,000,000 VND refund, above the 5,000,000 limit
        String id = start("hoa", "card-dispute", "DISPUTE-BIG", Map.of("amountMinor", 6_000_000));
        completeTask("an", id, "assess");

        // Then REFUND_LIMIT_EXCEEDED is caught by the error boundary: no incident, a senior approval task is open
        JsonNode instance = JSON.readTree(get("hoa", "/api/bpm/processes/v1/instances/" + id).body());
        assertThat(instance.get("status").stringValue()).as(instance.toString()).isEqualTo("RUNNING");
        assertThat(openTasks(instance)).containsExactly("senior-approval");

        // And a 1,000,000 VND refund posts and ends with the customer notification
        String small = start("hoa", "card-dispute", "DISPUTE-SMALL", Map.of("amountMinor", 1_000_000));
        completeTask("an", small, "assess");
        JsonNode done = JSON.readTree(get("hoa", "/api/bpm/processes/v1/instances/" + small).body());
        assertThat(done.get("status").stringValue()).as(done.toString()).isEqualTo("COMPLETED");
    }

    @Test
    void a_failed_credit_runs_the_compensation_boundaries_and_event_subprocess_and_a_good_one_runs_none() throws Exception {
        Map<String, Object> payout = new LinkedHashMap<>(
                Map.of("amountMinor", 90_000_000, "currency", "VND", "sourceAccount", "ACC-9"));

        // When the credit step succeeds, the payout completes and nothing is undone
        payout.put("failCredit", false);
        String paid = start("hoa", "loan-payout", "PAYOUT-OK", payout);
        String paidActivities = get("hoa", "/api/bpm/processes/v1/instances/" + paid + "/activities").body();
        assertThat(JSON.readTree(get("hoa", "/api/bpm/processes/v1/instances/" + paid).body()).get("status")
                .stringValue()).isEqualTo("COMPLETED");
        assertThat(paidActivities).contains("tell-success").doesNotContain("COMPENSATION_", "tell-failure");

        // When the credit step answers CREDIT_REJECTED
        payout.put("failCredit", true);
        String undone = start("hoa", "loan-payout", "PAYOUT-FAIL", payout);

        // Then the instance completes on the undone end, after the event sub-process, the debit credit-back and the
        // hold release ran, and the customer was told it failed
        JsonNode instance = awaitStatus(undone, "COMPLETED");
        assertThat(instance.get("status").stringValue()).as(instance.toString()).isEqualTo("COMPLETED");
        String activities = get("hoa", "/api/bpm/processes/v1/instances/" + undone + "/activities").body();
        List<String> undos = new ArrayList<>();
        for (JsonNode entry : JSON.readTree(activities).get("entries")) {
            if ("COMPENSATION_STEP_COMPLETED".equals(entry.get("type").stringValue())) {
                undos.add(entry.get("nodeId").stringValue());
            }
        }
        assertThat(undos).as("newest first: the event sub-process of the payout, the debit, then the hold")
                .containsExactly("payout", "debit", "reserve");
        assertThat(activities).contains("COMPENSATED", "tell-failure").doesNotContain("tell-success");
    }

    @Test
    void a_demo_timer_set_short_in_the_dev_profile_fires_on_its_own() throws Exception {
        // When a dispute is assessed by nobody for longer than the shortened timer (PT2S here, PT1M by default)
        String id = start("hoa", "card-dispute", "DISPUTE-TIMER", Map.of("amountMinor", 1_000_000));

        // Then the non-interrupting boundary fires: the reminder runs while the assessment task stays open
        String activities = "";
        for (int i = 0; i < 60 && !activities.contains("remind-officer"); i++) {
            Thread.sleep(1_000);
            activities = get("hoa", "/api/bpm/processes/v1/instances/" + id + "/activities").body();
        }
        assertThat(activities).as("the reminder step ran after the timer").contains("remind-officer");
        JsonNode instance = JSON.readTree(get("hoa", "/api/bpm/processes/v1/instances/" + id).body());
        assertThat(openTasks(instance)).containsExactly("assess");
    }

    @Test
    void the_shipped_model_keeps_its_real_timer_durations() throws Exception {
        assertThat(Files.readString(MODELS.resolve("card-dispute.bpmn"))).contains("<bpmn:timeDuration>P2D<");
        assertThat(Files.readString(MODELS.resolve("payment-review.bpmn"))).contains("<bpmn:timeDuration>PT2H<");
    }

    private String start(String user, String key, String businessKey, Map<String, Object> variables) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("definitionKey", key);
        body.put("businessKey", businessKey);
        body.put("variables", variables);
        HttpResponse<String> started = send(user, "POST", "/api/bpm/processes/v1/instances", JSON.writeValueAsString(body));
        assertThat(started.statusCode()).as(started.body()).isEqualTo(201);
        return JSON.readTree(started.body()).get("id").stringValue();
    }

    /** The user claims the open task on the node and completes it (both calls carry If-Match and an Idempotency-Key). */
    private void completeTask(String user, String instanceId, String nodeId) throws Exception {
        JsonNode instance = JSON.readTree(get(user, "/api/bpm/processes/v1/instances/" + instanceId).body());
        JsonNode task = null;
        for (JsonNode t : instance.get("tasks")) {
            if (nodeId.equals(t.get("nodeId").stringValue()) && "OPEN".equals(t.get("status").stringValue())) {
                task = t;
            }
        }
        assertThat(task).as("an open task on " + nodeId + " in " + instance).isNotNull();
        String path = "/api/bpm/processes/v1/tasks/" + task.get("id").stringValue();
        int lock = task.get("lockVersion").intValue();
        HttpResponse<String> claim = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path + ":claim"))
                .header("Authorization", basic(user)).header("If-Match", "\"" + lock + "\"")
                .header("Idempotency-Key", "claim-" + instanceId).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(claim.statusCode()).as(claim.body()).isEqualTo(200);
        HttpResponse<String> complete = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path + ":complete"))
                .header("Authorization", basic(user)).header("Content-Type", "application/json")
                .header("If-Match", "\"" + (lock + 1) + "\"").header("Idempotency-Key", "complete-" + instanceId)
                .POST(HttpRequest.BodyPublishers.ofString("{\"result\":{\"upheld\":true}}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(complete.statusCode()).as(complete.body()).isEqualTo(200);
    }

    /** The undo runs on the engine's adapter threads, so the instance is COMPENSATING for a moment. */
    private JsonNode awaitStatus(String id, String status) throws Exception {
        JsonNode instance = null;
        for (int i = 0; i < 60; i++) {
            instance = JSON.readTree(get("hoa", "/api/bpm/processes/v1/instances/" + id).body());
            if (status.equals(instance.get("status").stringValue())) {
                break;
            }
            Thread.sleep(500);
        }
        return instance;
    }

    private static List<String> openTasks(JsonNode instance) {
        List<String> open = new ArrayList<>();
        for (JsonNode t : instance.get("tasks")) {
            if ("OPEN".equals(t.get("status").stringValue()) || "CLAIMED".equals(t.get("status").stringValue())) {
                open.add(t.get("nodeId").stringValue());
            }
        }
        return open;
    }

    private List<String> permissions(String user) throws Exception {
        HttpResponse<String> me = get(user, "/api/bpm/me");
        assertThat(me.statusCode()).as(me.body()).isEqualTo(200);
        List<String> found = new ArrayList<>();
        JSON.readTree(me.body()).get("permissions").forEach(node -> found.add(node.stringValue()));
        return found;
    }

    private List<String> tenants(String user) throws Exception {
        List<String> found = new ArrayList<>();
        JSON.readTree(get(user, "/api/bpm/me").body()).get("tenant").get("allowed")
                .forEach(node -> found.add(node.stringValue()));
        return found;
    }

    private HttpResponse<String> post(String user, String path, String body, String idempotencyKey) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", basic(user)).header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static List<String> processKeys() throws IOException {
        List<String> keys = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MODELS)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".bpmn")).sorted().toList()) {
                Matcher m = PROCESS.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    keys.add(m.group(1));
                }
            }
        }
        return keys;
    }

    private HttpResponse<String> get(String user, String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", basic(user)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String user, String method, String path, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", basic(user)).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String basic(String user) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":it-pass").getBytes(StandardCharsets.UTF_8));
    }
}
