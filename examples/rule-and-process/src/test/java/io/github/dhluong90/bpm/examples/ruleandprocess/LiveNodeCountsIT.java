package io.github.dhluong90.bpm.examples.ruleandprocess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.host.spi.TenantResolver;
import io.github.dhluong90.bpm.process.api.DefinitionNotFoundException;
import io.github.dhluong90.bpm.process.api.ProcessQueryService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Live counts per step of the card-fee sample over HTTP (PRE-028): three charged-fee applications wait at the
 * officer review, a supervisor reads the counts, and another tenant sees none of them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "HORUS_DEMO_PASSWORD=it-pass")
@ActiveProfiles("dev")
@Testcontainers
class LiveNodeCountsIT {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String NODE_STATS = "/api/bpm/processes/v1/definitions/card-fee-review/versions/1/node-stats";

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(TestImages.postgres());

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** A {@code TENANT_<id>} authority names the tenant, otherwise the demo tenant {@code bank-a}. */
    @TestConfiguration(proxyBeanMethods = false)
    static class TenantFromAuthority {
        @Bean
        TenantResolver tenantFromAuthority() {
            return caller -> caller.authorities().stream().filter(a -> a.startsWith("TENANT_"))
                    .map(a -> a.substring(7)).findFirst().or(() -> Optional.of("bank-a"));
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    ProcessQueryService queries;

    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void PRE_028_counts_per_step_of_the_card_fee_process_over_http_and_never_for_another_tenant() throws Exception {
        for (String id : new String[] {"LC-1001", "LC-1002", "LC-1003"}) {
            HttpResponse<String> submitted = send("POST", "tuan", "/api/cards/applications",
                    "{\"applicationId\":\"" + id + "\",\"customerId\":\"C-002\"}");
            assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(201);
        }

        HttpResponse<String> response = send("GET", "an", NODE_STATS + "?mode=live", null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("definitionKey").stringValue()).isEqualTo("card-fee-review");
        assertThat(body.get("mode").stringValue()).isEqualTo("live");
        assertThat(body.get("asOf").stringValue()).isNotBlank();
        assertThat(body.at("/nodes/officer-review/WAITING_USER").longValue()).isEqualTo(3);
        assertThat(body.get("nodes").propertyNames()).containsExactly("officer-review");
        assertThat(response.headers().firstValue("ETag")).isPresent();

        assertThat(send("GET", "an", NODE_STATS + "?mode=window", null).statusCode()).isEqualTo(400);
        HttpResponse<String> unknown = send("GET", "an",
                "/api/bpm/processes/v1/definitions/card-fee-review/versions/9/node-stats", null);
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(JSON.readTree(unknown.body()).get("code").stringValue()).isEqualTo("DEFINITION_NOT_FOUND");

        long rowsBefore = jdbc.sql("SELECT (SELECT count(*) FROM pre_token) + (SELECT count(*) FROM pre_history) "
                + "+ (SELECT count(*) FROM pre_instance)").query(Long.class).single();
        TestingAuthenticationToken officerB = new TestingAuthenticationToken("officer-b", "n/a",
                java.util.List.of(new SimpleGrantedAuthority("ROLE_CARD_OFFICER"),
                        new SimpleGrantedAuthority("TENANT_bank-b")));
        officerB.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(officerB);
        assertThatThrownBy(() -> queries.liveNodeCounts("card-fee-review", 1))
                .as("bank-b has no card-fee-review: refused, and bank-a's counts are not revealed")
                .isInstanceOf(DefinitionNotFoundException.class);
        SecurityContextHolder.clearContext();
        assertThat(jdbc.sql("SELECT (SELECT count(*) FROM pre_token) + (SELECT count(*) FROM pre_history) "
                + "+ (SELECT count(*) FROM pre_instance)").query(Long.class).single()).as("nothing is written")
                .isEqualTo(rowsBefore);
    }

    private HttpResponse<String> send(String method, String user, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString((user + ":it-pass").getBytes(StandardCharsets.UTF_8)));
        if (body == null) {
            request.GET();
        } else {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
