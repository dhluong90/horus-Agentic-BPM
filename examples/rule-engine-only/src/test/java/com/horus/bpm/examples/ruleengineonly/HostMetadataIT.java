package com.horus.bpm.examples.ruleengineonly;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The example host with bearer tokens over real HTTP, through the host's own security chain: Lan is a
 * pricing analyst and Minh a pricing approver at bank-a; their token roles reach engine permissions
 * through the role mapping in {@code application.yml}. The identity provider is replaced by a decoder
 * that knows two fixed tokens.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class HostMetadataIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TestConfiguration(proxyBeanMethods = false)
    static class TwoKnownTokens {
        @Bean
        JwtDecoder jwtDecoder() {
            Map<String, List<String>> rolesByToken = Map.of(
                    "lan-token", List.of("PRICING_ANALYST"),
                    "minh-token", List.of("PRICING_APPROVER"));
            return token -> {
                List<String> roles = rolesByToken.get(token);
                if (roles == null) {
                    throw new BadJwtException("unknown test token");
                }
                return Jwt.withTokenValue(token).header("alg", "none")
                        .subject(token.substring(0, token.indexOf('-')))
                        .claim("tenant_id", "bank-a").claim("roles", roles)
                        .issuedAt(Instant.parse("2027-01-26T00:00:00Z")).expiresAt(Instant.parse("2099-01-01T00:00:00Z"))
                        .build();
            };
        }
    }

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private Environment environment;

    @Test
    void RLE_019_example_host_answers_meta_and_me_for_two_bearer_tokens() throws Exception {
        // When Lan and Minh call the host with their tokens
        HttpResponse<String> meta = get("/api/bpm/meta", "Bearer lan-token");
        HttpResponse<String> lan = get("/api/bpm/me", "Bearer lan-token");
        HttpResponse<String> minh = get("/api/bpm/me", "Bearer minh-token");

        // Then meta names this host and the rules engine of this build, privately cacheable
        assertThat(meta.statusCode()).isEqualTo(200);
        JsonNode metaBody = JSON.readTree(meta.body());
        assertThat(metaBody.path("hostId").asString()).isEqualTo("rule-engine-only");
        assertThat(metaBody.path("modules")).hasSize(1);
        assertThat(metaBody.path("modules").get(0).path("module").asString()).isEqualTo("rules");
        assertThat(metaBody.path("modules").get(0).path("engineVersion").asString())
                .isEqualTo(System.getProperty("rule-engine.version"));
        assertThat(meta.headers().firstValue("Cache-Control")).hasValue("private, max-age=60");
        assertThat(meta.headers().firstValue("ETag")).isPresent();

        // And the two callers get different permission lists from their roles
        assertThat(permissions(lan)).containsExactly("rules:read", "rules:author");
        assertThat(permissions(minh)).containsExactly("rules:read", "rules:approve");
        assertThat(JSON.readTree(lan.body()).path("tenant").path("current").asString()).isEqualTo("bank-a");
        assertThat(lan.headers().firstValue("Cache-Control")).hasValue("private, max-age=60");
    }

    @Test
    void RLE_019_example_host_answers_401_problem_without_a_valid_token() throws Exception {
        for (String authorization : new String[] {null, "Bearer stolen-token"}) {
            HttpResponse<String> response = get("/api/bpm/meta", authorization);

            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                    type -> assertThat(type).startsWith("application/problem+json"));
            JsonNode problem = JSON.readTree(response.body());
            assertThat(problem.path("code").asString()).isEqualTo("UNAUTHENTICATED");
            assertThat(problem.path("correlationId").asString()).isNotBlank();
            assertThat(response.body()).doesNotContain("Exception", "\tat ");
        }
    }

    private HttpResponse<String> get(String path, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + environment.getProperty("local.server.port") + path)).GET();
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static List<String> permissions(HttpResponse<String> me) {
        assertThat(me.statusCode()).isEqualTo(200);
        return JSON.readTree(me.body()).path("permissions").valueStream().map(JsonNode::asString).toList();
    }
}
