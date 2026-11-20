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
// minh approves process definitions in the demo; here his approver role grants processes:approve only, so he is the
// caller without processes:read in a_caller_without_processes_read_is_refused_every_read.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"HORUS_DEMO_PASSWORD=it-pass",
        "horus.bpm.processes.security.role-permissions[ROLE_PROCESS_APPROVER]=processes:approve"})
@ActiveProfiles("dev")
@Testcontainers
class ProcessRestApiIT {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String PASSWORD = "it-pass";
    static final String API = "/api/bpm/processes/v1";

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(TestImages.postgres());

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
                    .authorities("ROLE_TELLER", "ROLE_CARD_OFFICER", "ROLE_PROCESS_DEPLOYER", "ROLE_BRANCH_SUPERVISOR",
                            "TENANT_bank-b").build());
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

    // ---- cancel (PRE-050, PD-103) ------------------------------------------------------------------------------

    static final String REASON = "Customer withdrew the card application";

    @Test
    void PRE_050_cancel_ends_the_instance_cancels_its_task_and_a_replay_returns_the_first_answer() throws Exception {
        String id = submit("IT-CANCEL-1", "C-002");
        String etag = etag(id);

        HttpResponse<String> cancelled = cancel("hoa", id, etag, "cancel-it-1", REASON);
        HttpResponse<String> replay = cancel("hoa", id, etag, "cancel-it-1", REASON);
        HttpResponse<String> otherReason = cancel("hoa", id, etag, "cancel-it-1", "Duplicate application");

        assertThat(cancelled.statusCode()).as(cancelled.body()).isEqualTo(200);
        JsonNode instance = JSON.readTree(cancelled.body());
        assertThat(instance.get("status").stringValue()).isEqualTo("CANCELLED");
        assertThat(instance.at("/tasks/0/status").stringValue()).isEqualTo("CANCELLED");
        assertThat(instance.get("endedAt").isNull()).isFalse();
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(cancelled.body());
        assertThat(problem(otherReason)).isEqualTo("422 IDEMPOTENCY_KEY_REUSE_MISMATCH");
        assertThat(ended(id)).as("one INSTANCE_ENDED entry, none from the replay").isEqualTo(1);
        assertThat(jdbc.sql("SELECT actor_id || ' | ' || (details->>'reason') FROM pre_history WHERE tenant_id = 'bank-a' "
                + "AND instance_id = ? AND event_type = 'INSTANCE_ENDED'").param(id).query(String.class).single())
                .isEqualTo("hoa | " + REASON);
        List<ProcessEventPublisher.Event> events = EventRecorder.EVENTS.stream()
                .filter(e -> e.type().equals("processes.instance.cancelled.v1") && id.equals(e.payload().get("instanceId")))
                .toList();
        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.payload().get("cancelledBy")).isEqualTo("hoa");
            assertThat(e.payload().get("reason")).isEqualTo(REASON);
        });
        assertThat(problem(cancel("hoa", id, etag(id), "cancel-it-1b", "Again"))).isEqualTo("409 INVALID_STATE");
    }

    @Test
    void PRE_054_cancel_needs_processes_cancel_and_a_reason_and_runs_no_step_or_compensation() throws Exception {
        String id = submit("IT-CANCEL-2", "C-002");
        String etag = etag(id);
        long before = history(id);

        HttpResponse<String> teller = cancel("tuan", id, etag, "cancel-it-2", REASON);
        HttpResponse<String> officer = cancel("an", id, etag, "cancel-it-3", REASON);

        assertThat(problem(teller)).as("tuan may start but not cancel").isEqualTo("403 PERMISSION_DENIED");
        assertThat(JSON.readTree(teller.body()).get("detail").stringValue()).contains("processes:cancel");
        assertThat(problem(officer)).isEqualTo("403 PERMISSION_DENIED");
        assertThat(status(id)).isEqualTo("RUNNING");
        assertThat(history(id)).as("a refused cancel writes nothing").isEqualTo(before);

        HttpResponse<String> supervisor = cancel("hoa", id, etag, "cancel-it-4", REASON);

        assertThat(supervisor.statusCode()).as(supervisor.body()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT event_type FROM pre_history WHERE tenant_id = 'bank-a' AND instance_id = ? "
                + "AND occurred_at > (SELECT max(occurred_at) FROM pre_history WHERE tenant_id = 'bank-a' "
                + "AND instance_id = ? AND event_type <> 'INSTANCE_ENDED' AND event_type <> 'TOKEN_CANCELLED') "
                + "ORDER BY occurred_at, id").params(id, id).query(String.class).list())
                .as("the cancel only cancels tokens and ends the instance: no step entered, no handler, no compensation")
                .containsOnly("TOKEN_CANCELLED", "INSTANCE_ENDED");
        assertThat(jdbc.sql("SELECT details->>'actor' || ' | ' || (details->>'reason') || ' | ' || (details->>'status') "
                + "FROM pre_history WHERE tenant_id = 'bank-a' AND instance_id = ? AND event_type = 'INSTANCE_ENDED'")
                .param(id).query(String.class).single()).isEqualTo("hoa | " + REASON + " | CANCELLED");
        assertThat(meLists("hoa")).contains("processes:cancel", "processes:start");
        assertThat(meLists("tuan")).contains("processes:start").doesNotContain("processes:cancel");
    }

    @Test
    void PRE_054_a_missing_blank_or_too_long_reason_is_400_and_nothing_is_written() throws Exception {
        String id = submit("IT-CANCEL-R", "C-002");
        String etag = etag(id);

        HttpResponse<String> none = send("hoa", "POST", API + "/instances/" + id + ":cancel", null,
                Map.of("If-Match", etag, "Idempotency-Key", "reason-1"));
        HttpResponse<String> blank = cancel("hoa", id, etag, "reason-2", "   ");
        HttpResponse<String> tooLong = cancel("hoa", id, etag, "reason-3", "x".repeat(2_001));
        HttpResponse<String> longest = cancel("hoa", id, etag, "reason-4", "x".repeat(2_000));

        assertThat(problem(none)).isEqualTo("400 INPUT_INVALID");
        assertThat(problem(blank)).isEqualTo("400 INPUT_INVALID");
        assertThat(problem(tooLong)).isEqualTo("400 INPUT_INVALID");
        assertThat(longest.statusCode()).as("2,000 characters is the limit, inclusive").isEqualTo(200);
        assertThat(ended(id)).isEqualTo(1);
    }

    @Test
    void PRE_050_a_stale_if_match_is_409_and_an_unknown_instance_is_404() throws Exception {
        String id = submit("IT-CANCEL-S", "C-002");

        assertThat(problem(cancel("hoa", id, "\"0\"", "cancel-it-5", REASON))).isEqualTo("409 VERSION_CONFLICT");
        assertThat(problem(cancel("hoa", "01JD0000000000000000000000", "\"1\"", "cancel-it-6", REASON)))
                .isEqualTo("404 INSTANCE_NOT_FOUND");
        assertThat(status(id)).isEqualTo("RUNNING");
        assertThat(ended(id)).isZero();
    }

    @Test
    void PRE_050_two_concurrent_cancels_with_one_lock_version_cancel_once() throws Exception {
        String id = submit("IT-CANCEL-3", "C-002");
        String etag = etag(id);

        List<HttpResponse<String>> answers = concurrently(2,
                () -> cancel("hoa", id, etag, "cancel-race-" + UUID.randomUUID(), REASON));

        assertThat(answers.stream().map(ProcessRestApiIT::problemOrOk).sorted().toList())
                .containsExactly("200", "409 VERSION_CONFLICT");
        assertThat(ended(id)).isEqualTo(1);
    }

    @Test
    void PRE_050_concurrent_cancels_with_one_idempotency_key_cancel_once_and_answer_alike() throws Exception {
        String id = submit("IT-CANCEL-4", "C-002");
        String etag = etag(id);

        List<HttpResponse<String>> answers = concurrently(4, () -> cancel("hoa", id, etag, "cancel-same-key", REASON));

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
        HttpResponse<String> cancel = cancel("zoe", id, "\"1\"", "zoe-cancel-1", REASON);
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
        String etag = etag(id);

        HttpResponse<String> switched = send("hoa", "POST", API + "/instances/" + id + ":cancel",
                "{\"reason\":\"" + REASON + "\"}", Map.of("If-Match", etag, "Idempotency-Key", "switch-1",
                        "X-Tenant-Id", "bank-b", "Content-Type", "application/json"));

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

    private HttpResponse<String> cancel(String user, String id, String ifMatch, String key, String reason)
            throws Exception {
        // Written with a JSON writer, so any reason is escaped.
        String body = JSON.writeValueAsString(Map.of("reason", reason));
        return send(user, "POST", API + "/instances/" + id + ":cancel", body, Map.of("If-Match", ifMatch,
                "Idempotency-Key", key, "Content-Type", "application/json"));
    }

    private String etag(String id) throws Exception {
        return send("tuan", "GET", API + "/instances/" + id, null, Map.of()).headers().firstValue("ETag").orElseThrow();
    }

    private List<String> meLists(String user) throws Exception {
        JsonNode me = JSON.readTree(send(user, "GET", "/api/bpm/me", null, Map.of()).body());
        List<String> out = new ArrayList<>();
        me.get("permissions").forEach(p -> out.add(p.stringValue()));
        return out;
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
