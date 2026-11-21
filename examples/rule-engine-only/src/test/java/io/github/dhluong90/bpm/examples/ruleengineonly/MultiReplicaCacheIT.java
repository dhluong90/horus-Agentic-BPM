package io.github.dhluong90.bpm.examples.ruleengineonly;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.rules.autoconfigure.RuleEngineCacheCoherence;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/**
 * Two copies of the example host on one PostgreSQL database (RLE-010, TR-ASY-04, ADR-0009): copy A approves
 * {@code card-fee} v2 over HTTP while copy B evaluates every 100 ms. On a direct connection the notification brings B
 * to v2 within 1 s; through PgBouncer in transaction mode, with {@code cache.listen-notify=false}, the epoch check
 * does it within the 5 s interval and no session ever runs {@code LISTEN}.
 */
@Testcontainers
class MultiReplicaCacheIT {

    static final Network NETWORK = Network.newNetwork();
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    /** PgBouncer image of the pooler leg, pinned by digest in the root POM. */
    static final String PGBOUNCER_IMAGE = TestImages.pgbouncer();

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(TestImages.postgres())
                    .withNetwork(NETWORK).withNetworkAliases("pg");

    private static final AtomicInteger DATABASES = new AtomicInteger();

    @Test
    void RLE_010_multi_replica_cache_invalidates_on_publish() throws Exception {
        // Given two copies on a direct connection with notifications on, both serving card-fee v1
        String database = freshDatabase();
        String url = directUrl(database);
        try (ConfigurableApplicationContext a = start(url, "--horus.bpm.rules.cache.listen-notify=true");
                ConfigurableApplicationContext b = start(url, "--horus.bpm.rules.cache.listen-notify=true")) {
            awaitListening(b);
            publish(a, cardFee(50000), 1);
            assertThat(evaluatedVersion(b)).isEqualTo(1);

            // When copy A approves v2
            publish(a, cardFee(60000), 2);
            long committed = System.nanoTime();

            // Then copy B's evaluation returns version 2 within 1 s of the commit
            long served = awaitVersion(b, 2, Duration.ofSeconds(10));
            assertThat(Duration.ofNanos(served - committed)).isLessThan(Duration.ofSeconds(1));
            assertThat(JdbcClient.create(dataSource(a)).sql(
                    "SELECT count(*) FROM rle_decision_outbox WHERE payload->>'eventType' = 'rules.version.published.v1'")
                    .query(Long.class).single()).isEqualTo(2L);
        }
    }

    @Test
    @org.junit.jupiter.api.Tag("pooler")
    void TR_ASY_04_copies_behind_a_transaction_pooler_refresh_within_the_epoch_interval() throws Exception {
        // Given the schema migrated on a direct connection, then two copies through PgBouncer in transaction mode
        String database = freshDatabase();
        start(directUrl(database)).close();
        try (GenericContainer<?> pgbouncer = pgbouncer(database)) {
            pgbouncer.start();
            String pooled = "jdbc:postgresql://" + pgbouncer.getHost() + ":" + pgbouncer.getMappedPort(5432) + "/"
                    + database + "?prepareThreshold=0";
            String[] behindThePooler = {
                "--horus.bpm.rules.cache.listen-notify=false",
                "--horus.bpm.rules.cache.epoch-check-interval=PT5S",
                "--horus.bpm.rules.migrate-on-start=false",
                "--spring.flyway.enabled=false"};
            try (ConfigurableApplicationContext a = start(pooled, behindThePooler);
                    ConfigurableApplicationContext b = start(pooled, behindThePooler)) {
                publish(a, cardFee(50000), 1);
                assertThat(evaluatedVersion(b)).isEqualTo(1);

                // When copy A approves v2
                publish(a, cardFee(60000), 2);
                long committed = System.nanoTime();

                // Then copy B serves v2 within the 5 s interval (plus one 100 ms evaluation step), never later
                long served = awaitVersion(b, 2, Duration.ofSeconds(15));
                assertThat(Duration.ofNanos(served - committed)).isLessThanOrEqualTo(Duration.ofMillis(5_100));

                // And no session behind the pooler ever listened
                assertThat(a.getBean(RuleEngineCacheCoherence.class).listening()).isFalse();
                assertThat(b.getBean(RuleEngineCacheCoherence.class).listening()).isFalse();
                assertThat(JdbcClient.create(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()))
                        .sql("SELECT count(*) FROM pg_stat_activity WHERE query LIKE 'LISTEN %'")
                        .query(Long.class).single()).isZero();
            }
        }
    }

    private static GenericContainer<?> pgbouncer(String database) {
        return new GenericContainer<>(DockerImageName.parse(PGBOUNCER_IMAGE))
                .withNetwork(NETWORK)
                .withEnv("DB_HOST", "pg")
                .withEnv("DB_PORT", "5432")
                .withEnv("DB_NAME", database)
                .withEnv("DB_USER", POSTGRES.getUsername())
                .withEnv("DB_PASSWORD", POSTGRES.getPassword())
                .withEnv("AUTH_TYPE", "scram-sha-256")
                .withEnv("POOL_MODE", "transaction")
                .withEnv("LISTEN_PORT", "5432")
                .withExposedPorts(5432)
                .waitingFor(Wait.forListeningPort());
    }

    private static ConfigurableApplicationContext start(String url, String... extra) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.datasource.url=" + url,
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--server.port=0"));
        args.addAll(TestTokens.arguments());
        args.addAll(List.of(extra));
        return new SpringApplicationBuilder(RuleEngineOnlyApplication.class).run(args.toArray(String[]::new));
    }

    private static void publish(ConfigurableApplicationContext copy, byte[] yaml, int version) throws Exception {
        String path = version == 1 ? "/rule-sets" : "/rule-sets/card-fee/versions";
        assertThat(send(copy, "alice", path, yaml, "application/yaml").statusCode()).isEqualTo(201);
        HttpResponse<String> requested = send(copy, "alice",
                "/rule-sets/card-fee/versions/" + version + ":request-publish", null, null);
        assertThat(requested.statusCode()).isEqualTo(200);
        String etag = requested.headers().firstValue("ETag").orElseThrow();
        assertThat(send(copy, "bob", "/rule-sets/card-fee/versions/" + version + ":approve", null, null, etag)
                .statusCode()).isEqualTo(200);
    }

    private static int evaluatedVersion(ConfigurableApplicationContext copy) throws Exception {
        HttpResponse<String> response = send(copy, "teller", "/decisions/card-fee:evaluate",
                "{\"inputs\":{\"tier\":\"SILVER\",\"txCount\":1}}".getBytes(StandardCharsets.UTF_8), "application/json");
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body()).path("version").asInt();
    }

    /** Evaluates every 100 ms until the copy serves the version; returns System.nanoTime() of that request. */
    private static long awaitVersion(ConfigurableApplicationContext copy, int version, Duration timeout)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            long before = System.nanoTime();
            if (evaluatedVersion(copy) == version) {
                return before;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("copy B still served an older version after " + timeout);
    }

    private static void awaitListening(ConfigurableApplicationContext copy) throws InterruptedException {
        RuleEngineCacheCoherence coherence = copy.getBean(RuleEngineCacheCoherence.class);
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!coherence.listening()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the cache listener did not start");
            }
            Thread.sleep(20);
        }
    }

    private static HttpResponse<String> send(ConfigurableApplicationContext copy, String user, String path,
            byte[] body, String contentType) throws Exception {
        return send(copy, user, path, body, contentType, null);
    }

    private static HttpResponse<String> send(ConfigurableApplicationContext copy, String user, String path,
            byte[] body, String contentType, String ifMatch) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:"
                        + copy.getEnvironment().getProperty("local.server.port") + "/api/bpm/rules/v1" + path))
                .header("Authorization", TestTokens.bearer(user))
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static javax.sql.DataSource dataSource(ConfigurableApplicationContext copy) {
        return copy.getBean(javax.sql.DataSource.class);
    }

    private static byte[] cardFee(long defaultFee) {
        try (InputStream in = MultiReplicaCacheIT.class.getResourceAsStream("/rules/card-fee.yaml")) {
            String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return yaml.replace("\"50000 VND\"", "\"" + defaultFee + " VND\"").getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String freshDatabase() {
        String name = "cache_copies_" + DATABASES.incrementAndGet();
        try (var connection = java.sql.DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("cannot create test database " + name, e);
        }
        return name;
    }

    private static String directUrl(String database) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/" + database;
    }
}
