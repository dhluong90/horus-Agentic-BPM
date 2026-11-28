package io.github.dhluong90.bpm.examples.ruleandprocess;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Spans of the engines in a host's trace (#114, TR-OBS-03, PRE-394, RLE-228, TR-SEC-05): a card application started
 * over HTTP produces process step spans and rule evaluation spans with bounded attributes, and no span carries the
 * application id, the customer id or any other value of the request. Export is switched on here only so the in-memory
 * exporter sees the spans; the host's default is off.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "HORUS_DEMO_PASSWORD=it-pass", "management.tracing.export.enabled=true",
        "management.tracing.sampling.probability=1.0", "management.opentelemetry.tracing.export.schedule-delay=100ms"})
@ActiveProfiles("dev")
@Testcontainers
class TracingSpansIT {

    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String APPLICATION_ID = "TRACE-APP-7731";
    static final String CUSTOMER_ID = "C-002";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** An in-memory exporter beside the host's OpenTelemetry tracer, so the test reads every finished span. */
    @TestConfiguration(proxyBeanMethods = false)
    static class InMemoryExporter {
        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    InMemorySpanExporter exporter;

    @Test
    void PRE_394_RLE_228_a_started_application_produces_step_and_evaluation_spans_with_ids_and_codes_only() throws Exception {
        // When a teller starts a card application, which prices the card and opens the review process
        HttpResponse<String> submitted = send("tuan", "{\"applicationId\":\"" + APPLICATION_ID + "\",\"customerId\":\""
                + CUSTOMER_ID + "\"}");
        assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(201);

        // Then one process step span names the definition, its version and the step kind
        List<SpanData> spans = awaitSpans();
        List<SpanData> steps = spans.stream().filter(s -> "horus.process.step".equals(s.getName())).toList();
        assertThat(steps).isNotEmpty();
        assertThat(steps).anySatisfy(step -> {
            assertThat(tags(step)).containsEntry("horus.step_kind", "START").containsEntry("horus.outcome", "OK");
            assertThat(tags(step)).containsKeys("horus.definition_key", "horus.definition_version");
        });
        // and one rule evaluation span names the rule set, its version, the decision id and the outcome
        List<SpanData> evaluations = spans.stream().filter(s -> "horus.rule.evaluate".equals(s.getName())).toList();
        assertThat(evaluations).isNotEmpty();
        assertThat(evaluations).allSatisfy(span -> assertThat(tags(span))
                .containsKeys("horus.rule_set", "horus.rule_set.version", "horus.decision_id", "horus.outcome"));

        // and every engine span sits in the trace of the HTTP request that caused it
        assertThat(spans.stream().filter(s -> s.getName().startsWith("horus.")).map(SpanData::getTraceId).distinct())
                .hasSize(1);

        // and no span carries a business value of the request, nor an attribute outside the bounded set
        Set<String> allowed = Set.of("horus.tenant_id", "horus.definition_key", "horus.definition_version",
                "horus.step_kind", "horus.outcome", "horus.error_code", "horus.error_type", "horus.rule_set",
                "horus.rule_set.version", "horus.decision_id", "horus.matched_count");
        for (SpanData span : spans.stream().filter(s -> s.getName().startsWith("horus.")).toList()) {
            assertThat(tags(span).keySet()).as(span.getName()).isSubsetOf(allowed);
            assertThat(tags(span).values()).as(span.getName())
                    .noneMatch(v -> v.contains(APPLICATION_ID) || v.contains(CUSTOMER_ID));
            assertThat(span.getName()).doesNotContain(APPLICATION_ID);
        }
    }

    private List<SpanData> awaitSpans() throws InterruptedException {
        for (int i = 0; i < 100 && exporter.getFinishedSpanItems().stream()
                .noneMatch(s -> "horus.rule.evaluate".equals(s.getName())); i++) {
            Thread.sleep(100);
        }
        return List.copyOf(exporter.getFinishedSpanItems());
    }

    private static Map<String, String> tags(SpanData span) {
        Map<String, String> tags = new TreeMap<>();
        span.getAttributes().forEach((AttributeKey<?> key, Object value) -> tags.put(key.getKey(), String.valueOf(value)));
        return tags;
    }

    private HttpResponse<String> send(String user, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/cards/applications"))
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString((user + ":it-pass").getBytes(StandardCharsets.UTF_8)))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
