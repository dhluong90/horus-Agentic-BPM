package io.github.dhluong90.bpm.examples.ruleandprocess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Export is off by default (#114, TR-OBS-03, TR-SEC-05): with no tracing property set, the host builds no span exporter
 * bean, and a host that took traffic opened no connection to the default OTLP
 * ports. Only the database and the demo password are set, as in any run of the example.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "HORUS_DEMO_PASSWORD=it-pass")
@ActiveProfiles("dev")
@Testcontainers
class TracingExportOffIT {

    static final HttpClient HTTP = HttpClient.newHttpClient();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    int port;

    @Autowired
    ApplicationContext context;

    @Test
    void PRE_520_with_no_tracing_property_nothing_is_exported_and_no_exporter_connects_anywhere() throws Exception {
        // Given the default OTLP ports (gRPC 4317, HTTP 4318) are watched for any connection
        AtomicInteger connections = new AtomicInteger();
        try (ServerSocket grpc = listen(4317); ServerSocket http = listen(4318)) {
            assumeTrue(grpc != null && http != null, "a default OTLP port is in use on this machine");
            watch(grpc, connections);
            watch(http, connections);

            // When the host takes traffic that makes spans of the engines
            for (int i = 0; i < 20; i++) {
                HttpResponse<String> response = submit("OFF-APP-" + i);
                assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
            }
            Thread.sleep(1500);

            // Then the context holds no span exporter
            assertThat(context.getBeansOfType(SpanExporter.class)).isEmpty();

            // and nothing connected to a default collector port
            assertThat(connections.get()).isZero();
        }
    }

    private static ServerSocket listen(int port) {
        try {
            return new ServerSocket(port, 5, InetAddress.getLoopbackAddress());
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private static void watch(ServerSocket socket, AtomicInteger connections) {
        Thread t = new Thread(() -> {
            try {
                while (true) {
                    socket.accept().close();
                    connections.incrementAndGet();
                }
            } catch (java.io.IOException closed) {
                // the test closed the socket
            }
        }, "otlp-watch-" + socket.getLocalPort());
        t.setDaemon(true);
        t.start();
    }

    private HttpResponse<String> submit(String applicationId) throws Exception {
        String body = "{\"applicationId\":\"" + applicationId + "\",\"customerId\":\"C-002\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/cards/applications"))
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString("tuan:it-pass".getBytes(StandardCharsets.UTF_8)))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
