package io.github.dhluong90.bpm.examples.ruleandprocess;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.host.spi.TenantResolver;
import io.github.dhluong90.bpm.process.spi.ProcessEventPublisher;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The REST API for definitions, instances and cancellation on PostgreSQL over HTTP: reads and keyset lists,
 * idempotent and optimistically locked cancellation (also under concurrency), the permission checks, and tenant
 * isolation, where another tenant's request is refused and writes nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "HORUS_DEMO_PASSWORD=it-pass")
@ActiveProfiles("dev")
@Testcontainers
class ProcessRestApiIT {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String PASSWORD = "it-pass";
    static final String API = "/api/bpm/processes/v1";

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(System.getProperty("bpm.test.postgres.image", "postgres:16-alpine"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * The host's tenant resolver for this test: a {@code TENANT_<id>} authority names the tenant, otherwise the demo
     * tenant {@code bank-a}. User {@code zoe} works for {@code bank-b}.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class TenantFromAuthority {
        @Bean
        TenantResolver tenantFromAuthority() {
            return caller -> caller.authorities().stream().filter(a -> a.startsWith("TENANT_"))
                    .map(a -> a.substring(7)).findFirst().or(() -> Optional.of("bank-a"));
        }
    }

    /** Records events after commit, so rolled-back or replayed work never shows up. */
    @TestConfiguration(proxyBeanMethods = false)
    static class EventRecorder {
        static final List<ProcessEventPublisher.Event> EVENTS = new CopyOnWriteArrayList<>();

        @Bean
        Object cancelEventRecorder() {
            return new Object() {
                @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
                void on(ProcessEventPublisher.Event event) {
                    EVENTS.add(event);
                }
            };
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    InMemoryUserDetailsManager users;

    @BeforeEach
    void bankBUser() {
        if (!users.userExists("zoe")) {
            users.createUser(User.withUsername("zoe").password("{noop}" + PASSWORD)
                    .authorities("ROLE_TELLER", "ROLE_CARD_OFFICER", "ROLE_PROCESS_DEPLOYER", "TENANT_bank-b").build());
        }
    }

    // ---- definitions ------------------------------------------------------------------------------------------

    @Test
    void PRE_049_the_import_location_reads_the_version_with_its_definition_hash_as_etag() throws Exception {
        String source = jdbc.sql("SELECT source_document FROM pre_definition WHERE tenant_id = 'bank-a' "
                + "AND key_name = 'card-fee-review' AND version_num = 1").query(String.class).single();
        HttpResponse<String> imported = send("lan", "POST", API + "/definitions",
                source.replace("id=\"card-fee-review\"", "id=\"card-limit-review\""), Map.of("Content-Type", "application/xml"));
        assertThat(imported.statusCode()).as(imported.body()).isEqualTo(201);
        String location = imported.headers().firstValue("Location").orElseThrow();

        HttpResponse<String> read = send("tuan", "GET", location, null, Map.of());

        assertThat(location).isEqualTo(API + "/definitions/card-limit-review/versions/1");
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        JsonNode version = JSON.readTree(read.body());
        assertThat(read.headers().firstValue("ETag")).hasValue("\"" + version.get("definitionHash").stringValue() + "\"");
        assertThat(version.get("key").stringValue()).isEqualTo("card-limit-review");
        assertThat(version.get("status").stringValue()).isEqualTo("ACTIVE");
        assertThat(version.get("approvedBy").stringValue()).isEqualTo("system:dev-auto-approve");
        assertThat(version.get("effectiveFrom").stringValue()).endsWith("Z");
        assertThat(version.has("sourceDocument")).isFalse();
        assertThat(problem(send("tuan", "GET", API + "/definitions/card-limit-review/versions/9", null, Map.of())))
                .isEqualTo("404 DEFINITION_NOT_FOUND");
    }

    @Test
    void PRE_049_the_definition_list_pages_by_key_with_the_effective_version() throws Exception {
        JsonNode first = json(send("tuan", "GET", API + "/definitions?limit=1", null, Map.of()));
        List<String> keys = new ArrayList<>();
        first.get("data").forEach(d -> keys.add(d.get("key").stringValue()));
        String cursor = first.get("nextCursor").isNull() ? null : first.get("nextCursor").stringValue();
        while (cursor != null) {
            JsonNode next = json(send("tuan", "GET", API + "/definitions?limit=1&cursor=" + cursor, null, Map.of()));
            next.get("data").forEach(d -> keys.add(d.get("key").stringValue()));
            cursor = next.get("hasNext").booleanValue() ? next.get("nextCursor").stringValue() : null;
        }

        assertThat(keys).contains("card-fee-review").isSorted().doesNotHaveDuplicates();
        JsonNode all = json(send("tuan", "GET", API + "/definitions", null, Map.of()));
        JsonNode cardFee = null;
        for (JsonNode d : all.get("data")) {
            if (d.get("key").stringValue().equals("card-fee-review")) {
                cardFee = d;
            }
        }
        assertThat(cardFee).isNotNull();
        assertThat(cardFee.get("latestStatus").stringValue()).isEqualTo("ACTIVE");
        assertThat(cardFee.get("effectiveVersion").intValue()).isEqualTo(cardFee.get("latestVersion").intValue());
        assertThat(cardFee.get("lastChangedAt").stringValue()).endsWith("Z");
    }

    // ---- instance list ----------------------------------------------------------------------------------------

    @Test
    void PRE_049_the_instance_list_pages_newest_first_without_gaps_or_duplicates() throws Exception {
        List<String> started = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            started.add(submit("IT-LIST-" + i, "C-002"));
        }

        List<String> listed = new ArrayList<>();
        String query = API + "/instances?definitionKey=card-fee-review&limit=2";
        JsonNode page = json(send("an", "GET", query, null, Map.of()));
        while (true) {
            page.get("data").forEach(i -> listed.add(i.get("id").stringValue()));
            assertThat(page.get("data").size()).isLessThanOrEqualTo(2);
            if (!page.get("hasNext").booleanValue()) {
                break;
            }
            page = json(send("an", "GET", query + "&cursor=" + page.get("nextCursor").stringValue(), null, Map.of()));
        }

        assertThat(listed).doesNotHaveDuplicates().containsAll(started);
        List<String> newestFirst = new ArrayList<>(started).reversed();
        assertThat(listed.stream().filter(started::contains).toList()).isEqualTo(newestFirst);
        JsonNode item = json(send("an", "GET", API + "/instances?limit=1", null, Map.of())).at("/data/0");
        assertThat(item.has("variables")).as("list items carry no variables").isFalse();
        assertThat(item.get("status").stringValue()).isEqualTo("RUNNING");
    }

    @Test
    void PRE_049_list_limits_ranges_statuses_and_edited_cursors_are_400_QUERY_INVALID() throws Exception {
        submit("IT-Q-1", "C-002");
        submit("IT-Q-2", "C-002");
        String cursor = json(send("an", "GET", API + "/instances?limit=1", null, Map.of())).get("nextCursor").stringValue();
        String edited = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "instances:2027-01-01T00:00:00Z|01JD0000000000000000000000".getBytes(StandardCharsets.UTF_8))
                + cursor.substring(cursor.indexOf('.'));

        for (String query : List.of("limit=0", "limit=201", "status=OPEN",
                "startedFrom=2027-01-01T00:00:00Z&startedTo=2027-02-02T00:00:00Z",
                "startedFrom=2027-02-01T00:00:00Z&startedTo=2027-01-01T00:00:00Z",
                "cursor=" + edited, "cursor=" + cursor + "&status=COMPLETED")) {
            assertThat(problem(send("an", "GET", API + "/instances?" + query, null, Map.of()))).as(query)
                    .isEqualTo("400 QUERY_INVALID");
        }
        assertThat(problem(send("an", "GET", API + "/definitions?limit=500", null, Map.of())))
                .isEqualTo("400 QUERY_INVALID");
    }

    @Test
    void PRE_049_an_instance_list_with_one_bound_covers_query_max_range_from_it() throws Exception {
        String id = submit("IT-RANGE-1", "C-002");
        String startedAt = json(send("an", "GET", API + "/instances/" + id, null, Map.of())).get("startedAt").stringValue();
        String from = URLEncoder.encode(java.time.Instant.parse(startedAt).minusSeconds(60).toString(), StandardCharsets.UTF_8);
        String to = URLEncoder.encode(java.time.Instant.parse(startedAt).minusSeconds(30).toString(), StandardCharsets.UTF_8);

        assertThat(ids(json(send("an", "GET", API + "/instances?startedFrom=" + from, null, Map.of())))).contains(id);
        assertThat(ids(json(send("an", "GET", API + "/instances?startedTo=" + to, null, Map.of())))).doesNotContain(id);
    }

    // ---- cancel -----------------------------------------------------------------------------------------------

    @Test
    void PRE_050_cancel_ends_the_instance_cancels_its_task_and_a_replay_returns_the_first_answer() throws Exception {
        String id = submit("IT-CANCEL-1", "C-002");
        HttpResponse<String> before = send("tuan", "GET", API + "/instances/" + id, null, Map.of());
        String etag = before.headers().firstValue("ETag").orElseThrow();
        Map<String, String> headers = Map.of("If-Match", etag, "Idempotency-Key", "cancel-it-1",
                "Content-Type", "application/json");
        String body = "{\"reason\":\"Customer withdrew the card application\"}";

        HttpResponse<String> cancelled = send("tuan", "POST", API + "/instances/" + id + ":cancel", body, headers);
        HttpResponse<String> replay = send("tuan", "POST", API + "/instances/" + id + ":cancel", body, headers);
        HttpResponse<String> otherReason = send("tuan", "POST", API + "/instances/" + id + ":cancel",
                "{\"reason\":\"Duplicate application\"}", headers);

        assertThat(cancelled.statusCode()).as(cancelled.body()).isEqualTo(200);
        JsonNode instance = JSON.readTree(cancelled.body());
        assertThat(instance.get("status").stringValue()).isEqualTo("CANCELLED");
        assertThat(instance.at("/tasks/0/status").stringValue()).isEqualTo("CANCELLED");
        assertThat(instance.get("endedAt").isNull()).isFalse();
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(cancelled.body());
        assertThat(problem(otherReason)).isEqualTo("422 IDEMPOTENCY_KEY_REUSE_MISMATCH");
        assertThat(ended(id)).as("one INSTANCE_ENDED entry, none from the replay").isEqualTo(1);
        assertThat(jdbc.sql("SELECT details->>'reason' FROM pre_history WHERE tenant_id = 'bank-a' AND instance_id = ? "
                + "AND event_type = 'INSTANCE_ENDED'").param(id).query(String.class).single())
                .isEqualTo("Customer withdrew the card application");
        assertThat(EventRecorder.EVENTS.stream().filter(e -> e.type().equals("processes.instance.cancelled.v1")
                && id.equals(e.payload().get("instanceId"))).count()).isEqualTo(1);

        String now = send("tuan", "GET", API + "/instances/" + id, null, Map.of()).headers().firstValue("ETag").orElseThrow();
        assertThat(problem(send("tuan", "POST", API + "/instances/" + id + ":cancel", null,
                Map.of("If-Match", now, "Idempotency-Key", "cancel-it-1b")))).isEqualTo("409 INVALID_STATE");
    }

    @Test
    void PRE_050_a_stale_if_match_is_409_VERSION_CONFLICT_and_a_caller_without_start_permission_is_403() throws Exception {
        String id = submit("IT-CANCEL-2", "C-002");

        HttpResponse<String> stale = send("tuan", "POST", API + "/instances/" + id + ":cancel", null,
                Map.of("If-Match", "\"0\"", "Idempotency-Key", "cancel-it-2"));
        HttpResponse<String> officer = send("an", "POST", API + "/instances/" + id + ":cancel", null,
                Map.of("If-Match", "\"1\"", "Idempotency-Key", "cancel-it-3"));
        HttpResponse<String> missing = send("tuan", "POST", API + "/instances/01JD0000000000000000000000:cancel", null,
                Map.of("If-Match", "\"1\"", "Idempotency-Key", "cancel-it-4"));

        assertThat(problem(stale)).isEqualTo("409 VERSION_CONFLICT");
        assertThat(problem(officer)).isEqualTo("403 PERMISSION_DENIED");
        assertThat(problem(missing)).isEqualTo("404 INSTANCE_NOT_FOUND");
        assertThat(status(id)).isEqualTo("RUNNING");
        assertThat(ended(id)).isZero();
    }

    @Test
    void PRE_050_two_concurrent_cancels_with_one_lock_version_cancel_once() throws Exception {
        String id = submit("IT-CANCEL-3", "C-002");
        String etag = send("tuan", "GET", API + "/instances/" + id, null, Map.of()).headers().firstValue("ETag").orElseThrow();

        List<HttpResponse<String>> answers = concurrently(2, () -> send("tuan", "POST", API + "/instances/" + id + ":cancel",
                null, Map.of("If-Match", etag, "Idempotency-Key", "cancel-race-" + UUID.randomUUID())));

        assertThat(answers.stream().map(ProcessRestApiIT::problemOrOk).sorted().toList())
                .containsExactly("200", "409 VERSION_CONFLICT");
        assertThat(ended(id)).isEqualTo(1);
    }

    @Test
    void PRE_050_concurrent_cancels_with_one_idempotency_key_cancel_once_and_answer_alike() throws Exception {
        String id = submit("IT-CANCEL-4", "C-002");
        String etag = send("tuan", "GET", API + "/instances/" + id, null, Map.of()).headers().firstValue("ETag").orElseThrow();

        List<HttpResponse<String>> answers = concurrently(4, () -> send("tuan", "POST", API + "/instances/" + id + ":cancel",
                null, Map.of("If-Match", etag, "Idempotency-Key", "cancel-same-key")));

        assertThat(answers).extracting(HttpResponse::statusCode).containsOnly(200);
        assertThat(answers.stream().map(HttpResponse::body).distinct()).hasSize(1);
        assertThat(ended(id)).isEqualTo(1);
    }

    // ---- tenant isolation (failure path) ----------------------------------------------------------------------

    @Test
    void TR_TEN_01_another_tenant_cannot_read_list_or_cancel_an_instance_and_nothing_is_written() throws Exception {
        String id = submit("IT-TENANT-1", "C-002");
        long history = history(id);

        HttpResponse<String> read = send("zoe", "GET", API + "/instances/" + id, null, Map.of());
        HttpResponse<String> cancel = send("zoe", "POST", API + "/instances/" + id + ":cancel", null,
                Map.of("If-Match", "\"1\"", "Idempotency-Key", "zoe-cancel-1"));
        JsonNode list = json(send("zoe", "GET", API + "/instances?status=RUNNING,COMPLETED,CANCELLED", null, Map.of()));
        JsonNode definitions = json(send("zoe", "GET", API + "/definitions", null, Map.of()));
        HttpResponse<String> version = send("zoe", "GET", API + "/definitions/card-fee-review/versions/1", null, Map.of());

        assertThat(problem(read)).isEqualTo("404 INSTANCE_NOT_FOUND");
        assertThat(problem(cancel)).isEqualTo("404 INSTANCE_NOT_FOUND");
        assertThat(ids(list)).doesNotContain(id);
        assertThat(definitions.get("data").size()).isZero();
        assertThat(problem(version)).isEqualTo("404 DEFINITION_NOT_FOUND");
        assertThat(status(id)).isEqualTo("RUNNING");
        assertThat(history(id)).isEqualTo(history);
        assertThat(jdbc.sql("SELECT count(*) FROM pre_idempotency WHERE tenant_id = 'bank-b'").query(Long.class).single())
                .as("the refused cancel left no idempotency record").isZero();
    }

    @Test
    void TR_TEN_01_a_tenant_header_for_another_tenant_is_403_and_nothing_is_written() throws Exception {
        String id = submit("IT-TENANT-2", "C-002");
        String etag = send("tuan", "GET", API + "/instances/" + id, null, Map.of()).headers().firstValue("ETag").orElseThrow();

        HttpResponse<String> switched = send("tuan", "POST", API + "/instances/" + id + ":cancel", null,
                Map.of("If-Match", etag, "Idempotency-Key", "switch-1", "X-Tenant-Id", "bank-b"));

        assertThat(problem(switched)).isEqualTo("403 TENANT_NOT_ALLOWED");
        assertThat(status(id)).isEqualTo("RUNNING");
        assertThat(ended(id)).isZero();
    }

    @Test
    void PRE_049_a_cursor_issued_to_one_tenant_is_refused_for_another() throws Exception {
        submit("IT-TENANT-3", "C-002");
        submit("IT-TENANT-4", "C-002");
        String cursor = json(send("an", "GET", API + "/instances?limit=1", null, Map.of())).get("nextCursor").stringValue();

        assertThat(problem(send("zoe", "GET", API + "/instances?limit=1&cursor=" + cursor, null, Map.of())))
                .isEqualTo("400 QUERY_INVALID");
    }

    @Test
    void a_caller_without_processes_read_is_refused_every_read() throws Exception {
        for (String path : List.of(API + "/instances", API + "/definitions", API + "/definitions/card-fee-review/versions/1")) {
            assertThat(problem(send("minh", "GET", path, null, Map.of()))).as(path).isEqualTo("403 PERMISSION_DENIED");
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private String submit(String applicationId, String customerId) throws Exception {
        HttpResponse<String> response = send("tuan", "POST", "/api/cards/applications",
                "{\"applicationId\":\"" + applicationId + "\",\"customerId\":\"" + customerId + "\"}",
                Map.of("Content-Type", "application/json"));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return JSON.readTree(response.body()).get("instanceId").stringValue();
    }

    private String status(String id) {
        return jdbc.sql("SELECT status FROM pre_instance WHERE tenant_id = 'bank-a' AND id = ?").param(id)
                .query(String.class).single();
    }

    private long ended(String id) {
        return jdbc.sql("SELECT count(*) FROM pre_history WHERE tenant_id = 'bank-a' AND instance_id = ? "
                + "AND event_type = 'INSTANCE_ENDED'").param(id).query(Long.class).single();
    }

    private long history(String id) {
        return jdbc.sql("SELECT count(*) FROM pre_history WHERE instance_id = ?").param(id).query(Long.class).single();
    }

    private static Set<String> ids(JsonNode page) {
        Set<String> out = new HashSet<>();
        page.get("data").forEach(i -> out.add(i.get("id").stringValue()));
        return out;
    }

    private static JsonNode json(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static String problem(HttpResponse<String> response) {
        assertThat(response.headers().firstValue("Content-Type")).as(response.body()).hasValue("application/problem+json");
        return response.statusCode() + " " + JSON.readTree(response.body()).get("code").stringValue();
    }

    private static String problemOrOk(HttpResponse<String> response) {
        return response.statusCode() == 200 ? "200" : problem(response);
    }

    private HttpResponse<String> send(String user, String method, String path, String body, Map<String, String> headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString((user + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8)))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static List<HttpResponse<String>> concurrently(int n, Callable<HttpResponse<String>> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(30, TimeUnit.SECONDS);
                    return call.call();
                }));
            }
            ready.await(30, TimeUnit.SECONDS);
            go.countDown();
            List<HttpResponse<String>> answers = new ArrayList<>();
            for (Future<HttpResponse<String>> f : futures) {
                answers.add(f.get(60, TimeUnit.SECONDS));
            }
            return answers;
        } finally {
            pool.shutdownNow();
        }
    }
}
