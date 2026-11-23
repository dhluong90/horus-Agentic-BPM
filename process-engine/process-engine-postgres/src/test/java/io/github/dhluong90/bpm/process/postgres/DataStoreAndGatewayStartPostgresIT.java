package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.MessageService.MessageDelivery;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SignalBroadcast;
import io.github.dhluong90.bpm.process.api.SignalRequest;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.api.ValidationFailedException;
import io.github.dhluong90.bpm.process.spi.DataStoreAdapter;
import io.github.dhluong90.bpm.process.spi.HandlerResult;
import io.github.dhluong90.bpm.process.spi.ServiceTaskHandler;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * An instantiating event-based gateway and data store execution (#499) on PostgreSQL. The tenant that reaches a data
 * store is the instance's own, so one bank never reads or writes another bank's rows.
 */
class DataStoreAndGatewayStartPostgresIT {

    private static final String[] PIPELINE = {"processes:deploy", "processes:start", "processes:read",
            "processes:message:correlate", "processes:signal:broadcast"};

    private static final String SCORING = """
            <bpmn:dataStore id="credit-limits" name="Credit limits"/>
            <bpmn:dataStore id="score-ledger" name="Score ledger"/>
            <bpmn:process id="scoring" isExecutable="true">
              <bpmn:startEvent id="start"/>
              <bpmn:serviceTask id="score" horus:handler="credit.score">
                <bpmn:ioSpecification id="io">
                  <bpmn:dataInput id="in-limit" name="limit"/>
                  <bpmn:dataOutput id="out-score" name="score"/>
                  <bpmn:inputSet><bpmn:dataInputRefs>in-limit</bpmn:dataInputRefs></bpmn:inputSet>
                  <bpmn:outputSet><bpmn:dataOutputRefs>out-score</bpmn:dataOutputRefs></bpmn:outputSet>
                </bpmn:ioSpecification>
                <bpmn:dataInputAssociation id="a-in"><bpmn:sourceRef>limits-ref</bpmn:sourceRef><bpmn:targetRef>in-limit</bpmn:targetRef></bpmn:dataInputAssociation>
                <bpmn:dataOutputAssociation id="a-out"><bpmn:sourceRef>out-score</bpmn:sourceRef><bpmn:targetRef>ledger-ref</bpmn:targetRef></bpmn:dataOutputAssociation>
              </bpmn:serviceTask>
              <bpmn:endEvent id="end"/>
              <bpmn:dataStoreReference id="limits-ref" dataStoreRef="credit-limits"/>
              <bpmn:dataStoreReference id="ledger-ref" dataStoreRef="score-ledger"/>
              <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="score"/>
              <bpmn:sequenceFlow id="f2" sourceRef="score" targetRef="end"/>
            </bpmn:process>
            """;

    private static final String INTAKE = """
            <bpmn:message id="m-app" name="loan-application"/>
            <bpmn:message id="m-walkin" name="walk-in-application"/>
            <bpmn:signal id="s-batch" name="branch-batch-opened"/>
            <bpmn:process id="loan-intake" isExecutable="true">
              <bpmn:eventBasedGateway id="intake" instantiate="true"/>
              <bpmn:intermediateCatchEvent id="online"><bpmn:messageEventDefinition messageRef="m-app"/></bpmn:intermediateCatchEvent>
              <bpmn:intermediateCatchEvent id="branch"><bpmn:signalEventDefinition signalRef="s-batch"/></bpmn:intermediateCatchEvent>
              <bpmn:userTask id="review-online" horus:assignee="role:OPS" horus:resultVariable="r1"/>
              <bpmn:userTask id="review-branch" horus:assignee="role:OPS" horus:resultVariable="r2"/>
              <bpmn:endEvent id="end"/>
              <bpmn:sequenceFlow id="f1" sourceRef="intake" targetRef="online"/>
              <bpmn:sequenceFlow id="f2" sourceRef="intake" targetRef="branch"/>
              <bpmn:sequenceFlow id="f3" sourceRef="online" targetRef="review-online"/>
              <bpmn:sequenceFlow id="f4" sourceRef="branch" targetRef="review-branch"/>
              <bpmn:sequenceFlow id="f5" sourceRef="review-online" targetRef="end"/>
              <bpmn:sequenceFlow id="f6" sourceRef="review-branch" targetRef="end"/>
            </bpmn:process>
            """;

    /** The parallel type (#499): the message starts the instance and the signal and the second message are awaited. */
    private static final String ONBOARDING = """
            <bpmn:message id="m-kyc" name="kyc-documents"/>
            <bpmn:message id="m-consent" name="consent-signed"/>
            <bpmn:signal id="s-sanction" name="sanction-screening-done"/>
            <bpmn:process id="onboarding" isExecutable="true">
              <bpmn:eventBasedGateway id="intake" instantiate="true" eventGatewayType="Parallel"/>
              <bpmn:intermediateCatchEvent id="kyc"><bpmn:messageEventDefinition messageRef="m-kyc"/></bpmn:intermediateCatchEvent>
              <bpmn:intermediateCatchEvent id="consent"><bpmn:messageEventDefinition messageRef="m-consent"/></bpmn:intermediateCatchEvent>
              <bpmn:intermediateCatchEvent id="sanction"><bpmn:signalEventDefinition signalRef="s-sanction"/></bpmn:intermediateCatchEvent>
              <bpmn:userTask id="check-kyc" horus:assignee="role:OPS" horus:resultVariable="r1"/>
              <bpmn:userTask id="file-consent" horus:assignee="role:OPS" horus:resultVariable="r2"/>
              <bpmn:userTask id="clear-sanction" horus:assignee="role:OPS" horus:resultVariable="r3"/>
              <bpmn:endEvent id="end"/>
              <bpmn:sequenceFlow id="f1" sourceRef="intake" targetRef="kyc"/>
              <bpmn:sequenceFlow id="f2" sourceRef="intake" targetRef="consent"/>
              <bpmn:sequenceFlow id="f3" sourceRef="intake" targetRef="sanction"/>
              <bpmn:sequenceFlow id="f4" sourceRef="kyc" targetRef="check-kyc"/>
              <bpmn:sequenceFlow id="f5" sourceRef="consent" targetRef="file-consent"/>
              <bpmn:sequenceFlow id="f6" sourceRef="sanction" targetRef="clear-sanction"/>
              <bpmn:sequenceFlow id="f7" sourceRef="check-kyc" targetRef="end"/>
              <bpmn:sequenceFlow id="f8" sourceRef="file-consent" targetRef="end"/>
              <bpmn:sequenceFlow id="f9" sourceRef="clear-sanction" targetRef="end"/>
            </bpmn:process>
            """;

    /** A store whose rows are keyed by tenant: the adapter honours the tenant it is given. */
    private static final class TenantStore implements DataStoreAdapter {
        private final String name;
        private final Map<String, Object> rows = new ConcurrentHashMap<>();
        private final List<DataStoreAccess> accesses = new CopyOnWriteArrayList<>();

        TenantStore(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Object read(DataStoreAccess access) {
            accesses.add(access);
            return rows.get(access.tenantId() + "/" + access.item());
        }

        @Override
        public void write(DataStoreAccess access, Object value) {
            accesses.add(access);
            rows.put(access.tenantId() + "/" + access.item(), value);
        }
    }

    private final TestClock clock = TestClock.at(Instant.parse("2027-03-01T02:00:00Z"));
    private final TenantStore limits = new TenantStore("credit-limits");
    private final TenantStore ledger = new TenantStore("score-ledger");
    private final List<String> scored = new CopyOnWriteArrayList<>();
    private TestEngine engine;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        TestEngine.reset();
        ServiceTaskHandler score = new ServiceTaskHandler() {
            @Override
            public String name() {
                return "credit.score";
            }

            @Override
            public HandlerResult handle(io.github.dhluong90.bpm.process.spi.ServiceCall call) {
                scored.add(call.tenantId() + ":" + call.inputs().get("limit"));
                return HandlerResult.completed(Map.of("score", 710));
            }
        };
        engine = TestEngine.replica("pod-1", clock).handlers(score).dataStores(limits, ledger).build();
        jdbc = TestEngine.jdbc();
    }

    @AfterEach
    void stop() {
        engine.close();
    }

    private static byte[] bpmn(String key, String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:horus="urn:horus-bpm:bpmn:extensions:v1"
                                  id="Definitions_%s" targetNamespace="urn:horus-bpm:examples"
                                  expressionLanguage="urn:horus-bpm:expr:v1">
                %s
                </bpmn:definitions>
                """.formatted(key, body).getBytes(StandardCharsets.UTF_8);
    }

    private void deploy(String tenant, String key, String body) {
        engine.identity.as(tenant, "pipeline", PIPELINE);
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML, bpmn(key, body), null));
    }

    private ProcessInstance start(String tenant, String key, String businessKey, Map<String, Object> variables) {
        engine.identity.as(tenant, "officer-lan", PIPELINE);
        return engine.services.start(new StartRequest(key, businessKey, variables, null));
    }

    private String text(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(String.class).single();
    }

    private long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    @Test
    void PRE_354_a_step_reads_and_writes_its_stores_and_the_adapter_sees_only_the_tenant_of_the_instance() {
        // Given bank-a limit 50,000,000 VND and bank-b limit 5,000,000 VND under the same item name
        limits.rows.put("bank-a/limit", 50_000_000);
        limits.rows.put("bank-b/limit", 5_000_000);
        deploy("bank-a", "scoring", SCORING);
        deploy("bank-b", "scoring", SCORING);

        // When each bank scores an applicant, one of them naming the other bank in a variable
        ProcessInstance a = start("bank-a", "scoring", "BK-A", Map.of("tenantId", "bank-b"));
        ProcessInstance b = start("bank-b", "scoring", "BK-B", Map.of());

        // Then each step got its own bank's limit and wrote its score under its own bank, and a variable named
        // tenantId changed nothing
        assertThat(scored).containsExactlyInAnyOrder("bank-a:50000000", "bank-b:5000000");
        assertThat(ledger.rows).containsEntry("bank-a/score", 710).containsEntry("bank-b/score", 710);
        assertThat(limits.accesses).extracting(DataStoreAdapter.DataStoreAccess::tenantId)
                .containsExactlyInAnyOrder("bank-a", "bank-b");
        assertThat(ledger.accesses).extracting(DataStoreAdapter.DataStoreAccess::tenantId)
                .containsExactlyInAnyOrder("bank-a", "bank-b");
        assertThat(text("SELECT status FROM pre_instance WHERE id = ?", a.id())).isEqualTo("COMPLETED");
        assertThat(text("SELECT status FROM pre_instance WHERE id = ?", b.id())).isEqualTo("COMPLETED");
        assertThat(limits.accesses).allSatisfy(x -> assertThat(x.store()).isEqualTo("credit-limits"));
        assertThat(count("SELECT count(*) FROM pre_history WHERE instance_id = ? AND event_type = 'DATA_STORE_READ'",
                a.id())).isOne();
        assertThat(count("SELECT count(*) FROM pre_history WHERE instance_id = ? AND event_type = 'DATA_STORE_WRITTEN'",
                a.id())).isOne();
    }

    @Test
    void PRE_354_a_definition_that_uses_a_store_with_no_adapter_is_refused_at_deploy_time() {
        // Given a host that registers no adapter for score-ledger
        engine.close();
        TestEngine.reset();
        engine = TestEngine.replica("pod-2", clock).handlers().dataStores(limits).build();
        jdbc = TestEngine.jdbc();

        // When the definition is imported
        assertThatThrownBy(() -> deploy("bank-a", "scoring", SCORING))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.problems())
                        .extracting(DefinitionProblem::code).contains("DATA_STORE_ADAPTER_NOT_REGISTERED"));

        // Then nothing is stored
        assertThat(count("SELECT count(*) FROM pre_definition")).isZero();
    }

    @Test
    void PRE_354_a_store_that_cannot_be_written_stops_the_step_and_the_instance_does_not_complete() {
        // Given a read-only store: no write is implemented
        engine.close();
        TestEngine.reset();
        DataStoreAdapter readOnly = new DataStoreAdapter() {
            @Override
            public String name() {
                return "score-ledger";
            }
        };
        engine = TestEngine.replica("pod-3", clock).handlers(new ServiceTaskHandler() {
            @Override
            public String name() {
                return "credit.score";
            }

            @Override
            public HandlerResult handle(io.github.dhluong90.bpm.process.spi.ServiceCall call) {
                return HandlerResult.completed(Map.of("score", 640));
            }
        }).dataStores(limits, readOnly).build();
        jdbc = TestEngine.jdbc();
        limits.rows.put("bank-a/limit", 1_000_000);
        deploy("bank-a", "scoring", SCORING);

        assertThatThrownBy(() -> start("bank-a", "scoring", "BK-RO", Map.of())).hasMessageContaining("DATA_STORE_WRITE_UNSUPPORTED");
        assertThat(count("SELECT count(*) FROM pre_instance WHERE status = 'COMPLETED'")).isZero();
    }

    @Test
    void PRE_352_the_first_message_creates_the_instance_after_its_catch_and_the_others_are_not_waited_for() {
        // Given a loan intake that starts on a loan-application message or a branch-batch-opened signal
        deploy("bank-a", "loan-intake", INTAKE);

        // When the message arrives with the applicant
        engine.identity.as("bank-a", "intake-consumer", PIPELINE);
        MessageDelivery d = engine.services.correlate("loan-application", "APP-9001",
                Map.of("applicant", "Tran Thi Mai", "amount", 300_000_000), "app-1");

        // Then an instance is created, it waits at the review after the message catch, nothing waits on the signal,
        // and the message payload is the instance's variables
        assertThat(d.outcome()).isEqualTo(MessageDelivery.Outcome.STARTED);
        assertThat(text("SELECT status FROM pre_instance WHERE id = ?", d.instanceId())).isEqualTo("RUNNING");
        assertThat(text("SELECT current_node_id FROM pre_token WHERE instance_id = ? AND status = 'WAITING_USER'",
                d.instanceId())).isEqualTo("review-online");
        assertThat(count("SELECT count(*) FROM pre_token WHERE instance_id = ? AND status IN ('WAITING_USER', 'WAITING_EVENT', 'WAITING_TIMER')",
                d.instanceId())).isOne();
        assertThat(count("SELECT count(*) FROM pre_message_subscription WHERE instance_id = ?", d.instanceId())).isZero();
        assertThat(text("SELECT variables::text FROM pre_instance WHERE id = ?", d.instanceId()))
                .contains("Tran Thi Mai");
        assertThat(count("SELECT count(*) FROM pre_history WHERE instance_id = ? AND event_type = 'EVENT_GATEWAY_STARTED'",
                d.instanceId())).isOne();
    }

    @Test
    void PRE_352_a_signal_creates_an_instance_of_its_own_tenant_after_the_signal_catch() {
        // Given the intake deployed for bank-a and bank-b
        deploy("bank-a", "loan-intake", INTAKE);
        deploy("bank-b", "loan-intake", INTAKE);

        // When bank-a opens a branch batch
        engine.identity.as("bank-a", "branch-admin", PIPELINE);
        SignalBroadcast sent = engine.services.broadcast(new SignalRequest("branch-batch-opened", "batch-1"));

        // Then exactly one instance starts, in bank-a, waiting at the review after the signal catch
        assertThat(sent.startedInstances()).hasSize(1);
        assertThat(text("SELECT tenant_id FROM pre_instance WHERE id = ?", sent.startedInstances().get(0))).isEqualTo("bank-a");
        assertThat(text("SELECT current_node_id FROM pre_token WHERE instance_id = ? AND status = 'WAITING_USER'",
                sent.startedInstances().get(0))).isEqualTo("review-branch");
        assertThat(count("SELECT count(*) FROM pre_instance WHERE tenant_id = 'bank-b'")).isZero();
    }

    @Test
    void PRE_352_a_message_no_catch_waits_for_starts_nothing_and_a_plain_start_is_refused() {
        deploy("bank-a", "loan-intake", INTAKE);

        engine.identity.as("bank-a", "intake-consumer", PIPELINE);
        assertThatThrownBy(() -> engine.services.correlate("walk-in-application", "APP-1", Map.of(), "w-1"))
                .isInstanceOf(io.github.dhluong90.bpm.process.api.MessageNotCorrelatedException.class);
        assertThatThrownBy(() -> start("bank-a", "loan-intake", "BK-1", Map.of())).hasMessageContaining("START_TRIGGER_REQUIRED");
        assertThat(count("SELECT count(*) FROM pre_instance")).isZero();
    }

    @Test
    void PRE_355_the_first_event_creates_the_instance_and_every_other_catch_is_awaited() {
        // Given an onboarding that starts on kyc-documents and awaits consent-signed and sanction-screening-done
        deploy("bank-a", "onboarding", ONBOARDING);

        // When the first message arrives for customer CIF-1001
        engine.identity.as("bank-a", "intake-consumer", PIPELINE);
        MessageDelivery first = engine.services.correlate("kyc-documents", "CIF-1001", Map.of(), "kyc-1");

        // Then one instance runs after the first catch and two catches wait, none cancelled
        assertThat(first.outcome()).isEqualTo(MessageDelivery.Outcome.STARTED);
        assertThat(count("SELECT count(*) FROM pre_token WHERE instance_id = ? AND status = 'WAITING_USER'",
                first.instanceId())).isOne();
        assertThat(count("SELECT count(*) FROM pre_token WHERE instance_id = ? AND status = 'WAITING_EVENT'",
                first.instanceId())).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM pre_token WHERE instance_id = ? AND status = 'CANCELLED'",
                first.instanceId())).isZero();

        // When the other message and the signal come
        MessageDelivery second = engine.services.correlate("consent-signed", "CIF-1001", Map.of(), "consent-1");
        engine.services.broadcast(new SignalRequest("sanction-screening-done", "sig-1"));
        engine.jobs.pollOnce();

        // Then the same instance now waits at three reviews and no second instance exists
        assertThat(second.outcome()).isEqualTo(MessageDelivery.Outcome.DELIVERED);
        assertThat(second.instanceId()).isEqualTo(first.instanceId());
        assertThat(count("SELECT count(*) FROM pre_token WHERE instance_id = ? AND status = 'WAITING_USER'",
                first.instanceId())).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM pre_instance WHERE definition_key = 'onboarding'")).isOne();
    }

    @Test
    void PRE_355_two_events_of_one_customer_on_two_replicas_create_one_instance() throws Exception {
        // Given two replicas of the engine on one database
        deploy("bank-a", "onboarding", ONBOARDING);
        TestEngine other = TestEngine.replica("pod-2", clock).build();
        java.util.concurrent.ExecutorService pods = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.CyclicBarrier sameMoment = new java.util.concurrent.CyclicBarrier(2);
            // When kyc-documents and consent-signed for CIF-2002 arrive at the same moment, one on each replica
            java.util.concurrent.Future<MessageDelivery> a = pods.submit(() -> {
                engine.identity.as("bank-a", "consumer-1", PIPELINE);
                sameMoment.await();
                return engine.services.correlate("kyc-documents", "CIF-2002", Map.of(), "k-1");
            });
            java.util.concurrent.Future<MessageDelivery> b = pods.submit(() -> {
                other.identity.as("bank-a", "consumer-2", PIPELINE);
                sameMoment.await();
                return other.services.correlate("consent-signed", "CIF-2002", Map.of(), "c-1");
            });
            MessageDelivery da = a.get(30, java.util.concurrent.TimeUnit.SECONDS);
            MessageDelivery db = b.get(30, java.util.concurrent.TimeUnit.SECONDS);

            // Then one instance exists, and it waits at two reviews and for the signal
            assertThat(count("SELECT count(*) FROM pre_instance WHERE definition_key = 'onboarding'")).isOne();
            assertThat(da.instanceId()).isEqualTo(db.instanceId());
            assertThat(List.of(da.outcome(), db.outcome()))
                    .containsExactlyInAnyOrder(MessageDelivery.Outcome.STARTED, MessageDelivery.Outcome.DELIVERED);
            assertThat(count("SELECT count(*) FROM pre_token WHERE instance_id = ? AND status = 'WAITING_USER'",
                    da.instanceId())).isEqualTo(2);
            assertThat(count("SELECT count(*) FROM pre_token WHERE instance_id = ? AND status = 'WAITING_EVENT'",
                    da.instanceId())).isOne();
        } finally {
            pods.shutdownNow();
            other.close();
        }
    }

    @Test
    void PRE_355_the_same_customer_key_in_two_tenants_starts_an_instance_in_each() {
        // Given the onboarding in bank-a and bank-b
        deploy("bank-a", "onboarding", ONBOARDING);
        deploy("bank-b", "onboarding", ONBOARDING);

        // When both banks receive kyc-documents for CIF-3003, then bank-b's consent-signed
        engine.identity.as("bank-a", "consumer", PIPELINE);
        MessageDelivery a = engine.services.correlate("kyc-documents", "CIF-3003", Map.of(), "a-1");
        engine.identity.as("bank-b", "consumer", PIPELINE);
        MessageDelivery b = engine.services.correlate("kyc-documents", "CIF-3003", Map.of(), "b-1");
        MessageDelivery consent = engine.services.correlate("consent-signed", "CIF-3003", Map.of(), "b-2");

        // Then each tenant has its own instance and the consent reached bank-b's only
        assertThat(a.instanceId()).isNotEqualTo(b.instanceId());
        assertThat(consent.instanceId()).isEqualTo(b.instanceId());
        assertThat(count("SELECT count(*) FROM pre_token WHERE instance_id = ? AND status = 'WAITING_USER'",
                a.instanceId())).isOne();
        assertThat(count("SELECT count(*) FROM pre_token WHERE instance_id = ? AND status = 'WAITING_USER'",
                b.instanceId())).isEqualTo(2);
    }
}
