package io.github.dhluong90.bpm.process.bpmn.subset;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.bpmn.xml.Limits;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorCatalog;
import io.github.dhluong90.bpm.process.internal.exec.Command;
import io.github.dhluong90.bpm.process.internal.exec.CompiledProcess;
import io.github.dhluong90.bpm.process.internal.exec.DefaultExecutionKernel;
import io.github.dhluong90.bpm.process.internal.exec.Effect;
import io.github.dhluong90.bpm.process.internal.exec.InstanceState;
import io.github.dhluong90.bpm.process.internal.exec.InstanceStatus;
import io.github.dhluong90.bpm.process.internal.exec.ProcessCompiler;
import io.github.dhluong90.bpm.process.internal.exec.StepResult;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.exec.TokenKind;
import io.github.dhluong90.bpm.process.internal.exec.TokenStatus;
import io.github.dhluong90.bpm.process.internal.exec.Transition;
import io.github.dhluong90.bpm.process.internal.expr.V1ExpressionEvaluator;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The default flow of an activity, and a terminate end reached from an error boundary while another branch of a
 * parallel fork waits (PRE-373, PRE-374), in memory: a credit check that routes by grade, and a loan preparation
 * that a collateral fraud flag ends.
 */
class ActivityDefaultFlowAndTerminateTest {

    private static final String INSTANCE = "01JD0000000000000000000290";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-25T03:00:00Z"), ZoneOffset.UTC);
    private static final String HEAD = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:horus="urn:horus-bpm:bpmn:extensions:v1" id="d" targetNamespace="urn:t"
                              expressionLanguage="urn:horus-bpm:expr:v1">
              <bpmn:error id="Err_flag" name="Collateral flagged" errorCode="COLLATERAL_FLAGGED"/>
              <bpmn:process id="loan" isExecutable="true">
            """;
    private static final String TAIL = "</bpmn:process></bpmn:definitions>";

    /** A credit check with a default flow to manual review, an auto-approve flow on a condition, and a plain audit flow. */
    private static final String CREDIT_CHECK = HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:serviceTask id="grade" horus:handler="credit-grade" default="to-review"/>
                <bpmn:serviceTask id="auto-approve" horus:handler="approve"/>
                <bpmn:serviceTask id="manual-review" horus:handler="review"/>
                <bpmn:serviceTask id="audit" horus:handler="audit"/>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="grade"/>
                <bpmn:sequenceFlow id="to-approve" sourceRef="grade" targetRef="auto-approve">
                  <bpmn:conditionExpression>score &gt;= 700</bpmn:conditionExpression>
                </bpmn:sequenceFlow>
                <bpmn:sequenceFlow id="to-review" sourceRef="grade" targetRef="manual-review"/>
                <bpmn:sequenceFlow id="to-audit" sourceRef="grade" targetRef="audit"/>
                <bpmn:sequenceFlow id="f5" sourceRef="auto-approve" targetRef="end"/>
                <bpmn:sequenceFlow id="f6" sourceRef="manual-review" targetRef="end"/>
                <bpmn:sequenceFlow id="f7" sourceRef="audit" targetRef="end"/>
            """ + TAIL;

    /**
     * Collateral registration (error boundary to a terminate end) in parallel with a valuation and a join. The
     * valuation branch is a user task, so the join waits; {@code valuation} may be a service task for the in-flight case.
     */
    private static String preparation(String valuationElement) {
        return HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:parallelGateway id="fork"/>
                <bpmn:serviceTask id="register-collateral" horus:handler="register-collateral"/>
                <bpmn:boundaryEvent id="collateral-flagged" attachedToRef="register-collateral">
                  <bpmn:errorEventDefinition errorRef="Err_flag"/>
                </bpmn:boundaryEvent>
                <bpmn:endEvent id="end-fraud"><bpmn:terminateEventDefinition/></bpmn:endEvent>
                %s
                <bpmn:parallelGateway id="join"/>
                <bpmn:serviceTask id="send-offer" horus:handler="send-offer"/>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="fork"/>
                <bpmn:sequenceFlow id="f2" sourceRef="fork" targetRef="register-collateral"/>
                <bpmn:sequenceFlow id="f3" sourceRef="fork" targetRef="valuation"/>
                <bpmn:sequenceFlow id="f4" sourceRef="register-collateral" targetRef="join"/>
                <bpmn:sequenceFlow id="f5" sourceRef="valuation" targetRef="join"/>
                <bpmn:sequenceFlow id="f6" sourceRef="collateral-flagged" targetRef="end-fraud"/>
                <bpmn:sequenceFlow id="f7" sourceRef="join" targetRef="send-offer"/>
                <bpmn:sequenceFlow id="f8" sourceRef="send-offer" targetRef="end"/>
                """.formatted(valuationElement) + TAIL;
    }

    private static final String USER_VALUATION =
            "<bpmn:userTask id=\"valuation\" horus:assignee=\"role:VALUER\" horus:resultVariable=\"value\"/>";
    private static final String SERVICE_VALUATION =
            "<bpmn:serviceTask id=\"valuation\" horus:handler=\"valuate\"/>";

    private final SubsetBpmnReader reader = new SubsetBpmnReader(Limits.DEFAULTS);

    private DefaultExecutionKernel kernel(String xml) {
        CompiledProcess compiled = new ProcessCompiler(BehaviorCatalog.registry(), 500)
                .compile("D1", reader.read(xml.getBytes(StandardCharsets.UTF_8)));
        return new DefaultExecutionKernel(compiled, BehaviorCatalog.registry(), new V1ExpressionEvaluator(100));
    }

    private static Transition start(DefaultExecutionKernel kernel, Map<String, Object> variables) {
        return kernel.apply(InstanceState.created(INSTANCE, "bank-a", "D1", "LA-2026-000454"),
                new Command.Start(variables, "officer.hoa"), CLOCK);
    }

    private static Effect.InvokeService call(Transition t, String nodeId) {
        return t.effects().stream().filter(Effect.InvokeService.class::isInstance).map(Effect.InvokeService.class::cast)
                .filter(e -> e.nodeId().equals(nodeId)).findFirst().orElseThrow();
    }

    private static List<String> invoked(Transition t) {
        return t.effects().stream().filter(Effect.InvokeService.class::isInstance).map(Effect.InvokeService.class::cast)
                .map(Effect.InvokeService::nodeId).toList();
    }

    private static Transition answer(DefaultExecutionKernel kernel, Transition scheduled, InstanceState from,
            String nodeId, StepResult result) {
        Effect.InvokeService call = call(scheduled, nodeId);
        return kernel.apply(from, new Command.AsyncResult(call.tokenId(), call.attemptId(), result), CLOCK);
    }

    private static Token flow(InstanceState state, String nodeId) {
        return state.tokens().stream().filter(t -> t.nodeId().equals(nodeId) && t.kind() == TokenKind.FLOW)
                .findFirst().orElseThrow();
    }

    @Test
    void PRE_373_a_matching_conditional_flow_suppresses_the_default_flow_of_an_activity() {
        // Given a credit check whose grade 720 matches the auto-approve condition
        DefaultExecutionKernel kernel = kernel(CREDIT_CHECK);
        Transition started = start(kernel, Map.of());

        // When the grade step answers 720
        Transition graded = kernel.apply(started.state(), new Command.AsyncResult(call(started, "grade").tokenId(),
                call(started, "grade").attemptId(), new StepResult.Completed(Map.of("score", 720L))), CLOCK);

        // Then auto-approve and the unconditioned audit flow run, and the default manual review does not
        assertThat(invoked(graded)).containsExactlyInAnyOrder("auto-approve", "audit");
    }

    @Test
    void PRE_373_the_default_flow_of_an_activity_is_taken_when_no_conditional_flow_matches() {
        // Given the same credit check
        DefaultExecutionKernel kernel = kernel(CREDIT_CHECK);
        Transition started = start(kernel, Map.of());

        // When the grade step answers 640, below the auto-approve condition
        Transition graded = kernel.apply(started.state(), new Command.AsyncResult(call(started, "grade").tokenId(),
                call(started, "grade").attemptId(), new StepResult.Completed(Map.of("score", 640L))), CLOCK);

        // Then the default manual review runs beside the unconditioned audit flow
        assertThat(invoked(graded)).containsExactlyInAnyOrder("manual-review", "audit");
    }

    @Test
    void PRE_374_a_terminate_end_reached_from_an_error_boundary_ends_the_instance_while_the_join_waits() {
        // Given a loan preparation whose valuation is open and whose collateral registration is running
        DefaultExecutionKernel kernel = kernel(preparation(USER_VALUATION));
        Transition started = start(kernel, Map.of());
        assertThat(flow(started.state(), "valuation").status()).isEqualTo(TokenStatus.WAITING_USER);

        // When the registry answers with the collateral-flagged error
        Transition ended = answer(kernel, started, started.state(), "register-collateral",
                new StepResult.BusinessError("COLLATERAL_FLAGGED", "Double pledge"));

        // Then the valuation task is cancelled, no token is live, and the instance completes
        assertThat(ended.state().tokens()).noneMatch(t -> t.status().live());
        assertThat(ended.state().status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(invoked(ended)).doesNotContain("send-offer");
    }

    @Test
    void PRE_374_a_job_in_flight_in_another_branch_is_cancelled_by_the_terminate() {
        // Given a valuation service call in flight beside the collateral registration
        DefaultExecutionKernel kernel = kernel(preparation(SERVICE_VALUATION));
        Transition started = start(kernel, Map.of());
        Effect.InvokeService valuation = call(started, "valuation");

        // When the collateral flag ends the instance
        Transition ended = answer(kernel, started, started.state(), "register-collateral",
                new StepResult.BusinessError("COLLATERAL_FLAGGED", "Double pledge"));

        // Then the valuation token is cancelled and its job is cancelled with it, so no result is awaited
        assertThat(ended.state().status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(flow(ended.state(), "valuation").status()).isEqualTo(TokenStatus.CANCELLED);
        assertThat(ended.effects()).filteredOn(Effect.CancelJob.class::isInstance).map(Effect.CancelJob.class::cast)
                .extracting(Effect.CancelJob::jobId).contains(valuation.attemptId());
    }

    @Test
    void PRE_374_a_terminate_end_reached_from_an_error_boundary_ends_the_instance_when_the_other_branch_already_waits_at_the_join() {
        // Given the valuation branch has finished and waits at the join, and the collateral registration is running
        DefaultExecutionKernel kernel = kernel(preparation(SERVICE_VALUATION));
        Transition started = start(kernel, Map.of());
        Transition valued = answer(kernel, started, started.state(), "valuation",
                new StepResult.Completed(Map.of("value", 1L)));
        assertThat(valued.state().tokens()).anyMatch(t -> t.nodeId().equals("join") && t.status() == TokenStatus.JOINED);

        // When the registry answers with the collateral-flagged error
        Transition ended = answer(kernel, started, valued.state(), "register-collateral",
                new StepResult.BusinessError("COLLATERAL_FLAGGED", "Double pledge"));

        // Then the token at the join is cancelled, nothing is live and the instance completes without an offer
        assertThat(ended.state().tokens()).noneMatch(t -> t.status().live());
        assertThat(ended.state().status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(invoked(ended)).doesNotContain("send-offer");
    }

    /**
     * The authority rule answers a tier; its outgoing flows read the result field, with the committee as the default
     * flow of the rule task. Each approval task has a non-interrupting timer boundary and both lead to one end event.
     */
    private static final String APPROVAL = HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:businessRuleTask id="determine-authority" horus:ruleSet="approval-authority"
                                       horus:resultVariable="authority" default="to-committee"/>
                <bpmn:userTask id="approve-manager" horus:assignee="role:CREDIT_MANAGER" horus:resultVariable="approval"/>
                <bpmn:boundaryEvent id="manager-sla" attachedToRef="approve-manager" cancelActivity="false">
                  <bpmn:timerEventDefinition><bpmn:timeDuration>PT4H</bpmn:timeDuration></bpmn:timerEventDefinition>
                </bpmn:boundaryEvent>
                <bpmn:userTask id="approve-committee" horus:assignee="role:CREDIT_COMMITTEE" horus:resultVariable="approval"/>
                <bpmn:boundaryEvent id="committee-sla" attachedToRef="approve-committee" cancelActivity="false">
                  <bpmn:timerEventDefinition><bpmn:timeDuration>P1D</bpmn:timeDuration></bpmn:timerEventDefinition>
                </bpmn:boundaryEvent>
                <bpmn:endEvent id="escalated"/>
                <bpmn:endEvent id="approved"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="determine-authority"/>
                <bpmn:sequenceFlow id="to-manager" sourceRef="determine-authority" targetRef="approve-manager">
                  <bpmn:conditionExpression>authority.approvalTier == 'MANAGER'</bpmn:conditionExpression>
                </bpmn:sequenceFlow>
                <bpmn:sequenceFlow id="to-committee" sourceRef="determine-authority" targetRef="approve-committee"/>
                <bpmn:sequenceFlow id="f4" sourceRef="approve-manager" targetRef="approved"/>
                <bpmn:sequenceFlow id="f5" sourceRef="approve-committee" targetRef="approved"/>
                <bpmn:sequenceFlow id="f6" sourceRef="manager-sla" targetRef="escalated"/>
                <bpmn:sequenceFlow id="f7" sourceRef="committee-sla" targetRef="escalated"/>
            """ + TAIL;

    private Transition decide(DefaultExecutionKernel kernel, Transition started, String tier) {
        Effect.InvokeService call = call(started, "determine-authority");
        return kernel.apply(started.state(), new Command.AsyncResult(call.tokenId(), call.attemptId(),
                new StepResult.Completed(Map.of("approvalTier", tier))), CLOCK);
    }

    @Test
    void PRE_375_a_sequence_flow_condition_reads_a_field_of_the_rule_task_result() {
        // Given a loan of 400 million VND whose authority rule answers the MANAGER tier
        DefaultExecutionKernel kernel = kernel(APPROVAL);
        Transition started = start(kernel, Map.of());

        // When the rule answers
        Transition decided = decide(kernel, started, "MANAGER");

        // Then the manager task opens and the committee task does not
        assertThat(flow(decided.state(), "approve-manager").status()).isEqualTo(TokenStatus.WAITING_USER);
        assertThat(decided.state().tokens()).noneMatch(t -> t.nodeId().equals("approve-committee"));
    }

    @Test
    void PRE_375_the_default_flow_of_a_rule_task_leads_to_the_committee_when_the_tier_is_not_manager() {
        // Given a loan of 3 billion VND whose authority rule answers the COMMITTEE tier
        DefaultExecutionKernel kernel = kernel(APPROVAL);
        Transition started = start(kernel, Map.of());

        // When the rule answers
        Transition decided = decide(kernel, started, "COMMITTEE");

        // Then only the committee task opens
        assertThat(flow(decided.state(), "approve-committee").status()).isEqualTo(TokenStatus.WAITING_USER);
        assertThat(decided.state().tokens()).noneMatch(t -> t.nodeId().equals("approve-manager"));
    }

    @Test
    void PRE_376_two_tasks_whose_escalation_boundaries_share_one_end_event_each_reach_it_and_stay_open() {
        for (String tier : List.of("MANAGER", "COMMITTEE")) {
            // Given the approval task of the tier is open with its timer armed
            DefaultExecutionKernel kernel = kernel(APPROVAL);
            Transition started = start(kernel, Map.of());
            Transition decided = decide(kernel, started, tier);
            String task = "MANAGER".equals(tier) ? "approve-manager" : "approve-committee";
            String boundary = "MANAGER".equals(tier) ? "manager-sla" : "committee-sla";
            Effect.ScheduleJob job = decided.effects().stream().filter(Effect.ScheduleJob.class::isInstance)
                    .map(Effect.ScheduleJob.class::cast).filter(e -> e.nodeId().equals(boundary)).findFirst()
                    .orElseThrow();

            // When the timer fires
            Transition fired = kernel.apply(decided.state(), new Command.JobDue(job.jobId(), job.tokenId(),
                    job.jobKind()), CLOCK);

            // Then the shared end event is reached, the task stays open and the instance keeps running
            assertThat(fired.history()).anyMatch(h -> "escalated".equals(h.nodeId()));
            assertThat(flow(fired.state(), task).status()).isEqualTo(TokenStatus.WAITING_USER);
            assertThat(fired.state().status()).isEqualTo(InstanceStatus.RUNNING);

            // And when the approver completes the task, the instance completes
            Token open = flow(fired.state(), task);
            Transition done = kernel.apply(fired.state(), new Command.CompleteTask(open.activityInstanceId(),
                    open.id(), Map.of("decision", "APPROVED"), "officer.lan"), CLOCK);
            assertThat(done.state().status()).isEqualTo(InstanceStatus.COMPLETED);
        }
    }
}
