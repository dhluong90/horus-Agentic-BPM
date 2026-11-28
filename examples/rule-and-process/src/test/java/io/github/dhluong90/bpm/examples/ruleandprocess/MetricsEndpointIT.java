package io.github.dhluong90.bpm.examples.ruleandprocess;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The metrics endpoint of the operations pack (#659, PRE-084, TR-OBS-02): with the endpoint exposed on the management
 * port, as deploy/observability does, Prometheus reads every meter the shipped alert rules and dashboards use. The
 * names here are the contract between the engines and deploy/observability; the rules and dashboards query no other.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "HORUS_DEMO_PASSWORD=it-pass",
        "management.endpoints.web.exposure.include=health,prometheus",
        "management.server.port=0",
        "horus.bpm.processes.metrics.gauge-refresh=PT1S"})
@ActiveProfiles("dev")
@Testcontainers
class MetricsEndpointIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Value("${local.management.port}")
    int managementPort;

    @org.springframework.boot.test.web.server.LocalServerPort
    int port;

    @Test
    void PRE_084_a_refused_message_is_counted_by_reason_and_the_scrape_shows_it() throws Exception {
        // Given an operations officer who delivers a message that no instance waits for
        String basic = java.util.Base64.getEncoder().encodeToString("hung:it-pass".getBytes());
        HttpResponse<String> refused = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/bpm/processes/v1/messages:correlate"))
                        .header("Authorization", "Basic " + basic).header("Content-Type", "application/json")
                        .header("Idempotency-Key", "metrics-it-1")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"messageName\":\"nobody-waits\",\"correlationKey\":\"k-1\"}"))
                        .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);

        // When Prometheus scrapes
        HttpResponse<String> scrape = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/actuator/prometheus")).build(),
                HttpResponse.BodyHandlers.ofString());

        // Then the counter is there, tagged with the reason only
        assertThat(scrape.body()).contains("horus_pre_messages_refused_total{reason=\"NOT_CORRELATED\"");
        assertThat(scrape.body()).doesNotContain("nobody-waits").doesNotContain("k-1");
    }

    @Test
    void TR_OBS_02_prometheus_endpoint_stays_behind_the_sign_in_on_the_application_port() throws Exception {
        HttpResponse<String> scrape = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/prometheus")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(scrape.statusCode()).isEqualTo(401);
    }

    @Test
    void TR_OBS_02_prometheus_endpoint_serves_the_meters_of_both_engines_the_pool_and_the_jvm_without_a_login() throws Exception {
        // Given one request served, so the HTTP timer exists
        HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString());

        // When Prometheus scrapes the management port, with no credentials
        HttpResponse<String> scrape = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/actuator/prometheus")).build(),
                HttpResponse.BodyHandlers.ofString());

        // Then it answers in the Prometheus text format
        assertThat(scrape.statusCode()).isEqualTo(200);
        String body = scrape.body();
        // and carries the meters the alert rules and dashboards query: Process Engine gauges, Rule Engine meters,
        // the connection pool and the JVM
        assertThat(body).contains("horus_pre_instances_active", "horus_pre_incidents_open",
                "horus_pre_jobs_due_oldest_age_seconds{kind=\"TIMER\"", "horus_pre_jobs_due_oldest_age_seconds{kind=\"ASYNC_SERVICE\"",
                "horus_rle_evaluations_seconds_count{outcome=\"matched\"", "horus_rle_cache_size",
                "horus_rle_partition_default_rows", "hikaricp_connections_active", "hikaricp_connections_max",
                "hikaricp_connections_pending", "jvm_memory_used_bytes", "http_server_requests_seconds");
        // and no meter carries a tenant
        assertThat(body).doesNotContain("tenant=");
    }
}
