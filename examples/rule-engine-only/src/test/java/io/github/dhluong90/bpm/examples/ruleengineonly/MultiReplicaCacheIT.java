package io.github.dhluong90.bpm.examples.ruleengineonly;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.rules.autoconfigure.RuleEngineCacheCoherence;
import io.github.dhluong90.bpm.rules.autoconfigure.RuleEngineLifecycleSweeper;
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
import org.testcontainers.images.builder.Transferable;
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
                    .withNetwork(NETWORK).withNetworkAliases("pg")
                    // Every statement is logged with its database name in front, so a test can read what the server
                    // was asked to run (the pooler legs check that no LISTEN and no advisory lock was asked for).
                    .withCommand("postgres", "-c", "fsync=off", "-c", "log_statement=all", "-c", "log_line_prefix=%d ");

    /**
     * Where the copies listen and where the test calls them. A copy on the wildcard address and an unrelated
     * program on 127.0.0.1 can hold the same port number; a call to localhost then reaches that program and the
     * test sees its 400 or 401. Bound to the loopback address, the operating system hands out a port that is free there.
     */
    private static final String LOOPBACK = "127.0.0.1";

    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static final AtomicInteger MARKERS = new AtomicInteger();
    private static final java.util.function.Predicate<String> LISTEN =
            java.util.regex.Pattern.compile("(?i)\\bLISTEN\\s").asPredicate();
    private static final java.util.function.Predicate<String> ADVISORY_LOCK =
            java.util.regex.Pattern.compile("(?i)pg_(try_)?advisory_(xact_)?lock").asPredicate();

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

    @Test
    @org.junit.jupiter.api.Tag("pooler")
    void RLE_027_copies_behind_a_transaction_pooler_retire_a_superseded_version_once() throws Exception {
        // Given two copies through PgBouncer in transaction mode, each sweeping every second, and card-fee v1 in force
        String database = freshDatabase();
        start(directUrl(database)).close();
        // The first copy migrated the schema on a direct connection and did listen; the run behind the pooler starts here
        String runStart = logMarker(directClient(database), "pooler-run-start");
        try (GenericContainer<?> pgbouncer = pgbouncer(database)) {
            pgbouncer.start();
            String pooled = "jdbc:postgresql://" + pgbouncer.getHost() + ":" + pgbouncer.getMappedPort(5432) + "/"
                    + database + "?prepareThreshold=0";
            String[] behindThePooler = {
                "--horus.bpm.rules.cache.listen-notify=false",
                "--horus.bpm.rules.lifecycle.sweep-interval=PT1S",
                "--horus.bpm.rules.migrate-on-start=false",
                "--spring.flyway.enabled=false"};
            try (ConfigurableApplicationContext a = start(pooled, behindThePooler);
                    ConfigurableApplicationContext b = start(pooled, behindThePooler)) {
                publish(a, cardFee(50000), 1);

                // When v2 is approved with an effective date a few seconds ahead, and the date passes
                String effective = java.time.Instant.now().plusSeconds(4).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                        .toString();
                String v2 = new String(cardFee(60000), StandardCharsets.UTF_8)
                        .replace("schemaVersion: 1\n", "schemaVersion: 1\neffectiveFrom: \"" + effective + "\"\n");
                assertThat(v2).contains("effectiveFrom");
                publish(a, v2.getBytes(StandardCharsets.UTF_8), 2);
                JdbcClient direct = JdbcClient.create(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        directUrl(database), POSTGRES.getUsername(), POSTGRES.getPassword()));
                long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
                while (!"RETIRED".equals(direct.sql("SELECT status FROM rle_version WHERE version_num = 1")
                        .query(String.class).single())) {
                    assertThat(System.nanoTime()).as("v1 was not retired by the sweep").isLessThan(deadline);
                    Thread.sleep(200);
                }
                // and both copies keep sweeping for a few more rounds
                Thread.sleep(4_000);

                // Then both copies serve v2, v1 has exactly one retired event and one RETIRED history row
                assertThat(evaluatedVersion(a)).isEqualTo(2);
                assertThat(evaluatedVersion(b)).isEqualTo(2);
                assertThat(direct.sql("SELECT count(*) FROM rle_decision_outbox WHERE payload->>'eventType' ="
                        + " 'rules.version.retired.v1'").query(Long.class).single()).isEqualTo(1L);
                assertThat(direct.sql("""
                        SELECT count(*) FROM rle_version_history h JOIN rle_version v ON v.id = h.version_id
                        WHERE v.version_num = 1 AND h.action = 'RETIRED'""").query(Long.class).single()).isEqualTo(1L);
                assertThat(direct.sql("SELECT payload->>'actor' || '/' || (payload->>'reason') FROM rle_decision_outbox"
                        + " WHERE payload->>'eventType' = 'rules.version.retired.v1'").query(String.class).single())
                        .isEqualTo("system:supersession/SUPERSEDED");

                // And the server was never asked to LISTEN: no session-mode connection was needed
                String runEnd = logMarker(direct, "pooler-run-end");
                assertThat(statementsOf(database, runStart, runEnd)).isNotEmpty().noneMatch(LISTEN);
            }
        }
    }

    @Test
    @org.junit.jupiter.api.Tag("pooler")
    void RLE_027_two_sweeps_started_at_the_same_moment_behind_a_transaction_pooler_retire_each_version_once()
            throws Exception {
        // Given two copies through PgBouncer in transaction mode whose own sweep never fires within the test, and
        // eight rule sets, each with v1 published and a v2 that takes effect a few seconds ahead
        String database = freshDatabase();
        start(directUrl(database)).close();
        int ruleSets = 8;
        try (GenericContainer<?> pgbouncer = pgbouncer(database)) {
            pgbouncer.start();
            String pooled = "jdbc:postgresql://" + pgbouncer.getHost() + ":" + pgbouncer.getMappedPort(5432) + "/"
                    + database + "?prepareThreshold=0";
            String[] behindThePooler = {
                "--horus.bpm.rules.cache.listen-notify=false",
                "--horus.bpm.rules.lifecycle.sweep-interval=PT1H",
                "--horus.bpm.rules.migrate-on-start=false",
                "--spring.flyway.enabled=false"};
            try (ConfigurableApplicationContext a = start(pooled, behindThePooler);
                    ConfigurableApplicationContext b = start(pooled, behindThePooler)) {
                JdbcClient direct = directClient(database);
                String effective = java.time.Instant.now().plusSeconds(6).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                        .toString();
                for (int n = 0; n < ruleSets; n++) {
                    String name = "card-fee-" + n;
                    publish(a, name, cardFee(name, 50000, null), 1);
                    publish(a, name, cardFee(name, 60000, effective), 2);
                }
                for (int n = 0; n < ruleSets; n++) {
                    awaitVersion(b, "card-fee-" + n, 2, Duration.ofSeconds(30));
                }
                // The date has passed, selection already serves v2, and no sweep has run: every v1 is still PUBLISHED
                assertThat(direct.sql("SELECT count(*) FROM rle_version WHERE version_num = 1 AND status = 'PUBLISHED'")
                        .query(Long.class).single()).isEqualTo((long) ruleSets);
                String windowStart = logMarker(direct, "sweep-window-start");

                // When both copies sweep at the same moment
                java.util.concurrent.CyclicBarrier together = new java.util.concurrent.CyclicBarrier(2);
                java.util.concurrent.ExecutorService threads = java.util.concurrent.Executors.newFixedThreadPool(2);
                int retiredByA;
                int retiredByB;
                try {
                    java.util.concurrent.Future<Integer> sweepA = threads.submit(() -> {
                        together.await();
                        return a.getBean(RuleEngineLifecycleSweeper.class).runOnce();
                    });
                    java.util.concurrent.Future<Integer> sweepB = threads.submit(() -> {
                        together.await();
                        return b.getBean(RuleEngineLifecycleSweeper.class).runOnce();
                    });
                    retiredByA = sweepA.get(30, java.util.concurrent.TimeUnit.SECONDS);
                    retiredByB = sweepB.get(30, java.util.concurrent.TimeUnit.SECONDS);
                } finally {
                    threads.shutdownNow();
                }
                String windowEnd = logMarker(direct, "sweep-window-end");

                // Then each of the eight versions was retired by exactly one of them, never by both
                assertThat(retiredByA + retiredByB).isEqualTo(ruleSets);
                assertThat(direct.sql("SELECT count(*) FROM rle_version WHERE version_num = 1 AND status = 'RETIRED'")
                        .query(Long.class).single()).isEqualTo((long) ruleSets);
                assertThat(direct.sql("SELECT count(*) FROM rle_decision_outbox WHERE payload->>'eventType' ="
                        + " 'rules.version.retired.v1'").query(Long.class).single()).isEqualTo((long) ruleSets);
                assertThat(direct.sql("""
                        SELECT count(*) FROM (SELECT h.version_id FROM rle_version_history h
                          WHERE h.action = 'RETIRED' GROUP BY h.version_id HAVING count(*) = 1) once""")
                        .query(Long.class).single()).isEqualTo((long) ruleSets);
                for (int n = 0; n < ruleSets; n++) {
                    assertThat(evaluatedVersion(a, "card-fee-" + n)).isEqualTo(2);
                    assertThat(evaluatedVersion(b, "card-fee-" + n)).isEqualTo(2);
                }

                // And the server saw both sweeps run their statement, with no LISTEN and no advisory lock among
                // the statements of the window
                List<String> window = statementsOf(database, windowStart, windowEnd);
                assertThat(window.stream().filter(line -> line.contains("UPDATE rle_version old")).count())
                        .as("the sweep statement ran once per copy").isGreaterThanOrEqualTo(2);
                assertThat(window).noneMatch(LISTEN).noneMatch(ADVISORY_LOCK);
            }
        }
    }

    /**
     * Runs a statement that carries a unique text and waits until the server log shows it, so the log up to the
     * returned marker is complete.
     */
    private static String logMarker(JdbcClient direct, String label) throws InterruptedException {
        String marker = label + "-" + MARKERS.incrementAndGet();
        direct.sql("SELECT '" + marker + "'").query(String.class).single();
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!POSTGRES.getLogs().contains(marker)) {
            assertThat(System.nanoTime()).as("the server log never showed " + marker).isLessThan(deadline);
            Thread.sleep(100);
        }
        return marker;
    }

    /** The log lines of one database between two markers; a null first marker starts at the beginning. */
    private static List<String> statementsOf(String database, String from, String to) {
        String logs = POSTGRES.getLogs();
        int start = from == null ? 0 : logs.indexOf(from);
        int end = logs.indexOf(to, Math.max(start, 0));
        return logs.substring(Math.max(start, 0), end < 0 ? logs.length() : end).lines()
                .filter(line -> line.startsWith(database + " ")).toList();
    }

    private static JdbcClient directClient(String database) {
        return JdbcClient.create(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                directUrl(database), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private static GenericContainer<?> pgbouncer(String database) {
        return new GenericContainer<>(DockerImageName.parse(PGBOUNCER_IMAGE))
                .withNetwork(NETWORK)
                .withCopyToContainer(Transferable.of(
                        TestImages.pgbouncerIni(database, POSTGRES.getUsername(), POSTGRES.getPassword()), 0644),
                        "/tmp/pgbouncer.ini")
                .withCopyToContainer(Transferable.of(
                        TestImages.pgbouncerUserlist(POSTGRES.getUsername(), POSTGRES.getPassword()), 0644),
                        "/tmp/userlist.txt")
                .withCommand("/tmp/pgbouncer.ini")
                .withExposedPorts(5432)
                .waitingFor(Wait.forListeningPort());
    }

    private static ConfigurableApplicationContext start(String url, String... extra) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.datasource.url=" + url,
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--server.address=" + LOOPBACK, "--server.port=0"));
        args.addAll(TestTokens.arguments());
        args.addAll(List.of(extra));
        return new SpringApplicationBuilder(RuleEngineOnlyApplication.class).run(args.toArray(String[]::new));
    }

    private static void publish(ConfigurableApplicationContext copy, byte[] yaml, int version) throws Exception {
        publish(copy, "card-fee", yaml, version);
    }

    private static void publish(ConfigurableApplicationContext copy, String name, byte[] yaml, int version)
            throws Exception {
        String path = version == 1 ? "/rule-sets" : "/rule-sets/" + name + "/versions";
        expectStatus(send(copy, "alice", path, yaml, "application/yaml"), 201);
        HttpResponse<String> requested = send(copy, "alice",
                "/rule-sets/" + name + "/versions/" + version + ":request-publish", null, null);
        expectStatus(requested, 200);
        String etag = requested.headers().firstValue("ETag").orElseThrow();
        String reviewed = JSON.readTree(requested.body()).path("canonicalHash").asString();
        expectStatus(send(copy, "bob", "/rule-sets/" + name + "/versions/" + version + ":approve",
                ("{\"expectedCanonicalHash\":\"" + reviewed + "\"}").getBytes(StandardCharsets.UTF_8),
                "application/json", etag), 200);
    }

    private static int evaluatedVersion(ConfigurableApplicationContext copy) throws Exception {
        return evaluatedVersion(copy, "card-fee");
    }

    private static int evaluatedVersion(ConfigurableApplicationContext copy, String name) throws Exception {
        HttpResponse<String> response = send(copy, "teller", "/decisions/" + name + ":evaluate",
                "{\"inputs\":{\"tier\":\"SILVER\",\"txCount\":1}}".getBytes(StandardCharsets.UTF_8), "application/json");
        expectStatus(response, 200);
        return JSON.readTree(response.body()).path("version").asInt();
    }

    private static void expectStatus(HttpResponse<String> response, int status) {
        assertThat(response.statusCode())
                .as("%s %s answered %s", response.request().method(), response.uri(), response.body())
                .isEqualTo(status);
    }

    /** Evaluates every 100 ms until the copy serves the version; returns System.nanoTime() of that request. */
    private static long awaitVersion(ConfigurableApplicationContext copy, int version, Duration timeout)
            throws Exception {
        return awaitVersion(copy, "card-fee", version, timeout);
    }

    private static long awaitVersion(ConfigurableApplicationContext copy, String name, int version, Duration timeout)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            long before = System.nanoTime();
            if (evaluatedVersion(copy, name) == version) {
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
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://" + LOOPBACK + ":"
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

    private static byte[] cardFee(String name, long defaultFee, String effectiveFrom) {
        String yaml = new String(cardFee(defaultFee), StandardCharsets.UTF_8).replace("ruleSet: card-fee",
                "ruleSet: " + name);
        if (effectiveFrom != null) {
            yaml = yaml.replace("schemaVersion: 1\n", "schemaVersion: 1\neffectiveFrom: \"" + effectiveFrom + "\"\n");
        }
        return yaml.getBytes(StandardCharsets.UTF_8);
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
