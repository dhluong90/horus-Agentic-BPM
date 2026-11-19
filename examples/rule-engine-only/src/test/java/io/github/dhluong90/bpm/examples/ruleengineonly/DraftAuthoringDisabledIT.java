package io.github.dhluong90.bpm.examples.ruleengineonly;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * RLE-047 on a host that keeps the 0.1.0 default {@code authoring.cockpit-drafts: false} (PD-094): the engine itself
 * refuses to replace a draft, while import of a document keeps working.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class DraftAuthoringDisabledIT {

    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final List<String> AUTHOR = List.of("rules:read", "rules:author");

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(System.getProperty("bpm.test.postgres.image", "postgres:16-alpine"));

    @org.springframework.test.context.DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        TestTokens.register(registry);
    }

    @Autowired
    Environment environment;

    @Test
    void RLE_047_replace_is_refused_when_the_host_turned_draft_authoring_off() throws Exception {
        byte[] cardFee = DraftReplaceAndDiffIT.cardFee("10000 VND");
        HttpResponse<String> imported = send("POST", "/rule-sets", cardFee, null, null);
        assertThat(imported.statusCode()).as(imported.body()).isEqualTo(201);

        HttpResponse<String> put = send("PUT", "/rule-sets/card-fee/versions/1", DraftReplaceAndDiffIT.cardFee("0 VND"),
                "k-601", "\"0\"");

        assertThat(put.statusCode()).isEqualTo(403);
        assertThat(put.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(JsonMapper.builder().build().readTree(put.body()).path("code").asString())
                .isEqualTo("DRAFT_AUTHORING_DISABLED");
        assertThat(send("GET", "/rule-sets/card-fee/versions/1", null, null, null).headers().firstValue("ETag"))
                .contains("\"0\"");
    }

    HttpResponse<String> send(String method, String path, byte[] body, String key, String ifMatch) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:"
                        + environment.getProperty("local.server.port") + "/api/bpm/rules/v1" + path))
                .header("Authorization", TestTokens.bearerFor("lan", "bank-off", AUTHOR))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (body != null) {
            request.header("Content-Type", "application/yaml");
        }
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
