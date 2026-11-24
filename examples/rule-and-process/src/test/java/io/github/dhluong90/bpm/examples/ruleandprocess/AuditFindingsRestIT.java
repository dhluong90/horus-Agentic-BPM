package io.github.dhluong90.bpm.examples.ruleandprocess;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Regression tests over HTTP of the pre-1.0 audit findings (#572): a start with an existing business key answers a
 * caller that only holds {@code processes:start} with the id, status and {@code created: false} (AUD-01), and a task is
 * read only by who it is for (AUD-05).
 *
 * <p>Users: {@code tuan} (teller, reads) submits; {@code starter} may only start; {@code an} is a card officer, the
 * assignee; {@code hung} works tasks but is not an assignee; {@code hoa} manages tasks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "HORUS_DEMO_PASSWORD=it-pass",
        "horus.bpm.processes.security.role-permissions[ROLE_START_ONLY]=processes:start"})
@ActiveProfiles("dev")
@Testcontainers
class AuditFindingsRestIT {

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

    @LocalServerPort
    int port;

    @Autowired
    InMemoryUserDetailsManager users;

    @BeforeEach
    void starterOnly() {
        if (!users.userExists("starter")) {
            users.createUser(User.withUsername("starter").password("{noop}" + PASSWORD)
                    .authorities("ROLE_START_ONLY").build());
        }
    }

    private HttpResponse<String> start(String user, String businessKey, String idempotencyKey) throws Exception {
        return send(user, "POST", API + "/instances", """
                {"definitionKey":"card-fee-review","businessKey":"%s",
                 "variables":{"applicationId":"%s","customerId":"C-002","ssn":"123-45-6789"}}""".formatted(businessKey,
                        businessKey), Map.of("Content-Type", "application/json", "Idempotency-Key", idempotencyKey));
    }

    @Test
    void PRE_356_control_a_start_only_caller_learns_nothing_of_an_existing_application_but_its_id_and_status()
            throws Exception {
        HttpResponse<String> created = start("tuan", "IT-AUD-01", "aud-1");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);

        HttpResponse<String> guess = start("starter", "IT-AUD-01", "aud-2");

        assertThat(guess.statusCode()).as(guess.body()).isEqualTo(200);
        JsonNode body = JSON.readTree(guess.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("id", "status", "created");
        assertThat(body.get("created").booleanValue()).isFalse();
        assertThat(guess.body()).doesNotContain("123-45-6789");
        HttpResponse<String> replay = start("starter", "IT-AUD-01", "aud-2");
        assertThat(replay.body()).isEqualTo(guess.body());
        HttpResponse<String> reader = start("tuan", "IT-AUD-01", "aud-3");
        assertThat(reader.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(reader.body()).at("/variables/ssn").stringValue()).isEqualTo("123-45-6789");
    }

    @Test
    void PRE_358_control_a_task_is_read_by_its_assignee_and_a_task_manager_and_by_nobody_else() throws Exception {
        String instance = JSON.readTree(start("tuan", "IT-AUD-05", "aud-5").body()).get("id").stringValue();
        String task = JSON.readTree(send("an", "GET", API + "/instances/" + instance, null, Map.of()).body())
                .at("/tasks/0/id").stringValue();

        assertThat(send("an", "GET", API + "/tasks/" + task, null, Map.of()).statusCode()).isEqualTo(200);
        assertThat(send("hoa", "GET", API + "/tasks/" + task, null, Map.of()).statusCode()).isEqualTo(200);
        for (String path : new String[] {API + "/tasks/" + task, API + "/tasks/" + task + "/form"}) {
            HttpResponse<String> other = send("hung", "GET", path, null, Map.of());
            assertThat(other.statusCode()).as(other.body()).isEqualTo(404);
            assertThat(JSON.readTree(other.body()).get("code").stringValue()).isEqualTo("TASK_NOT_FOUND");
        }
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
}
