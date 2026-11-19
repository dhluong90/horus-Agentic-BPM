package com.horus.bpm.examples.ruleengineonly;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The README's curl demo on the {@code dev} profile: two demo users over HTTP basic, tenant from the header. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Testcontainers
class DevUsersIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private Environment environment;

    @Test
    void RLE_019_dev_users_get_different_permission_lists_and_no_credentials_get_401() throws Exception {
        JsonNode lan = JSON.readTree(me("lan:lan").body());
        JsonNode minh = JSON.readTree(me("minh:minh").body());
        HttpResponse<String> anonymous = me(null);
        HttpResponse<String> wrongPassword = me("lan:guess");

        assertThat(strings(lan.path("permissions"))).containsExactly("rules:read", "rules:author");
        assertThat(strings(lan.path("roles"))).containsExactly("ROLE_PRICING_ANALYST");
        assertThat(strings(minh.path("permissions"))).containsExactly("rules:read", "rules:approve");
        assertThat(minh.path("tenant").path("current").asString()).isEqualTo("bank-a");
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.headers().firstValue("WWW-Authenticate")).hasValue("Basic realm=\"rule-engine-only\"");
        assertThat(JSON.readTree(anonymous.body()).path("code").asString()).isEqualTo("UNAUTHENTICATED");
        assertThat(wrongPassword.statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> me(String credentials) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + environment.getProperty("local.server.port") + "/api/bpm/me"))
                .header("X-Tenant-Id", "bank-a").GET();
        if (credentials != null) {
            request.header("Authorization",
                    "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static List<String> strings(JsonNode array) {
        return array.valueStream().map(JsonNode::asString).toList();
    }
}
