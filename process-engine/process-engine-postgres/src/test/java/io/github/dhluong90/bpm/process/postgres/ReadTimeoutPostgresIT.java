package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.InstanceQuery;
import io.github.dhluong90.bpm.process.api.PageRequest;
import io.github.dhluong90.bpm.process.api.ProcessException;
import io.github.dhluong90.bpm.process.api.QueryTimeoutException;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PRE-394 (#254) on PostgreSQL: the instance and definition lists run in a read-only transaction of their own with
 * {@code query.statement-timeout}. A list slowed by a table lock held elsewhere is stopped and answered
 * {@code QUERY_TIMEOUT} with no partial page, a write transaction of the host that is open around the read is left
 * alone, and the limit does not outlive the read.
 */
class ReadTimeoutPostgresIT {

    private static final Duration LIMIT = Duration.ofMillis(500);

    private final TestClock clock = TestClock.at("2027-04-05T03:00:00Z");
    private final DataSource db = TestEngine.dataSource();
    private TestEngine engine;
    private Connection locker;

    @BeforeEach
    void setUp() {
        TestEngine.reset();
        engine = TestEngine.replica("pod-1", clock).readTimeout(LIMIT).build();
        engine.identity.as("bank-a", "dan", "processes:read");
        JdbcClient.create(db).sql("CREATE TABLE IF NOT EXISTS pre_394_probe (id INT PRIMARY KEY)").update();
        JdbcClient.create(db).sql("DELETE FROM pre_394_probe").update();
    }

    @AfterEach
    void release() throws SQLException {
        if (locker != null) {
            locker.rollback();
            locker.close();
        }
    }

    /** Holds an exclusive lock on a table from another connection, so a read of it waits until the limit. */
    private void slowDown(String table) throws SQLException {
        locker = db.getConnection();
        locker.setAutoCommit(false);
        try (Statement st = locker.createStatement()) {
            st.execute("LOCK TABLE " + table + " IN ACCESS EXCLUSIVE MODE");
        }
    }

    @Test
    void PRE_394_a_definition_list_slower_than_the_limit_is_stopped_with_a_named_code() throws Exception {
        slowDown("pre_definition");

        long started = System.nanoTime();
        assertThatThrownBy(() -> engine.queries.definitions(PageRequest.first()))
                .isInstanceOf(QueryTimeoutException.class)
                .isInstanceOfSatisfying(ProcessException.class, e -> assertThat(e.code()).isEqualTo("QUERY_TIMEOUT"))
                .hasMessageContaining("PT0.5S");

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void PRE_394_an_instance_list_slower_than_the_limit_is_stopped_with_a_named_code() throws Exception {
        slowDown("pre_instance");

        assertThatThrownBy(() -> engine.queries.instances(InstanceQuery.running()))
                .isInstanceOfSatisfying(ProcessException.class, e -> assertThat(e.code()).isEqualTo("QUERY_TIMEOUT"));
    }

    @Test
    void PRE_521_live_counts_slower_than_the_limit_are_stopped_with_a_named_code_and_no_partial_counts() throws Exception {
        // Given a definition version, and the incident table locked elsewhere while the open incidents are counted
        engine.identity.as("bank-a", "pipeline", "processes:deploy", "processes:read");
        engine.services.importDefinition(new io.github.dhluong90.bpm.process.api.ImportRequest(
                io.github.dhluong90.bpm.process.api.SourceFormat.BPMN_XML, """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:horus="urn:horus-bpm:bpmn:extensions:v1" id="Definitions_kyc"
                                  targetNamespace="urn:horus-bpm:examples" expressionLanguage="urn:horus-bpm:expr:v1">
                  <bpmn:process id="kyc" name="KYC" isExecutable="true">
                    <bpmn:startEvent id="start"/>
                    <bpmn:userTask id="review" horus:assignee="role:KYC_OFFICER"/>
                    <bpmn:endEvent id="end"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="review"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="review" targetRef="end"/>
                  </bpmn:process>
                </bpmn:definitions>
                """.strip().getBytes(java.nio.charset.StandardCharsets.UTF_8), null));
        slowDown("pre_incident");

        // Then the counts are refused as QUERY_TIMEOUT, not answered with the token counts alone
        assertThatThrownBy(() -> engine.queries.liveNodeCounts("kyc", 1))
                .isInstanceOf(QueryTimeoutException.class)
                .isInstanceOfSatisfying(ProcessException.class, e -> assertThat(e.code()).isEqualTo("QUERY_TIMEOUT"));
        locker.rollback();
        assertThat(engine.queries.liveNodeCounts("kyc", 1).openIncidents()).isEmpty();
    }

    @Test
    void PRE_522_an_incident_list_slower_than_the_limit_is_stopped_with_a_named_code() throws Exception {
        slowDown("pre_incident");

        assertThatThrownBy(() -> engine.services.incidents(new io.github.dhluong90.bpm.process.api.IncidentQuery("OPEN",
                null, "kyc", 1))).isInstanceOfSatisfying(ProcessException.class,
                        e -> assertThat(e.code()).isEqualTo("QUERY_TIMEOUT"));
        assertThatThrownBy(() -> engine.services.incidents(io.github.dhluong90.bpm.process.api.IncidentQuery.open()))
                .isInstanceOf(QueryTimeoutException.class);
    }

    @Test
    void PRE_394_a_host_write_transaction_around_the_timed_out_read_is_untouched() throws Exception {
        slowDown("pre_definition");
        TransactionTemplate host = new TransactionTemplate(new DataSourceTransactionManager(db));

        host.executeWithoutResult(status -> {
            JdbcClient.create(db).sql("INSERT INTO pre_394_probe VALUES (1)").update();
            assertThatThrownBy(() -> engine.queries.definitions(PageRequest.first())).isInstanceOf(QueryTimeoutException.class);
            // The host transaction is still usable and commits what it wrote before the read.
            JdbcClient.create(db).sql("INSERT INTO pre_394_probe VALUES (2)").update();
        });

        assertThat(JdbcClient.create(db).sql("SELECT count(*) FROM pre_394_probe").query(Long.class).single()).isEqualTo(2L);
    }

    @Test
    void PRE_394_the_list_answers_once_the_lock_is_gone_and_the_limit_does_not_stay_on_the_connection() throws Exception {
        slowDown("pre_definition");
        assertThatThrownBy(() -> engine.queries.definitions(PageRequest.first())).isInstanceOf(QueryTimeoutException.class);
        locker.rollback();

        assertThat(engine.queries.definitions(PageRequest.first()).items()).isEmpty();
        try (Connection c = db.getConnection(); Statement st = c.createStatement();
                var rs = st.executeQuery("SHOW statement_timeout")) {
            rs.next();
            assertThat(rs.getString(1)).isEqualTo("0");
        }
    }
}
