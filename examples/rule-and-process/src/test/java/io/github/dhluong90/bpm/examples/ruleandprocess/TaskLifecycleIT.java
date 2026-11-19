package io.github.dhluong90.bpm.examples.ruleandprocess;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.host.spi.TenantResolver;
import io.github.dhluong90.bpm.process.spi.ProcessEventPublisher;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * The human task lifecycle over HTTP on PostgreSQL (DESIGN 3.5): read, claim, unclaim and complete, each with
 * {@code If-Match} and {@code Idempotency-Key}; stale and racing requests refused with a conflict and written once;
 * four eyes at claim and at completion; another tenant's task not found and nothing written.
 *
 * <p>Users: {@code tuan} (teller) submits; {@code an} and {@code hoa} are card officers, {@code hoa} also a teller;
 * {@code zoe} is an officer of {@code bank-b}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "HORUS_DEMO_PASSWORD=it-pass")
@ActiveProfiles("dev")
@Testcontainers
class TaskLifecycleIT {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String PASSWORD = "it-pass";
    static final String TASKS = "/api/bpm/processes/v1/tasks/";
    static final String APPROVE = "{\"result\":{\"approved\":true}}";

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(System.getProperty("bpm.test.postgres.image", "postgres:16-alpine"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** A {@code TENANT_<id>} authority names the tenant, otherwise {@code bank-a}. */
    @TestConfiguration(proxyBeanMethods = false)
    static class TenantFromAuthority {
        @Bean
        TenantResolver tenantFromAuthority() {
            return caller -> caller.authorities().stream().filter(a -> a.startsWith("TENANT_"))
                    .map(a -> a.substring(7)).findFirst().or(() -> Optional.of("bank-a"));
        }
    }

    /** Records task events after commit, so rolled-back and replayed work never shows up. */
    @TestConfiguration(proxyBeanMethods = false)
    static class EventRecorder {
        static final List<ProcessEventPublisher.Event> EVENTS = new CopyOnWriteArrayList<>();

        @Bean
        Object taskEventRecorder() {
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
    void bankBOfficer() {
        if (!users.userExists("zoe")) {
            users.createUser(User.withUsername("zoe").password("{noop}" + PASSWORD)
                    .authorities("ROLE_CARD_OFFICER", "TENANT_bank-b").build());
        }
    }

    // ---- read ---------------------------------------------------------------------------------------------

    @Test
    void a_task_reads_with_its_lock_version_as_etag_and_the_actions_each_caller_may_take() throws Exception {
        String task = openTask("hoa", "IT-T-READ");

        HttpResponse<String> forAn = send("an", "GET", TASKS + task, null, Map.of());
        HttpResponse<String> forHoa = send("hoa", "GET", TASKS + task, null, Map.of());
        HttpResponse<String> forTuan = send("tuan", "GET", TASKS + task, null, Map.of());

        assertThat(forAn.statusCode()).as(forAn.body()).isEqualTo(200);
        assertThat(forAn.headers().firstValue("ETag")).hasValue("\"0\"");
        JsonNode view = JSON.readTree(forAn.body());
        assertThat(view.get("status").stringValue()).isEqualTo("OPEN");
        assertThat(view.get("fourEyes").booleanValue()).isTrue();
        assertThat(view.get("definitionKey").stringValue()).isEqualTo("card-fee-review");
        assertThat(view.get("businessKey").stringValue()).isEqualTo("IT-T-READ");
        assertThat(view.get("allowedActions").toString()).isEqualTo("[\"claim\"]");
        assertThat(JSON.readTree(forHoa.body()).get("allowedActions").toString())
                .as("hoa submitted it, so four eyes leaves her nothing to do").isEqualTo("[]");
        assertThat(problem(forTuan)).isEqualTo("403 PERMISSION_DENIED");
    }

    // ---- claim, unclaim -----------------------------------------------------------------------------------

    @Test
    void claim_and_unclaim_are_idempotent_and_each_writes_one_history_entry_and_one_event() throws Exception {
        String task = openTask("tuan", "IT-T-CLAIM");

        HttpResponse<String> claimed = claim("an", task, "\"0\"", "claim-1");
        HttpResponse<String> replay = claim("an", task, "\"0\"", "claim-1");
        HttpResponse<String> otherVersion = claim("an", task, "\"5\"", "claim-1");
        HttpResponse<String> byOther = unclaim("hoa", task, "\"1\"", "unclaim-hoa");
        HttpResponse<String> released = unclaim("an", task, "\"1\"", "unclaim-1");
        HttpResponse<String> releasedReplay = unclaim("an", task, "\"1\"", "unclaim-1");

        assertThat(claimed.statusCode()).as(claimed.body()).isEqualTo(200);
        JsonNode view = JSON.readTree(claimed.body());
        assertThat(view.get("status").stringValue()).isEqualTo("CLAIMED");
        assertThat(view.get("claimedBy").stringValue()).isEqualTo("an");
        assertThat(view.get("lockVersion").intValue()).isEqualTo(1);
        assertThat(view.get("allowedActions").toString()).isEqualTo("[\"unclaim\",\"complete\"]");
        assertThat(claimed.headers().firstValue("ETag")).hasValue("\"1\"");
        assertThat(replay.body()).isEqualTo(claimed.body());
        assertThat(problem(otherVersion)).isEqualTo("422 IDEMPOTENCY_KEY_REUSE_MISMATCH");
        assertThat(problem(byOther)).isEqualTo("403 TASK_NOT_ASSIGNED");
        assertThat(released.statusCode()).as(released.body()).isEqualTo(200);
        assertThat(JSON.readTree(released.body()).get("status").stringValue()).isEqualTo("OPEN");
        assertThat(JSON.readTree(released.body()).get("claimedBy").isNull()).isTrue();
        assertThat(releasedReplay.body()).isEqualTo(released.body());
        assertThat(history(task, "TASK_CLAIMED")).isEqualTo(1);
        assertThat(history(task, "TASK_UNCLAIMED")).isEqualTo(1);
        assertThat(events(task, "processes.task.claimed.v1")).isEqualTo(1);
        assertThat(events(task, "processes.task.unclaimed.v1")).isEqualTo(1);
        assertThat(problem(unclaim("an", task, "\"2\"", "unclaim-2"))).as("an open task cannot be unclaimed")
                .isEqualTo("409 INVALID_STATE");
    }

    @Test
    void TR_IDEM_03_two_officers_claiming_at_once_with_one_lock_version_claim_once() throws Exception {
        String task = openTask("tuan", "IT-T-RACE");

        List<HttpResponse<String>> answers = concurrently(List.of(
                () -> claim("an", task, "\"0\"", "race-an"),
                () -> claim("hoa", task, "\"0\"", "race-hoa")));

        assertThat(answers.stream().map(TaskLifecycleIT::problemOrOk).sorted().toList())
                .containsExactly("200", "409 VERSION_CONFLICT");
        assertThat(history(task, "TASK_CLAIMED")).isEqualTo(1);
    }

    // ---- complete (PRE-003) -------------------------------------------------------------------------------

    @Test
    void PRE_003_two_completions_at_once_with_one_lock_version_complete_once() throws Exception {
        String task = openTask("tuan", "IT-T-DOUBLE");
        assertThat(claim("an", task, "\"0\"", "double-claim").statusCode()).isEqualTo(200);

        List<HttpResponse<String>> answers = concurrently(List.of(
                () -> complete("an", task, "\"1\"", "double-1"),
                () -> complete("an", task, "\"1\"", "double-2")));

        assertThat(answers.stream().map(TaskLifecycleIT::problemOrOk).sorted().toList())
                .containsExactly("200", "409 VERSION_CONFLICT");
        assertThat(history(task, "TASK_COMPLETED")).isEqualTo(1);
        assertThat(events(task, "processes.task.completed.v1")).isEqualTo(1);
    }

    @Test
    void PRE_003_an_open_task_must_be_claimed_before_it_is_completed_and_nothing_is_written() throws Exception {
        String task = openTask("tuan", "IT-T-OPEN");

        HttpResponse<String> unclaimed = complete("an", task, "\"0\"", "open-1");

        assertThat(problem(unclaimed)).isEqualTo("409 INVALID_STATE");
        assertThat(JSON.readTree(unclaimed.body()).get("detail").stringValue()).contains("claim it first");
        assertThat(task(task).get("status").stringValue()).isEqualTo("OPEN");
        assertThat(task(task).get("lockVersion").intValue()).isZero();
        assertThat(history(task, "TASK_COMPLETED")).isZero();
    }

    @Test
    void PRE_003_a_task_claimed_by_another_officer_is_refused_with_403() throws Exception {
        String task = openTask("tuan", "IT-T-OTHER");
        assertThat(claim("an", task, "\"0\"", "other-claim").statusCode()).isEqualTo(200);

        assertThat(problem(complete("hoa", task, "\"1\"", "other-1"))).isEqualTo("403 TASK_NOT_ASSIGNED");
        assertThat(problem(claim("hoa", task, "\"1\"", "other-2"))).isEqualTo("409 INVALID_STATE");
        assertThat(task(task).get("claimedBy").stringValue()).isEqualTo("an");
        assertThat(history(task, "TASK_COMPLETED")).isZero();
    }

    // ---- four eyes (PRE-007) ------------------------------------------------------------------------------

    @Test
    void PRE_007_the_submitter_can_neither_claim_nor_complete_the_four_eyes_review() throws Exception {
        String task = openTask("hoa", "IT-T-4EYES");

        HttpResponse<String> ownClaim = claim("hoa", task, "\"0\"", "own-claim");

        assertThat(problem(ownClaim)).isEqualTo("403 SELF_APPROVAL_FORBIDDEN");
        assertThat(task(task).get("status").stringValue()).isEqualTo("OPEN");
        assertThat(history(task, "TASK_CLAIMED")).isZero();
        assertThat(claim("an", task, "\"0\"", "an-claim").statusCode()).isEqualTo(200);
        HttpResponse<String> done = complete("an", task, "\"1\"", "an-done");
        assertThat(done.statusCode()).as(done.body()).isEqualTo(200);
        assertThat(JSON.readTree(done.body()).at("/tasks/0/completedBy").stringValue()).isEqualTo("an");
        assertThat(JSON.readTree(done.body()).at("/tasks/0/claimedBy").stringValue()).isEqualTo("an");
    }

    // ---- tenant isolation and permissions -----------------------------------------------------------------

    @Test
    void TR_TEN_01_another_tenant_cannot_read_claim_unclaim_or_complete_a_task_and_nothing_is_written() throws Exception {
        String task = openTask("tuan", "IT-T-TENANT");

        assertThat(problem(send("zoe", "GET", TASKS + task, null, Map.of()))).isEqualTo("404 TASK_NOT_FOUND");
        assertThat(problem(claim("zoe", task, "\"0\"", "zoe-1"))).isEqualTo("404 TASK_NOT_FOUND");
        assertThat(problem(unclaim("zoe", task, "\"0\"", "zoe-2"))).isEqualTo("404 TASK_NOT_FOUND");
        assertThat(problem(complete("zoe", task, "\"0\"", "zoe-3"))).isEqualTo("404 TASK_NOT_FOUND");

        assertThat(task(task).get("status").stringValue()).isEqualTo("OPEN");
        assertThat(task(task).get("lockVersion").intValue()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM pre_idempotency WHERE tenant_id = 'bank-b'").query(Long.class).single())
                .isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM pre_history WHERE tenant_id = 'bank-b'").query(Long.class).single())
                .isZero();
    }

    @Test
    void a_caller_without_processes_task_work_cannot_claim_and_preconditions_are_required() throws Exception {
        String task = openTask("tuan", "IT-T-PERM");

        assertThat(problem(claim("tuan", task, "\"0\"", "tuan-claim"))).isEqualTo("403 PERMISSION_DENIED");
        assertThat(problem(send("an", "POST", TASKS + task + ":claim", null, Map.of("Idempotency-Key", "no-if-match"))))
                .isEqualTo("428 PRECONDITION_REQUIRED");
        assertThat(problem(send("an", "POST", TASKS + task + ":claim", null, Map.of("If-Match", "\"0\""))))
                .isEqualTo("428 PRECONDITION_REQUIRED");
        assertThat(history(task, "TASK_CLAIMED")).isZero();
    }

    @Test
    void cancelling_an_instance_cancels_its_claimed_task() throws Exception {
        String task = openTask("tuan", "IT-T-CANCEL");
        assertThat(claim("an", task, "\"0\"", "cancel-claim").statusCode()).isEqualTo(200);
        String instance = task(task).get("instanceId").stringValue();
        String etag = send("tuan", "GET", "/api/bpm/processes/v1/instances/" + instance, null, Map.of()).headers()
                .firstValue("ETag").orElseThrow();

        HttpResponse<String> cancelled = send("tuan", "POST", "/api/bpm/processes/v1/instances/" + instance + ":cancel",
                null, Map.of("If-Match", etag, "Idempotency-Key", "cancel-claimed"));

        assertThat(cancelled.statusCode()).as(cancelled.body()).isEqualTo(200);
        assertThat(task(task).get("status").stringValue()).isEqualTo("CANCELLED");
        assertThat(problem(complete("an", task, "\"2\"", "after-cancel"))).isEqualTo("409 INVALID_STATE");
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private String openTask(String submitter, String applicationId) throws Exception {
        HttpResponse<String> submitted = send(submitter, "POST", "/api/cards/applications",
                "{\"applicationId\":\"" + applicationId + "\",\"customerId\":\"C-002\"}",
                Map.of("Content-Type", "application/json"));
        assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(201);
        String instance = JSON.readTree(submitted.body()).get("instanceId").stringValue();
        HttpResponse<String> read = send("an", "GET", "/api/bpm/processes/v1/instances/" + instance, null, Map.of());
        return JSON.readTree(read.body()).at("/tasks/0/id").stringValue();
    }

    private JsonNode task(String id) throws Exception {
        HttpResponse<String> response = send("an", "GET", TASKS + id, null, Map.of());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> claim(String user, String task, String ifMatch, String key) throws Exception {
        return send(user, "POST", TASKS + task + ":claim", null, Map.of("If-Match", ifMatch, "Idempotency-Key", key));
    }

    private HttpResponse<String> unclaim(String user, String task, String ifMatch, String key) throws Exception {
        return send(user, "POST", TASKS + task + ":unclaim", null, Map.of("If-Match", ifMatch, "Idempotency-Key", key));
    }

    private HttpResponse<String> complete(String user, String task, String ifMatch, String key) throws Exception {
        return send(user, "POST", TASKS + task + ":complete", APPROVE, Map.of("If-Match", ifMatch,
                "Idempotency-Key", key, "Content-Type", "application/json"));
    }

    private long history(String task, String type) {
        return jdbc.sql("SELECT count(*) FROM pre_history WHERE tenant_id = 'bank-a' AND event_type = ? "
                + "AND details->>'taskId' = ?").params(type, task).query(Long.class).single();
    }

    private static long events(String task, String type) {
        return EventRecorder.EVENTS.stream().filter(e -> e.type().equals(type) && task.equals(e.payload().get("taskId")))
                .count();
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

    private static List<HttpResponse<String>> concurrently(List<Callable<HttpResponse<String>>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (Callable<HttpResponse<String>> call : calls) {
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
