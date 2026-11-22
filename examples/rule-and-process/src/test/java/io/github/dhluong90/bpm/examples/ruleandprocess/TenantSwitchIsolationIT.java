package io.github.dhluong90.bpm.examples.ruleandprocess;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.host.spi.TenantSwitchAudit;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tenant switching through the engines (#451, ADR-0014 decision 5): case {@code P1} and the {@code card-fee} rule set
 * exist only in {@code bank-a}. Lan signs in for {@code bank-a} with {@code allowed_tenants: [bank-a, bank-b]}; after
 * an accepted switch to {@code bank-b} each engine answers {@code 404} for them and writes nothing, and the host's
 * audit sink holds one record per switched call.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "HORUS_DEMO_PASSWORD=it-pass", "horus.bpm.tenancy.switching.enabled=true"})
@ActiveProfiles("dev")
@Testcontainers
class TenantSwitchIsolationIT {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String LAN_TOKEN = "lan-bank-a-token";
    static final List<TenantSwitchAudit.TenantSwitch> AUDIT = new CopyOnWriteArrayList<>();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** Lan's token, as the bank's identity provider would sign it, and the host's own audit sink. */
    @TestConfiguration(proxyBeanMethods = false)
    static class SwitchingHost {

        @Bean
        JwtDecoder lanTokens() {
            return token -> {
                if (!LAN_TOKEN.equals(token)) {
                    throw new BadJwtException("unknown token");
                }
                Instant now = Instant.now();
                return Jwt.withTokenValue(token).header("alg", "none").subject("lan")
                        .claim("tenant_id", "bank-a").claim("allowed_tenants", List.of("bank-a", "bank-b"))
                        .claim("permissions", List.of("rules:read", "processes:read"))
                        .issuedAt(now).expiresAt(now.plusSeconds(300)).build();
            };
        }

        @Bean
        TenantSwitchAudit hostAuditTrail() {
            return AUDIT::add;
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clearAudit() {
        AUDIT.clear();
    }

    @Test
    void PRE_038_after_a_switch_to_bank_b_the_bank_a_instance_is_404_and_nothing_is_written() throws Exception {
        // Given case P1, started by tuan in bank-a, which lan can read in her own tenant
        String p1 = submit("IT-SWITCH-P1");
        assertThat(lan("/api/bpm/processes/v1/instances/" + p1, null).statusCode()).isEqualTo(200);
        long writesBefore = bankBRows();

        // When lan switches to bank-b and reads P1
        HttpResponse<String> read = lan("/api/bpm/processes/v1/instances/" + p1, "bank-b");

        // Then 404, nothing is written for either tenant, and the host's sink holds one record of the switch
        assertThat(read.statusCode()).as(read.body()).isEqualTo(404);
        assertThat(JSON.readTree(read.body()).get("code").stringValue()).isEqualTo("INSTANCE_NOT_FOUND");
        assertThat(bankBRows()).isEqualTo(writesBefore);
        assertSwitchRecorded(read);
    }

    @Test
    void RLE_018_after_a_switch_to_bank_b_the_bank_a_rule_set_is_404_and_nothing_is_written() throws Exception {
        // Given the card-fee rule set, published in bank-a, which lan can read in her own tenant
        assertThat(lan("/api/bpm/rules/v1/rule-sets/card-fee", null).statusCode()).isEqualTo(200);
        long writesBefore = bankBRows();

        // When lan switches to bank-b and reads card-fee
        HttpResponse<String> read = lan("/api/bpm/rules/v1/rule-sets/card-fee", "bank-b");

        // Then 404, nothing is written, and the host's sink holds one record of the switch
        assertThat(read.statusCode()).as(read.body()).isEqualTo(404);
        assertThat(read.body()).doesNotContain("Card fee");
        assertThat(bankBRows()).isEqualTo(writesBefore);
        assertSwitchRecorded(read);
    }

    @Test
    void TR_TEN_01_a_refused_switch_writes_no_audit_record() throws Exception {
        // When lan asks for a tenant outside her allowed list, or repeats the header
        HttpResponse<String> unlisted = lan("/api/bpm/rules/v1/rule-sets/card-fee", "bank-c");
        HttpResponse<String> malformed = lan("/api/bpm/rules/v1/rule-sets/card-fee", "../bank-b");

        // Then 403 TENANT_NOT_ALLOWED, and no record says a switch was accepted
        assertThat(unlisted.statusCode()).isEqualTo(403);
        assertThat(malformed.statusCode()).isEqualTo(403);
        assertThat(AUDIT).isEmpty();
    }

    private void assertSwitchRecorded(HttpResponse<String> response) {
        assertThat(AUDIT).hasSize(1);
        TenantSwitchAudit.TenantSwitch record = AUDIT.get(0);
        assertThat(record.actor()).isEqualTo("lan");
        assertThat(record.fromTenant()).isEqualTo("bank-a");
        assertThat(record.toTenant()).isEqualTo("bank-b");
        assertThat(record.at()).isBefore(Instant.now().plusSeconds(1));
        assertThat(record.correlationId()).isNotBlank();
        response.headers().firstValue("X-Correlation-Id")
                .ifPresent(id -> assertThat(record.correlationId()).isEqualTo(id));
    }

    /** Every engine row of bank-b: a refused or empty read must leave it at zero. */
    private long bankBRows() {
        return jdbc.sql("SELECT (SELECT count(*) FROM pre_instance WHERE tenant_id = 'bank-b')"
                + " + (SELECT count(*) FROM pre_idempotency WHERE tenant_id = 'bank-b')"
                + " + (SELECT count(*) FROM rle_rule_set WHERE tenant_id = 'bank-b')").query(Long.class).single();
    }

    private String submit(String applicationId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/cards/applications"))
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString("tuan:it-pass".getBytes(StandardCharsets.UTF_8)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(
                        Map.of("applicationId", applicationId, "customerId", "C-002"))))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return JSON.readTree(response.body()).get("instanceId").stringValue();
    }

    private HttpResponse<String> lan(String path, String tenant) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + LAN_TOKEN).GET();
        if (tenant != null) {
            request.header("X-Tenant-Id", tenant);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
