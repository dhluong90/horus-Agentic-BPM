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
    "HORUS_DEMO_PASSWORD=it-pass", "horus.bpm.processes.definitions.max-nodes=500"})
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
    void the_payment_approval_demo_completes_after_the_third_signature_when_the_complex_join_has_already_fired() throws Exception {
        // Given tuan has submitted a large payment that three approvers are asked to sign
        HttpResponse<String> started = send("tuan", "POST", "/api/bpm/processes/v1/instances",
                "{\"definitionKey\":\"payment-approval\",\"businessKey\":\"PA-BUG3\",\"variables\":{}}");
        assertThat(started.statusCode()).as(started.body()).isEqualTo(201);
        String id = JSON.readTree(started.body()).get("id").stringValue();

        // When an, hung and hoa sign in turn, the join fires on the second and the payment is released
        signOff("an", id, "card-ops-signoff");
        signOff("hung", id, "operations-signoff");
        assertThat(status(id)).as("two of three signed: released, third sign-off still open").isEqualTo("RUNNING");
        signOff("hoa", id, "supervisor-signoff");

        // Then the third signature is absorbed and the instance completes instead of hanging in RUNNING
        assertThat(status(id)).isEqualTo("COMPLETED");
    }

    private void signOff(String user, String instanceId, String nodeId) throws Exception {
        JsonNode instance = JSON.readTree(get(user, "/api/bpm/processes/v1/instances/" + instanceId).body());
        String task = null;
        for (JsonNode t : instance.get("tasks")) {
            if (nodeId.equals(t.get("nodeId").stringValue())) {
                task = t.get("id").stringValue();
            }
        }
        assertThat(task).as("open task " + nodeId + " in " + instance).isNotNull();
        String base = "/api/bpm/processes/v1/tasks/" + task;
        HttpResponse<String> claimed = send(user, "POST", base + ":claim", "", Map.of("If-Match", "\"0\"",
                "Idempotency-Key", user + "-claims-" + task));
        assertThat(claimed.statusCode()).as(claimed.body()).isEqualTo(200);
        HttpResponse<String> done = send(user, "POST", base + ":complete", "{\"result\":{\"ok\":true}}",
                Map.of("If-Match", "\"1\"", "Idempotency-Key", user + "-signs-" + task));
        assertThat(done.statusCode()).as(done.body()).isEqualTo(200);
    }

    private String status(String instanceId) throws Exception {
        return JSON.readTree(get("hoa", "/api/bpm/processes/v1/instances/" + instanceId).body()).get("status")
                .stringValue();
    }

    private HttpResponse<String> send(String user, String method, String path, String body, Map<String, String> headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", basic(user)).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
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
