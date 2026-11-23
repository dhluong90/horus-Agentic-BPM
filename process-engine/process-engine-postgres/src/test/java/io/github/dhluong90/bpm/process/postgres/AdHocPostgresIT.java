package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.PermissionDeniedException;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Ad-hoc sub-process (#500) on PostgreSQL: a customer onboarding whose identity check and sanctions screening the
 * branch manager starts, in any order, once the officer's work has begun. The operator action needs
 * {@code processes:task:manage}; a replay of its key changes nothing; and when the identity check passes, the
 * screening that still runs is cancelled with its work item.
 */
class AdHocPostgresIT {

    private static final Instant T0 = Instant.parse("2027-03-01T02:00:00Z");
    private static final String[] OFFICER = {"processes:start", "processes:read", "processes:task:work"};
    private static final String[] MANAGER = {"processes:read", "processes:task:manage"};

    private static final String ONBOARDING = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:horus="urn:horus-bpm:bpmn:extensions:v1" id="d" targetNamespace="urn:t"
                              expressionLanguage="urn:horus-bpm:expr:v1">
              <bpmn:process id="customer-onboarding" isExecutable="true">
                <bpmn:startEvent id="start"/>
                <bpmn:adHocSubProcess id="onboarding">
                  <bpmn:userTask id="collect-id" horus:assignee="role:OFFICER" horus:resultVariable="idCheck"/>
                  <bpmn:userTask id="screen-sanctions" horus:assignee="role:OFFICER" horus:resultVariable="sanctions"/>
                  <bpmn:completionCondition>idCheck.passed == true</bpmn:completionCondition>
                </bpmn:adHocSubProcess>
                <bpmn:userTask id="open-account" horus:assignee="role:OFFICER" horus:resultVariable="opened"/>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="onboarding"/>
                <bpmn:sequenceFlow id="f2" sourceRef="onboarding" targetRef="open-account"/>
                <bpmn:sequenceFlow id="f3" sourceRef="open-account" targetRef="end"/>
              </bpmn:process>
            </bpmn:definitions>
            """;

    private final TestClock clock = TestClock.at(T0);
    private TestEngine engine;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        TestEngine.reset();
        engine = TestEngine.replica("pod-1", clock).build();
        jdbc = TestEngine.jdbc();
        engine.identity.as("bank-a", "pipeline", "processes:deploy", "processes:read");
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                ONBOARDING.getBytes(StandardCharsets.UTF_8), null));
    }

    @AfterEach
    void stop() {
        engine.close();
    }

    private void as(String caller, String[] permissions) {
        engine.identity.as("bank-a", caller, permissions).roles = Set.of("OFFICER");
    }

    private long openTasks(String instanceId, String node) {
        return jdbc.sql("SELECT count(*) FROM pre_task WHERE instance_id = ? AND node_id = ? AND status IN"
                + " ('OPEN','CLAIMED')").params(instanceId, node).query(Long.class).single();
    }

    private static String scopeOf(ProcessInstance instance) {
        return instance.tokens().stream().filter(t -> t.kind().equals("SCOPE") && t.nodeId().equals("onboarding"))
                .findFirst().orElseThrow().id();
    }

    @Test
    void PRE_336_the_operator_starts_activities_with_task_manage_and_the_condition_cancels_the_rest() {
        // Given an onboarding that has entered the ad-hoc sub-process and runs nothing yet
        as("officer-lan", OFFICER);
        ProcessInstance onboarding = engine.services.start(new StartRequest("customer-onboarding", "CUS-0042",
                Map.of(), null));
        assertThat(openTasks(onboarding.id(), "collect-id")).isZero();
        String scope = scopeOf(onboarding);

        // When an officer without processes:task:manage tries to start an activity, it is refused and nothing changes
        assertThatThrownBy(() -> engine.services.startAdHocActivity(onboarding.id(), onboarding.lockVersion(), scope,
                "collect-id", "k0")).isInstanceOf(PermissionDeniedException.class);
        assertThat(openTasks(onboarding.id(), "collect-id")).isZero();

        // When the manager starts the identity check and the screening, a replay of the first key adds nothing
        as("manager-hoa", MANAGER);
        ProcessInstance first = engine.services.startAdHocActivity(onboarding.id(), onboarding.lockVersion(), scope,
                "collect-id", "k1");
        ProcessInstance replay = engine.services.startAdHocActivity(onboarding.id(), onboarding.lockVersion(), scope,
                "collect-id", "k1");
        assertThat(replay.lockVersion()).isEqualTo(first.lockVersion());
        assertThat(openTasks(onboarding.id(), "collect-id")).isOne();
        ProcessInstance both = engine.services.startAdHocActivity(onboarding.id(), first.lockVersion(), scope,
                "screen-sanctions", "k2");
        assertThat(openTasks(onboarding.id(), "screen-sanctions")).isOne();

        // When the officer completes the identity check as passed
        as("officer-lan", OFFICER);
        ProcessInstance.Task id = both.tasks().stream().filter(t -> t.nodeId().equals("collect-id")).findFirst()
                .orElseThrow();
        long claimed = engine.services.claim(id.id(), id.lockVersion(), "claim-id").lockVersion();
        ProcessInstance done = engine.services.complete(id.id(), Map.of("passed", true), claimed, "complete-id");

        // Then the screening is cancelled with its work item and the account opening is the open step
        assertThat(openTasks(onboarding.id(), "screen-sanctions")).isZero();
        assertThat(openTasks(onboarding.id(), "open-account")).isOne();
        assertThat(done.status()).isEqualTo("RUNNING");
    }
}
