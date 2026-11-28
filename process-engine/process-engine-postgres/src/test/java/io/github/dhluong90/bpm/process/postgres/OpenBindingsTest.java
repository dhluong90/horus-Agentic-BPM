package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.DefinitionVersion;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.InvalidStateException;
import io.github.dhluong90.bpm.process.api.MessageNotCorrelatedException;
import io.github.dhluong90.bpm.process.api.MigrationPlanRequest;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.api.ValidationFailedException;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * PRE-391 and PRE-392 on PostgreSQL: a file from another modeller whose steps lack a binding, or whose process says
 * {@code isExecutable="false"}, is stored as a draft that can be viewed and exported. Publish refuses it and names
 * what is open, under the auto-approve profile too; a complete definition still goes through four eyes unchanged.
 */
class OpenBindingsTest {

    private static final String[] PIPELINE = {"processes:deploy", "processes:read", "processes:start"};
    private static final String[] AUTHOR = {"processes:author", "processes:read", "processes:approve"};
    private static final String[] APPROVER = {"processes:approve", "processes:read"};

    private static final String NS = "xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:horus=\"urn:horus-bpm:bpmn:extensions:v1\" id=\"d\" targetNamespace=\"urn:t\"";

    /** An invoice approval from a modeller: a service step with a web service implementation and a user step with no
     *  assignee and no result variable. */
    private static String process(String executable) {
        return "<bpmn:definitions " + NS + "><bpmn:process id=\"invoice-approval\" " + executable + ">"
                + "<bpmn:startEvent id=\"s\"/>"
                + "<bpmn:serviceTask id=\"post\" name=\"Post invoice\" implementation=\"##WebService\"/>"
                + "<bpmn:userTask id=\"approve\" name=\"Approve invoice\"/>"
                + "<bpmn:endEvent id=\"e\"/>"
                + "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"post\"/>"
                + "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"post\" targetRef=\"approve\"/>"
                + "<bpmn:sequenceFlow id=\"f3\" sourceRef=\"approve\" targetRef=\"e\"/>"
                + "</bpmn:process></bpmn:definitions>";
    }

    private static DefinitionVersion importAs(TestEngine engine, String xml) {
        engine.identity.as("bank-a", "pipeline", PIPELINE);
        return engine.services.importDefinition(
                new ImportRequest(SourceFormat.BPMN_XML, xml.getBytes(StandardCharsets.UTF_8), null));
    }

    private static List<String> codes(List<DefinitionProblem> problems) {
        return problems.stream().map(DefinitionProblem::code).toList();
    }

    @Test
    void PRE_391_a_step_with_open_bindings_is_stored_as_a_draft_and_publish_names_each_one() {
        // Given a host that approves with four eyes, and an invoice process with an unbound service step and a user
        // step with no assignee and no result variable
        TestEngine.reset();
        TestEngine engine = TestEngine.replica("pod-1", TestClock.at("2027-03-27T03:00:00Z")).autoApprove(false).build();

        // When it is imported
        DefinitionVersion draft = importAs(engine, process("isExecutable=\"true\""));

        // Then it is a draft, the importer is told each open binding, and it can be exported again
        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThat(codes(draft.warnings())).contains("IMPLEMENTATION_UNBOUND", "HANDLER_MISSING", "ASSIGNEE_INVALID",
                "RESULT_VARIABLE_REQUIRED");
        engine.identity.as("bank-a", "auditor", "processes:read");
        assertThat(engine.services.exportDefinition("invoice-approval", 1, SourceFormat.BPMN_XML).document())
                .contains("invoice-approval");

        // And a request for publish is refused, naming every open binding by step, and nothing changes
        engine.identity.as("bank-a", "alice", AUTHOR);
        assertThatThrownBy(() -> engine.services.requestApproval(draft.ref(), draft.lockVersion(), null, null))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> {
                    assertThat(codes(e.problems())).contains("HANDLER_MISSING", "ASSIGNEE_INVALID",
                            "RESULT_VARIABLE_REQUIRED");
                    assertThat(e.problems()).extracting(DefinitionProblem::elementId).contains("post", "approve");
                });
        engine.identity.as("bank-a", "auditor", "processes:read");
        assertThat(engine.services.find(draft.ref()).orElseThrow().status()).isEqualTo("DRAFT");
    }

    @Test
    void PRE_391_open_bindings_keep_a_draft_a_draft_under_auto_approve_and_it_cannot_start() {
        // Given the dev profile, where an import is approved at once
        TestEngine.reset();
        TestEngine engine = TestEngine.replica("pod-1", TestClock.at("2027-03-27T03:00:00Z")).autoApprove(true).build();

        // When the same incomplete file is imported
        DefinitionVersion draft = importAs(engine, process("isExecutable=\"true\""));

        // Then it is not active, and no instance can start from it
        assertThat(draft.status()).isEqualTo("DRAFT");
        engine.identity.as("bank-a", "teller", PIPELINE);
        assertThatThrownBy(() -> engine.services.start(new StartRequest("invoice-approval", "INV-1", Map.of(), null)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void PRE_392_a_process_marked_not_executable_is_a_view_only_draft_and_publish_refuses_it() {
        // Given the dev profile and a process that says isExecutable="false" with every binding present
        TestEngine.reset();
        TestEngine engine = TestEngine.replica("pod-1", TestClock.at("2027-03-27T03:00:00Z")).autoApprove(true).build();
        String xml = "<bpmn:definitions " + NS + "><bpmn:process id=\"wfp-6\" isExecutable=\"false\">"
                + "<bpmn:startEvent id=\"s\"/><bpmn:endEvent id=\"e\"/>"
                + "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"e\"/></bpmn:process></bpmn:definitions>";

        // When it is imported
        DefinitionVersion draft = importAs(engine, xml);

        // Then it is a draft that says why, and a request for publish is refused with NOT_EXECUTABLE
        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThat(codes(draft.warnings())).contains("NOT_EXECUTABLE_VIEW_ONLY");
        engine.identity.as("bank-a", "alice", AUTHOR);
        assertThatThrownBy(() -> engine.services.requestApproval(draft.ref(), draft.lockVersion(), null, null))
                .isInstanceOfSatisfying(ValidationFailedException.class,
                        e -> assertThat(codes(e.problems())).containsExactly("NOT_EXECUTABLE"));
    }

    @Test
    void PRE_391_a_complete_definition_still_needs_a_request_and_a_second_person_to_approve() {
        // Given a process with every binding in place (the KYC onboarding sample)
        TestEngine.reset();
        TestEngine engine = TestEngine.replica("pod-1", TestClock.at("2027-03-27T03:00:00Z")).autoApprove(false).build();
        engine.identity.as("bank-a", "pipeline", PIPELINE);
        DefinitionVersion draft = engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                TestEngine.resource("/bpmn/kyc-onboarding.bpmn"), null));
        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThat(codes(draft.warnings())).doesNotContain("HANDLER_MISSING", "ASSIGNEE_INVALID");

        // When alice requests and alice tries to approve
        engine.identity.as("bank-a", "alice", AUTHOR);
        DefinitionVersion requested = engine.services.requestApproval(draft.ref(), draft.lockVersion(), null, null);

        // Then four eyes still hold: the requester cannot approve, another approver can
        assertThatThrownBy(() -> engine.services.approve(requested.ref(), requested.lockVersion(),
                requested.definitionHash(), null, null)).isInstanceOf(
                        io.github.dhluong90.bpm.process.api.SelfApprovalForbiddenException.class);
        engine.identity.as("bank-a", "bob", APPROVER);
        assertThat(engine.services.approve(requested.ref(), requested.lockVersion(), requested.definitionHash(),
                "checked", null).status()).isEqualTo("ACTIVE");
    }

    // ---- a draft with open bindings is refused on every path that could run it (PRE-391) ----------------------------

    private static final String DRAFT_STEP = "<bpmn:userTask id=\"approve\" name=\"Approve\"/>";

    /** A process of the given key with a message start event {@code payment-received} or a plain start, then a user
     *  task with no assignee and no result variable: an open binding. */
    private static String draftProcess(String key, String start, String preamble) {
        return "<bpmn:definitions " + NS + ">" + preamble + "<bpmn:process id=\"" + key + "\" isExecutable=\"true\">"
                + start + DRAFT_STEP + "<bpmn:endEvent id=\"e\"/>"
                + "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"approve\"/>"
                + "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"e\"/>"
                + "</bpmn:process></bpmn:definitions>";
    }

    private static String caller(String called, String attributes) {
        return "<bpmn:definitions " + NS + "><bpmn:process id=\"account-opening\" isExecutable=\"true\">"
                + "<bpmn:startEvent id=\"s\"/><bpmn:callActivity id=\"kyc\" calledElement=\"" + called + "\" "
                + attributes + "/><bpmn:endEvent id=\"e\"/>"
                + "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"kyc\"/>"
                + "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"kyc\" targetRef=\"e\"/></bpmn:process></bpmn:definitions>";
    }

    @Test
    void PRE_391_a_call_activity_never_starts_a_draft_with_open_bindings_by_key_or_by_pinned_version() {
        // Given the dev profile, kyc-check as a draft with an open binding, and two callers: by key and pinned to v1
        TestEngine.reset();
        TestEngine engine = TestEngine.replica("pod-1", TestClock.at("2027-03-27T03:00:00Z")).autoApprove(true).build();
        DefinitionVersion kyc = importAs(engine, draftProcess("kyc-check", "<bpmn:startEvent id=\"s\"/>", ""));
        assertThat(kyc.status()).isEqualTo("DRAFT");
        importAs(engine, caller("kyc-check", ""));
        engine.identity.as("bank-a", "teller", PIPELINE);
        engine.services.start(new StartRequest("account-opening", "AO-1", Map.of(), null));
        String pinned = caller("kyc-check", "horus:calledVersion=\"1\"").replace("account-opening", "account-opening-v1");
        importAs(engine, pinned);
        engine.identity.as("bank-a", "teller", PIPELINE);
        engine.services.start(new StartRequest("account-opening-v1", "AO-2", Map.of(), null));

        // Then no child instance of kyc-check exists and each caller has an incident NO_EFFECTIVE_VERSION
        assertThat(TestEngine.jdbc().sql("SELECT count(*) FROM pre_instance WHERE definition_key = 'kyc-check'")
                .query(Integer.class).single()).isZero();
        assertThat(TestEngine.jdbc().sql("SELECT count(*) FROM pre_incident WHERE error_code = 'NO_EFFECTIVE_VERSION'")
                .query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void PRE_391_a_message_never_starts_a_draft_with_open_bindings() {
        // Given the dev profile and a draft whose message start event is payment-received, with an open binding
        TestEngine.reset();
        TestEngine engine = TestEngine.replica("pod-1", TestClock.at("2027-03-27T03:00:00Z")).autoApprove(true).build();
        String start = "<bpmn:startEvent id=\"s\"><bpmn:messageEventDefinition messageRef=\"m\"/></bpmn:startEvent>";
        DefinitionVersion draft = importAs(engine, draftProcess("payment-intake", start,
                "<bpmn:message id=\"m\" name=\"payment-received\"/>"));
        assertThat(draft.status()).isEqualTo("DRAFT");

        // When the message arrives, then it starts nothing
        engine.identity.as("bank-a", "gateway", "processes:message:correlate", "processes:read", "processes:deploy");
        assertThatThrownBy(() -> engine.services.correlate("payment-received", "PAY-1", Map.of(), "msg-1"))
                .isInstanceOf(MessageNotCorrelatedException.class);
        assertThat(TestEngine.jdbc().sql("SELECT count(*) FROM pre_instance WHERE definition_key = 'payment-intake'")
                .query(Integer.class).single()).isZero();

        // Control: once the bindings are filled in and the version is active, the same message starts an instance
        String filled = draftProcess("payment-intake", start, "<bpmn:message id=\"m\" name=\"payment-received\"/>")
                .replace(DRAFT_STEP, "<bpmn:userTask id=\"approve\" name=\"Approve\" horus:assignee=\"role:cashier\""
                        + " horus:resultVariable=\"approval\"/>");
        assertThat(importAs(engine, filled).status()).isEqualTo("ACTIVE");
        engine.identity.as("bank-a", "gateway", "processes:message:correlate", "processes:read", "processes:deploy");
        assertThat(engine.services.correlate("payment-received", "PAY-1", Map.of(), "msg-2").outcome())
                .isEqualTo(io.github.dhluong90.bpm.process.api.MessageService.MessageDelivery.Outcome.STARTED);
    }

    @Test
    void PRE_391_a_migration_cannot_target_a_draft_with_open_bindings() {
        // Given kyc-onboarding v1 active, and a changed v2 with an open binding, which stays a draft
        TestEngine.reset();
        TestEngine engine = TestEngine.replica("pod-1", TestClock.at("2027-03-27T03:00:00Z")).autoApprove(true).build();
        importAs(engine, draftProcess("loan-review", "<bpmn:startEvent id=\"s\"/>", "")
                .replace("<bpmn:userTask id=\"approve\" name=\"Approve\"/>", "<bpmn:task id=\"approve\"/>"));
        DefinitionVersion v2 = importAs(engine, draftProcess("loan-review", "<bpmn:startEvent id=\"s\"/>", ""));
        assertThat(v2.status()).isEqualTo("DRAFT");

        // When a migration to v2 is planned, then it is refused: instances move only to an active version
        engine.identity.as("bank-a", "ops", "processes:migration:manage", "processes:read");
        assertThatThrownBy(() -> engine.migrations(io.github.dhluong90.bpm.process.internal.service.MigrationSettings
                .DEFAULTS).plan(new MigrationPlanRequest("loan-review", 1, 2, null)))
                .isInstanceOf(InvalidStateException.class).hasMessageContaining("only to an active version");
    }
}
