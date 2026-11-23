package io.github.dhluong90.bpm.process.bpmn.subset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.ValidationFailedException;
import io.github.dhluong90.bpm.process.bpmn.xml.Limits;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorCatalog;
import io.github.dhluong90.bpm.process.internal.exec.ProcessCompiler;
import io.github.dhluong90.bpm.process.internal.json.CanonicalProcessWriter;
import io.github.dhluong90.bpm.process.internal.model.bpmn.MessageFlow;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.model.bpmn.UserTask;
import io.github.dhluong90.bpm.process.internal.model.horus.TaskAssignment;
import io.github.dhluong90.bpm.process.internal.service.DefinitionReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** PRE-296 to PRE-299: pools become definitions, lanes and message flows are kept (#497). */
class CollaborationImportTest {

    private final SubsetBpmnReader reader = new SubsetBpmnReader(Limits.DEFAULTS);

    private static byte[] example() throws IOException {
        try (InputStream in = CollaborationImportTest.class.getResourceAsStream("/bpmn/order-payment-pools.bpmn")) {
            return in.readAllBytes();
        }
    }

    @Test
    void PRE_296_each_executable_pool_is_a_definition_and_the_black_box_is_not() throws IOException {
        DefinitionReader.Result result = reader.readWithWarnings(example());

        assertThat(result.processes()).extracting(Process::id).containsExactly("order-desk", "payment-desk");
        for (Process process : result.processes()) {
            new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1", process);
            assertThat(process.collaboration().participants()).extracting(p -> p.id())
                    .containsExactly("pool-order", "pool-payment", "pool-auditor");
        }
        // a stored collaboration is read back for the one definition asked for
        assertThat(reader.readKey(example(), "payment-desk").id()).isEqualTo("payment-desk");
    }

    @Test
    void PRE_297_a_lane_names_the_group_of_a_user_task_with_no_assignee_and_never_overrides_one() throws IOException {
        Process desk = reader.readKey(example(), "payment-desk");

        UserTask confirm = (UserTask) desk.flowElements().stream().filter(e -> e.id().equals("confirm-payment"))
                .findFirst().orElseThrow();
        assertThat(confirm.info().extensions().first(TaskAssignment.class).orElseThrow().assignee())
                .isEqualTo("group:back-office");
        assertThat(desk.laneSets()).singleElement().satisfies(set -> assertThat(set.lanes())
                .extracting(l -> l.name()).containsExactly("Back office", "Treasury"));

        String own = new String(example(), StandardCharsets.UTF_8).replace(
                "horus:resultVariable=\"confirmation\"", "horus:resultVariable=\"confirmation\" horus:assignee=\"role:cashier\"");
        UserTask kept = (UserTask) reader.readKey(own.getBytes(StandardCharsets.UTF_8), "payment-desk").flowElements()
                .stream().filter(e -> e.id().equals("confirm-payment")).findFirst().orElseThrow();
        assertThat(kept.info().extensions().first(TaskAssignment.class).orElseThrow().assignee())
                .isEqualTo("role:cashier");
    }

    @Test
    void PRE_297_a_user_task_with_no_assignee_and_no_lane_is_still_refused() {
        String file = new String(readExample(), StandardCharsets.UTF_8).replace("<bpmn:laneSet id=\"lanes\">",
                "<bpmn:laneSet id=\"lanes\"><bpmn:lane id=\"x\" name=\"x\"/>").replace(
                "<bpmn:flowNodeRef>confirm-payment</bpmn:flowNodeRef>", "");

        assertThatThrownBy(() -> reader.read(file.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.problems())
                        .extracting(DefinitionProblem::code).contains("ASSIGNEE_INVALID"));
    }

    @Test
    void PRE_298_message_flows_are_kept_with_their_pools_and_the_correlation_of_the_catching_step()
            throws IOException {
        Process order = reader.readKey(example(), "order-desk");

        assertThat(order.collaboration().messageFlows()).extracting(MessageFlow::id)
                .containsExactly("mf-request", "mf-received", "mf-audit");
        MessageFlow received = order.collaboration().messageFlows().get(1);
        assertThat(received.messageRef()).isEqualTo("payment-received");
        assertThat(received.info().otherAttributes()).containsEntry(SubsetBpmnReader.TARGET_POOL, "pool-order")
                .containsEntry(SubsetBpmnReader.CORRELATION_KEY, "orderId");
        assertThat(order.collaboration().messageFlows().get(2).info().otherAttributes())
                .containsEntry(SubsetBpmnReader.TARGET_POOL, "pool-auditor");
    }

    @Test
    void PRE_298_export_keeps_the_pools_lanes_and_message_flows_and_reads_back_to_the_same_definition()
            throws IOException {
        for (String key : new String[] {"order-desk", "payment-desk"}) {
            Process process = reader.readKey(example(), key);

            byte[] exported = new SubsetBpmnWriter().write(process);
            Process again = reader.read(exported);

            assertThat(CanonicalProcessWriter.canonicalText(again)).isEqualTo(CanonicalProcessWriter.canonicalText(process));
            assertThat(again.collaboration().messageFlows()).extracting(MessageFlow::id)
                    .containsExactly("mf-request", "mf-received", "mf-audit");
            assertThat(again.collaboration().participants()).hasSize(3);
            assertThat(again.laneSets()).isEqualTo(process.laneSets());
        }
        String xml = new String(new SubsetBpmnWriter().write(reader.readKey(example(), "payment-desk")),
                StandardCharsets.UTF_8);
        // the other executable pool is a black box in this export, so the file stands alone
        assertThat(xml).contains("<bpmn:participant id=\"pool-order\" name=\"Order desk\"/>")
                .contains("targetRef=\"pool-order\"");
    }

    @Test
    void PRE_299_a_choreography_is_kept_as_received_with_a_warning_and_a_second_process_needs_a_collaboration() {
        String choreography = new String(readExample(), StandardCharsets.UTF_8).replace("</bpmn:definitions>",
                "<bpmn:choreography id=\"c\"/></bpmn:definitions>");

        assertThat(reader.readWithWarnings(choreography.getBytes(StandardCharsets.UTF_8)).warnings())
                .extracting(DefinitionProblem::code).contains("CHOREOGRAPHY_KEPT_AS_RECEIVED");

        String loose = new String(readExample(), StandardCharsets.UTF_8)
                .replaceAll("(?s)<bpmn:collaboration.*?</bpmn:collaboration>", "");
        assertThatThrownBy(() -> reader.read(loose.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.problems())
                        .extracting(DefinitionProblem::code).contains("PROCESS_NOT_IN_COLLABORATION"));
    }

    private static byte[] readExample() {
        try {
            return example();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
