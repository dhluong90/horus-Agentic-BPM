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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
 * QA review of the walking skeleton: idempotent start and complete (also under concurrency), completion races,
 * decision version pinning and definition version pinning, on PostgreSQL through REST.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "HORUS_DEMO_PASSWORD=it-pass")
@ActiveProfiles("dev")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RuleAndProcessQaIT {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String START = "/api/bpm/processes/v1/instances";

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(TestImages.postgres());

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

    // ---- idempotent start (PRE-002) -----------------------------------------------------------------------

    @Test
    @Order(1)
    void PRE_002_same_idempotency_key_replays_the_first_answer() throws Exception {
        String body = startBody("QA-0001", "C-002");

        HttpResponse<String> first = send("tuan", "POST", START, body, null, "qa-k1");
        HttpResponse<String> replay = send("tuan", "POST", START, body, null, "qa-k1");

        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        assertThat(replay.statusCode()).as(replay.body()).isEqualTo(201);
        assertThat(id(replay)).isEqualTo(id(first));
        assertThat(instances("QA-0001")).isEqualTo(1);
    }

    @Test
    @Order(2)
    void PRE_002_same_business_key_with_a_new_idempotency_key_returns_the_existing_instance_with_200() throws Exception {
        HttpResponse<String> first = send("tuan", "POST", START, startBody("QA-0002", "C-002"), null, "qa-k2a");
        HttpResponse<String> again = send("tuan", "POST", START, startBody("QA-0002", "C-002"), null, "qa-k2b");

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(id(again)).isEqualTo(id(first));
        assertThat(instances("QA-0002")).isEqualTo(1);
        // SPEC PRE-002: "201 on same k1, 200 on new k2".
        assertThat(again.statusCode()).as("existing instance by business key").isEqualTo(200);
    }

    @Test
    @Order(3)
    void PRE_002_concurrent_starts_with_one_idempotency_key_create_one_instance() throws Exception {
        for (int round = 0; round < 5; round++) {
            String key = "qa-race-" + round;
            String body = "{\"definitionKey\":\"card-fee-review\",\"variables\":{\"applicationId\":\"QA-R" + round
                    + "\",\"customerId\":\"C-002\"}}";
            List<HttpResponse<String>> answers = concurrently(12, () -> send("tuan", "POST", START, body, null, key));

            List<String> ids = answers.stream().filter(a -> a.statusCode() == 201).map(RuleAndProcessQaIT::id)
                    .distinct().toList();
            long rows = jdbc.sql("SELECT count(*) FROM pre_instance WHERE tenant_id = 'bank-a' "
                    + "AND variables->>'applicationId' = ?").param("QA-R" + round).query(Long.class).single();
            assertThat(rows).as("round %d: pre_instance rows for one Idempotency-Key; 201 ids %s; statuses %s", round,
                    ids, answers.stream().map(HttpResponse::statusCode).toList()).isEqualTo(1);
            assertThat(ids).as("round %d: distinct instance ids answered for one Idempotency-Key", round).hasSize(1);
            assertThat(answers).extracting(HttpResponse::statusCode).as(bodies(answers)).containsOnly(201);
        }
    }

    @Test
    @Order(4)
    void PRE_002_concurrent_starts_with_one_business_key_all_return_the_one_instance() throws Exception {
        for (int round = 0; round < 5; round++) {
            String businessKey = "QA-BK-" + round;
            int[] n = {0};
            List<Callable<HttpResponse<String>>> calls = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                String key = "qa-bk-" + round + "-" + (n[0]++);
                calls.add(() -> send("tuan", "POST", START, startBody(businessKey, "C-002"), null, key));
            }
            List<HttpResponse<String>> answers = concurrently(calls);

            assertThat(instances(businessKey)).isEqualTo(1);
            assertThat(answers).extracting(HttpResponse::statusCode).as(bodies(answers))
                    .allSatisfy(s -> assertThat(s).isBetween(200, 201));
        }
    }

    @Test
    @Order(4)
    void concurrent_starts_of_different_applications_never_share_token_or_task_ids() throws Exception {
        for (int round = 0; round < 5; round++) {
            List<Callable<HttpResponse<String>>> calls = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                String app = "QA-M" + round + "-" + i;
                String customer = i % 2 == 0 ? "C-001" : "C-002";
                calls.add(() -> send("tuan", "POST", "/api/cards/applications",
                        "{\"applicationId\":\"" + app + "\",\"customerId\":\"" + customer + "\"}", null, null));
            }
            List<HttpResponse<String>> answers = concurrently(calls);

            // Every committed instance owns its root scope token and a flow token; a running one waits at the task.
            List<String> broken = jdbc.sql("SELECT i.business_key || ' ' || i.status || ' tokens=' || "
                    + "(SELECT count(*) FROM pre_token t WHERE t.tenant_id = i.tenant_id AND t.instance_id = i.id) || "
                    + "' waiting=' || (SELECT count(*) FROM pre_token t WHERE t.tenant_id = i.tenant_id "
                    + "AND t.instance_id = i.id AND t.status = 'WAITING_USER') FROM pre_instance i "
                    + "WHERE i.tenant_id = 'bank-a' AND i.business_key LIKE ? AND ("
                    + "(SELECT count(*) FROM pre_token t WHERE t.tenant_id = i.tenant_id AND t.instance_id = i.id) < 2 "
                    + "OR (i.status = 'RUNNING' AND NOT EXISTS (SELECT 1 FROM pre_token t WHERE t.tenant_id = i.tenant_id "
                    + "AND t.instance_id = i.id AND t.status = 'WAITING_USER')))")
                    .param("QA-M" + round + "-%").query(String.class).list();
            assertThat(broken).as("round %d: instances whose token rows were overwritten by another instance", round)
                    .isEmpty();
            assertThat(answers).extracting(HttpResponse::statusCode).as("round %d: %s", round, bodies(answers))
                    .containsOnly(201);
        }
    }

    // ---- complete: idempotency and races (PRE-003) ------------------------------------------------------

    @Test
    @Order(5)
    void complete_replayed_with_the_same_idempotency_key_returns_the_stored_answer_and_completes_once() throws Exception {
        String taskPath = openTask("QA-C001");

        HttpResponse<String> first = send("an", "POST", taskPath, "{\"result\":{\"approved\":true}}", "\"1\"", "qa-c1");
        HttpResponse<String> replay = send("an", "POST", taskPath, "{\"result\":{\"approved\":true}}", "\"1\"", "qa-c1");

        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        assertThat(replay.statusCode()).as(replay.body()).isEqualTo(200);
        assertThat(JSON.readTree(replay.body())).isEqualTo(JSON.readTree(first.body()));
        assertThat(taskCompletedEntries(id(first))).isEqualTo(1);
    }

    @Test
    @Order(6)
    void complete_raced_by_eight_officers_with_one_if_match_completes_exactly_once() throws Exception {
        // If-Match and Idempotency-Key are required on completion (security review of #149); each officer sends
        // its own key and the version it read.
        String taskPath = openTask("QA-C002");

        List<HttpResponse<String>> answers = concurrently(8,
                () -> send("an", "POST", taskPath, "{\"result\":{\"approved\":true}}", "\"1\"", null));

        assertThat(answers).extracting(HttpResponse::statusCode).as(bodies(answers)).containsOnly(200, 409);
        assertThat(answers).filteredOn(a -> a.statusCode() == 200).hasSize(1);
        String instanceId = id(answers.stream().filter(a -> a.statusCode() == 200).findFirst().orElseThrow());
        assertThat(taskCompletedEntries(instanceId)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM card_application WHERE tenant_id = 'bank-a' AND application_id = 'QA-C002'")
                .query(String.class).single()).isEqualTo("APPROVED");
    }

    @Test
    @Order(7)
    void complete_with_a_stale_if_match_after_a_replay_is_a_conflict_not_a_second_transition() throws Exception {
        String taskPath = openTask("QA-C003");

        send("an", "POST", taskPath, "{\"result\":{\"approved\":false}}", "\"1\"", "qa-c3");
        HttpResponse<String> stale = send("an", "POST", taskPath, "{\"result\":{\"approved\":true}}", "\"1\"", "qa-c3-other");

        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(JSON.readTree(stale.body()).get("code").stringValue()).isEqualTo("VERSION_CONFLICT");
    }

    // ---- version pinning ----------------------------------------------------------------------------------

    @Test
    @Order(20)
    void decision_version_is_pinned_an_earlier_instance_keeps_v1_after_v2_is_published() throws Exception {
        // Given an instance priced by card-fee v1 (R2: 10000 VND) and waiting for the officer
        JsonNode a = post("tuan", "/api/cards/applications", "{\"applicationId\":\"QA-P001\",\"customerId\":\"C-002\"}");
        JsonNode instanceA = get("an", START + "/" + a.get("instanceId").stringValue());
        String decisionA = instanceA.at("/variables/pricing/decisionId").stringValue();
        assertThat(instanceA.at("/variables/pricing/fee/amount").longValue()).isEqualTo(10_000);

        // When card-fee v2 (R2: 20000 VND) is requested by lan and approved by minh
        String v2 = Files.readString(Path.of("src/main/resources/rules/card-fee.yaml"))
                .replace("then: { fee: \"10000 VND\" }", "then: { fee: \"20000 VND\" }");
        String rules = "/api/bpm/rules/v1/rule-sets/card-fee";
        HttpResponse<String> draft = send("lan", "POST", rules + "/versions", v2, null, null, "application/yaml");
        assertThat(draft.statusCode()).as(draft.body()).isEqualTo(201);
        int version = JSON.readTree(draft.body()).get("version").intValue();
        assertThat(send("lan", "POST", rules + "/versions/" + version + ":request-publish", "", null, null).statusCode())
                .isEqualTo(200);
        HttpResponse<String> approved = send("minh", "POST", rules + "/versions/" + version + ":approve", "", null, null);
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);

        // Then a new instance is priced by v2, and the earlier one keeps v1 in its variables and in rle_decision
        JsonNode b = post("tuan", "/api/cards/applications", "{\"applicationId\":\"QA-P002\",\"customerId\":\"C-002\"}");
        JsonNode instanceB = get("an", START + "/" + b.get("instanceId").stringValue());
        assertThat(instanceB.at("/variables/pricing/fee/amount").longValue()).isEqualTo(20_000);
        assertThat(decisionVersion(instanceB.at("/variables/pricing/decisionId").stringValue())).isEqualTo(version);
        assertThat(decisionVersion(decisionA)).isEqualTo(1);

        String taskA = instanceA.at("/tasks/0/id").stringValue();
        HttpResponse<String> done = send("an", "POST", claimedBy("an", taskA),
                "{\"result\":{\"approved\":true}}", "\"1\"", null);
        assertThat(done.statusCode()).as(done.body()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT fee_minor FROM card_application WHERE tenant_id = 'bank-a' AND application_id = 'QA-P001'")
                .query(Long.class).single()).isEqualTo(10_000L);
        assertThat(jdbc.sql("SELECT count(*) FROM rle_decision WHERE tenant_id = 'bank-a' AND correlation_id = ?")
                .param(instanceA.get("id").stringValue()).query(Long.class).single())
                .as("the earlier instance is not re-priced").isEqualTo(1);
    }

    @Test
    @Order(21)
    void PRE_001_running_instance_pins_definition_version() throws Exception {
        // Given I1 waiting at officer-review on card-fee-review v1
        JsonNode i1 = post("tuan", "/api/cards/applications", "{\"applicationId\":\"QA-D001\",\"customerId\":\"C-003\"}");
        JsonNode instance1 = get("an", START + "/" + i1.get("instanceId").stringValue());
        assertThat(instance1.get("definitionVersion").intValue()).isEqualTo(1);

        // When v2 is imported (dev auto-approval makes it active): the officer task is renamed
        String v2 = Files.readString(Path.of("src/main/resources/processes/card-fee-review.bpmn"))
                .replace("name=\"Officer reviews the fee\"", "name=\"Officer reviews the fee (v2)\"");
        HttpResponse<String> imported = send("lan", "POST", "/api/bpm/processes/v1/definitions", v2, null, null,
                "application/xml");
        assertThat(imported.statusCode()).as(imported.body()).isEqualTo(201);
        assertThat(JSON.readTree(imported.body()).get("version").intValue()).isEqualTo(2);
        JsonNode i2 = post("tuan", "/api/cards/applications", "{\"applicationId\":\"QA-D002\",\"customerId\":\"C-003\"}");

        // Then I1 continues and completes on v1; I2 runs on v2
        String task1 = instance1.at("/tasks/0/id").stringValue();
        HttpResponse<String> done = send("an", "POST", claimedBy("an", task1),
                "{\"result\":{\"approved\":true}}", "\"1\"", null);
        assertThat(done.statusCode()).as(done.body()).isEqualTo(200);
        assertThat(JSON.readTree(done.body()).get("definitionVersion").intValue()).isEqualTo(1);
        JsonNode instance2 = get("an", START + "/" + i2.get("instanceId").stringValue());
        assertThat(instance2.get("definitionVersion").intValue()).isEqualTo(2);
        assertThat(instance2.at("/tasks/0/name").stringValue()).isEqualTo("Officer reviews the fee (v2)");
    }

    // ---- BPMN export (PRE-034) ------------------------------------------------------------------------------

    @Test
    @Order(30)
    void PRE_034_bpmn_export_imports_back_to_the_same_definition_hash() throws Exception {
        int latest = jdbc.sql("SELECT max(version_num) FROM pre_definition WHERE tenant_id = 'bank-a' "
                + "AND key_name = 'card-fee-review'").query(Integer.class).single();
        String hash = jdbc.sql("SELECT definition_hash FROM pre_definition WHERE tenant_id = 'bank-a' "
                + "AND key_name = 'card-fee-review' AND version_num = ?").param(latest).query(String.class).single();
        long rowsBefore = definitionRows();

        // When the version is exported and the document imported again
        HttpResponse<String> exported = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/api/bpm/processes/v1/definitions/card-fee-review/versions/" + latest + "/export?format=bpmn"))
                .header("Authorization", basic("lan")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(exported.statusCode()).as(exported.body()).isEqualTo(200);
        assertThat(exported.headers().firstValue("Content-Type")).hasValueSatisfying(
                v -> assertThat(v).startsWith("application/bpmn+xml"));
        assertThat(exported.headers().firstValue("ETag")).contains("\"" + hash + "\"");
        assertThat(exported.body()).contains("<bpmndi:BPMNDiagram").contains("horus:handler=\"cardProfiles.load\"");
        HttpResponse<String> imported = send("lan", "POST", "/api/bpm/processes/v1/definitions", exported.body(), null,
                null, "application/bpmn+xml");

        // Then the import finds the same definition: same hash, same version, no new row
        assertThat(imported.statusCode()).as(imported.body()).isEqualTo(201);
        JsonNode version = JSON.readTree(imported.body());
        assertThat(version.get("definitionHash").stringValue()).isEqualTo(hash);
        assertThat(version.get("version").intValue()).isEqualTo(latest);
        assertThat(definitionRows()).isEqualTo(rowsBefore);
    }

    @Test
    @Order(31)
    void PRE_034_another_tenants_version_is_not_found_and_nothing_is_written() throws Exception {
        // Given bank-b holds card-fee-review v99, which bank-a does not have
        jdbc.sql("INSERT INTO pre_definition (tenant_id, id, key_name, version_num, status, name, definition_json, "
                + "definition_hash, source_format, source_document, source_hash, node_count, source, effective_from, "
                + "created_by, created_at, approved_by, approved_at) "
                + "SELECT 'bank-b', '01JDBANKB00000000000000000', key_name, 99, status, name, definition_json, "
                + "definition_hash, source_format, source_document, source_hash, node_count, source, effective_from, "
                + "created_by, created_at, approved_by, approved_at FROM pre_definition "
                + "WHERE tenant_id = 'bank-a' AND key_name = 'card-fee-review' AND version_num = 1").update();
        long rowsBefore = definitionRows();
        long outboxBefore = jdbc.sql("SELECT count(*) FROM event_outbox").query(Long.class).single();

        // When a bank-a user asks for v99
        HttpResponse<String> answer = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/api/bpm/processes/v1/definitions/card-fee-review/versions/99/export?format=bpmn"))
                .header("Authorization", basic("lan")).GET().build(), HttpResponse.BodyHandlers.ofString());

        // Then it is not found, nothing of bank-b is disclosed and nothing is written
        assertThat(answer.statusCode()).as(answer.body()).isEqualTo(404);
        assertThat(JSON.readTree(answer.body()).get("code").stringValue()).isEqualTo("DEFINITION_NOT_FOUND");
        assertThat(answer.body()).doesNotContain("bank-b").doesNotContain("<bpmn:");
        assertThat(definitionRows()).isEqualTo(rowsBefore);
        assertThat(jdbc.sql("SELECT count(*) FROM event_outbox").query(Long.class).single()).isEqualTo(outboxBefore);
    }

    private long definitionRows() {
        return jdbc.sql("SELECT count(*) FROM pre_definition").query(Long.class).single();
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private String openTask(String applicationId) throws Exception {
        JsonNode submitted = post("tuan", "/api/cards/applications",
                "{\"applicationId\":\"" + applicationId + "\",\"customerId\":\"C-002\"}");
        JsonNode instance = get("an", START + "/" + submitted.get("instanceId").stringValue());
        return claimedBy("an", instance.at("/tasks/0/id").stringValue());
    }

    /** Claims the open task (lock version 0 to 1) and returns its completion path; complete with If-Match "1". */
    private String claimedBy(String user, String taskId) throws Exception {
        HttpResponse<String> claimed = send(user, "POST", "/api/bpm/processes/v1/tasks/" + taskId + ":claim", "", "\"0\"",
                "qa-claim-" + taskId);
        assertThat(claimed.statusCode()).as(claimed.body()).isEqualTo(200);
        return "/api/bpm/processes/v1/tasks/" + taskId + ":complete";
    }

    private static String startBody(String businessKey, String customerId) {
        return "{\"definitionKey\":\"card-fee-review\",\"businessKey\":\"" + businessKey + "\",\"variables\":"
                + "{\"applicationId\":\"" + businessKey + "\",\"customerId\":\"" + customerId + "\"}}";
    }

    private long instances(String businessKey) {
        return jdbc.sql("SELECT count(*) FROM pre_instance WHERE tenant_id = 'bank-a' AND business_key = ?")
                .param(businessKey).query(Long.class).single();
    }

    private long taskCompletedEntries(String instanceId) {
        return jdbc.sql("SELECT count(*) FROM pre_history WHERE tenant_id = 'bank-a' AND instance_id = ? "
                + "AND event_type = 'TASK_COMPLETED'").param(instanceId).query(Long.class).single();
    }

    private int decisionVersion(String decisionId) {
        return jdbc.sql("SELECT version_num FROM rle_decision WHERE tenant_id = 'bank-a' AND id = ?").param(decisionId)
                .query(Integer.class).single();
    }

    private static String id(HttpResponse<String> response) {
        try {
            JsonNode node = JSON.readTree(response.body());
            return node.has("id") ? node.get("id").stringValue() : "<" + response.statusCode() + ">";
        } catch (RuntimeException e) {
            return "<" + response.statusCode() + ">";
        }
    }

    private static String bodies(List<HttpResponse<String>> answers) {
        StringBuilder out = new StringBuilder();
        answers.stream().filter(a -> a.statusCode() >= 400).limit(2)
                .forEach(a -> out.append(a.statusCode()).append(' ').append(a.body()).append('\n'));
        return out.toString();
    }

    private static List<HttpResponse<String>> concurrently(int n, Callable<HttpResponse<String>> call) throws Exception {
        List<Callable<HttpResponse<String>>> calls = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            calls.add(call);
        }
        return concurrently(calls);
    }

    private static List<HttpResponse<String>> concurrently(List<Callable<HttpResponse<String>>> calls) throws Exception {
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (Callable<HttpResponse<String>> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            ready.await();
            go.countDown();
            List<HttpResponse<String>> out = new ArrayList<>();
            for (Future<HttpResponse<String>> f : futures) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private JsonNode post(String user, String path, String body) throws Exception {
        HttpResponse<String> response = send(user, "POST", path, body, null, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return JSON.readTree(response.body());
    }

    private JsonNode get(String user, String path) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", basic(user)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> send(String user, String method, String path, String body, String ifMatch,
            String idempotencyKey) throws Exception {
        return send(user, method, path, body, ifMatch, idempotencyKey, "application/json");
    }

    private HttpResponse<String> send(String user, String method, String path, String body, String ifMatch,
            String idempotencyKey, String contentType) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", basic(user)).header("Content-Type", contentType)
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        // An approval or rejection must name the lock version it was decided on (#234); these tests approve a version
        // just requested, at lock version 1, unless they pass their own If-Match.
        String lock = ifMatch == null && path.matches(".*:(approve|reject)$") ? "\"1\"" : ifMatch;
        if (lock != null) {
            request.header("If-Match", lock);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        } else if (path.endsWith(":complete")) {
            request.header("Idempotency-Key", "qa-" + java.util.UUID.randomUUID());
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String basic(String user) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":it-pass").getBytes(StandardCharsets.UTF_8));
    }
}
