package io.github.dhluong90.bpm.process.bpmn.subset;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.ValidationFailedException;
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
import io.github.dhluong90.bpm.process.internal.json.CanonicalProcessWriter;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The transaction, cancel and compensation elements of #488 on the reader, the compiler and the kernel, in memory. A
 * fund transfer of 25,000,000 VND debits the source inside a transaction whose compensation boundary names the credit
 * back as its compensation activity; a cancel end event inside it hands the flow to the cancel boundary once the undo
 * is done.
 */
class TransactionCompensationTest {

    private static final String INSTANCE = "01JD0000000000000000000488";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-23T03:00:00Z"), ZoneOffset.UTC);
    private static final String HEAD = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:horus="urn:horus-bpm:bpmn:extensions:v1" id="d" targetNamespace="urn:t"
                              expressionLanguage="urn:horus-bpm:expr:v1">
              <bpmn:process id="fund-transfer" isExecutable="true">
            """;
    private static final String TAIL = "</bpmn:process></bpmn:definitions>";
    private static final String TRANSFER = HEAD + """
                <bpmn:startEvent id="start"/>
                <bpmn:transaction id="transfer">
                  <bpmn:startEvent id="transfer-start"/>
                  <bpmn:serviceTask id="debit-source" horus:handler="core-banking" horus:mode="remote"/>
                  <bpmn:boundaryEvent id="debit-undo" attachedToRef="debit-source" cancelActivity="false">
                    <bpmn:compensateEventDefinition/>
                  </bpmn:boundaryEvent>
                  <bpmn:serviceTask id="credit-back-activity" isForCompensation="true" horus:handler="credit-back"
                                    horus:mode="remote"/>
                  <bpmn:association id="debit-undo-link" associationDirection="One" sourceRef="debit-undo"
                                    targetRef="credit-back-activity"/>
                  <bpmn:endEvent id="transfer-cancel"><bpmn:cancelEventDefinition/></bpmn:endEvent>
                  <bpmn:sequenceFlow id="t1" sourceRef="transfer-start" targetRef="debit-source"/>
                  <bpmn:sequenceFlow id="t2" sourceRef="debit-source" targetRef="transfer-cancel"/>
                </bpmn:transaction>
                <bpmn:boundaryEvent id="transfer-cancelled" attachedToRef="transfer">
                  <bpmn:cancelEventDefinition/>
                </bpmn:boundaryEvent>
                <bpmn:serviceTask id="notify-customer" horus:handler="notify-customer"/>
                <bpmn:endEvent id="cancelled-end"/>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="transfer"/>
                <bpmn:sequenceFlow id="f2" sourceRef="transfer" targetRef="end"/>
                <bpmn:sequenceFlow id="f3" sourceRef="transfer-cancelled" targetRef="notify-customer"/>
                <bpmn:sequenceFlow id="f4" sourceRef="notify-customer" targetRef="cancelled-end"/>
            """ + TAIL;

    private final SubsetBpmnReader reader = new SubsetBpmnReader(Limits.DEFAULTS);

    private Process read(String xml) {
        return reader.read(xml.getBytes(StandardCharsets.UTF_8));
    }

    private static DefaultExecutionKernel kernel(Process process) {
        CompiledProcess compiled = new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1", process);
        return new DefaultExecutionKernel(compiled, BehaviorCatalog.registry(), new V1ExpressionEvaluator(100));
    }

    private static Token scope(InstanceState state, String nodeId) {
        return state.tokens().stream().filter(t -> t.nodeId().equals(nodeId) && t.kind() == TokenKind.SCOPE)
                .findFirst().orElseThrow();
    }

    private static List<String> problems(String xml, TransactionCompensationTest self) {
        Process process = self.read(xml);
        try {
            kernel(process);
            return List.of();
        } catch (ValidationFailedException e) {
            return e.problems().stream().map(DefinitionProblem::code).toList();
        }
    }

    @Test
    void PRE_288_the_cancel_end_waits_for_the_undo_and_the_cancel_boundary_then_takes_the_flow() {
        // Given the transfer, started and with the debit accepted
        DefaultExecutionKernel kernel = kernel(read(TRANSFER));
        Transition started = kernel.apply(InstanceState.created(INSTANCE, "bank-a", "D1", "TRF-2026-0300"),
                new Command.Start(Map.of(), "teller.hoa"), CLOCK);
        Effect.InvokeService debit = started.effects().stream().filter(Effect.InvokeService.class::isInstance)
                .map(Effect.InvokeService.class::cast).findFirst().orElseThrow();

        // When the debit completes and the flow reaches the cancel end event
        Transition cancelled = kernel.apply(started.state(), new Command.AsyncResult(debit.tokenId(),
                debit.attemptId(), new StepResult.Completed(Map.of("postingRef", "FT26327001"))), CLOCK);

        // Then the undo of the debit is on the stack with the handler of its compensation activity, not of the debit,
        // the instance is COMPENSATING and the cancel end is parked
        Token transfer = scope(cancelled.state(), "transfer");
        assertThat(cancelled.effects()).filteredOn(Effect.PushCompensation.class::isInstance).singleElement()
                .isInstanceOfSatisfying(Effect.PushCompensation.class, push -> {
                    assertThat(push.nodeId()).isEqualTo("debit-source");
                    assertThat(push.handler()).isEqualTo("credit-back");
                    assertThat(push.scopeTokenId()).isEqualTo(transfer.id());
                });
        assertThat(cancelled.effects()).contains(new Effect.StartCompensation(transfer.id(), "transfer-cancel",
                "CANCEL"));
        assertThat(cancelled.state().status()).isEqualTo(InstanceStatus.COMPENSATING);
        assertThat(cancelled.state().tokens()).filteredOn(t -> t.nodeId().equals("transfer-cancel")).singleElement()
                .extracting(Token::status).isEqualTo(TokenStatus.WAITING_EVENT);

        // When the shell has walked the stack to the bottom
        Transition resumed = kernel.apply(cancelled.state(), new Command.EndCompensation(transfer.id()), CLOCK);

        // Then the transaction scope is COMPENSATED, the instance runs again, and the cancel boundary sent the flow to
        // the customer notice, not to the normal end
        assertThat(resumed.state().status()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(scope(resumed.state(), "transfer").status()).isEqualTo(TokenStatus.COMPENSATED);
        assertThat(resumed.state().tokens()).filteredOn(t -> t.status().live()).extracting(Token::nodeId)
                .contains("notify-customer").doesNotContain("end");
        assertThat(resumed.history()).extracting(h -> h.type()).contains("COMPENSATED", "BOUNDARY_FIRED");
    }

    @Test
    void PRE_288_a_transaction_with_compensation_and_cancel_exports_and_reads_back_to_the_same_definition() {
        // When the transfer is read, exported as BPMN and read again
        Process process = read(TRANSFER);
        byte[] exported = new SubsetBpmnWriter().write(process);

        // Then the canonical form, which the version hash is made of, is equal, and it names the compensation activity
        assertThat(CanonicalProcessWriter.canonicalText(reader.read(exported)))
                .isEqualTo(CanonicalProcessWriter.canonicalText(process));
        assertThat(CanonicalProcessWriter.canonicalText(process)).contains("\"compensatedBy\":\"credit-back-activity\"")
                .contains("\"type\":\"cancelEnd\"");
        assertThat(new String(exported, StandardCharsets.UTF_8)).contains("<bpmn:transaction id=\"transfer\"")
                .contains("isForCompensation=\"true\"").contains("<bpmn:cancelEventDefinition/>");
    }

    @Test
    void PRE_288_a_compensation_boundary_without_its_activity_and_a_cancel_end_without_its_boundary_are_refused() {
        // Given the transfer without the association, and the transfer without the cancel boundary
        String noLink = TRANSFER.replaceAll("(?s)<bpmn:association.*?/>", "");
        String noBoundary = TRANSFER.replaceAll("(?s)<bpmn:boundaryEvent id=\"transfer-cancelled\".*?</bpmn:boundaryEvent>",
                "").replaceAll("<bpmn:sequenceFlow id=\"f3\"[^>]*/>", "");

        // Then each is refused by name when compiled
        assertThat(problems(noLink, this)).contains("COMPENSATION_ACTIVITY_MISSING", "COMPENSATION_ACTIVITY_UNATTACHED");
        assertThat(problems(noBoundary, this)).contains("CANCEL_BOUNDARY_REQUIRED");
    }
}
