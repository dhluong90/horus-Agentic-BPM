package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.FormNotDeclaredException;
import io.github.dhluong90.bpm.process.api.FormValidationException;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.api.TaskForm;
import io.github.dhluong90.bpm.process.api.TaskNotFoundException;
import io.github.dhluong90.bpm.process.api.ValidationFailedException;
import io.github.dhluong90.bpm.process.spi.FormRegistry;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Task forms on PostgreSQL (PRE-031, PRE-219, PRE-220): in {@code loan-review-form.bpmn} the credit officer {@code an}
 * completes the credit review through the form {@code forms/approve.schema.json} (a REJECT needs a reason), the
 * collateral check is a link to the bank's own screen, and the free note declares no form. The national id number of
 * the application is a masked field of the form.
 */
class TaskFormTest {

    private static final String[] OFFICER = {"processes:start", "processes:read", "processes:task:work",
            "processes:deploy"};
    private static final String ID_NUMBER = "079012345678";

    private final TestClock clock = TestClock.at("2027-03-01T02:00:00Z");
    private TestEngine engine;

    private static Map<String, Object> approveSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("decision", Map.of("enum", List.of("APPROVE", "REJECT")));
        properties.put("reason", Map.of("type", "string", "maxLength", 500));
        properties.put("idNumber", Map.of("type", "string", "mask", true));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", List.of("decision"));
        schema.put("properties", properties);
        schema.put("if", Map.of("required", List.of("decision"), "properties",
                Map.of("decision", Map.of("const", "REJECT"))));
        schema.put("then", Map.of("required", List.of("reason")));
        return schema;
    }

    private static FormRegistry registry(Map<String, Object> schema) {
        return (tenant, reference) -> "forms/approve.schema.json".equals(reference)
                ? Optional.of(new FormRegistry.FormDefinition(schema, Map.of("widgets", Map.of("reason", "textarea"))))
                : Optional.empty();
    }

    private void engine(Map<String, Object> schema, String... linkOrigins) {
        TestEngine.reset();
        engine = TestEngine.replica("pod-1", clock).forms(registry(schema), linkOrigins).build();
    }

    private void as(String caller) {
        engine.identity.as("bank-a", caller, OFFICER).roles = Set.of("credit-officer");
    }

    private void importFixture() {
        as("pipeline");
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                TestEngine.resource("/bpmn/loan-review-form.bpmn"), null));
    }

    private ProcessInstance.Task open(ProcessInstance instance, String nodeId) {
        return instance.tasks().stream().filter(t -> t.nodeId().equals(nodeId) && !"COMPLETED".equals(t.status()))
                .findFirst().orElseThrow();
    }

    private ProcessInstance refresh(ProcessInstance instance) {
        return engine.services.instance(instance.id());
    }

    @BeforeEach
    void setUp() {
        engine(approveSchema());
    }

    @Test
    void PRE_031_task_form_resolved_and_completion_validated() {
        importFixture();
        as("an");
        ProcessInstance started = engine.services.start(new StartRequest("loan-review-form", "LN-2027-0042",
                Map.of("idNumber", ID_NUMBER, "decision", "APPROVE"), null));
        ProcessInstance.Task approve = open(started, "approve");
        long claimed = engine.services.claim(approve.id(), approve.lockVersion(), "an-claim-1").lockVersion();

        TaskForm form = engine.services.form(approve.id());

        assertThat(form.taskId()).isEqualTo(approve.id());
        assertThat(form.lockVersion()).isEqualTo(claimed);
        assertThat(form.link()).isNull();
        assertThat(form.schema()).containsKeys("type", "required", "properties", "if", "then");
        assertThat(form.hints()).containsEntry("order", List.of("decision", "reason"))
                .containsEntry("widgets", Map.of("reason", "textarea"))
                .containsEntry("labels", Map.of("reason", Map.of("en", "Reason", "vi", "Lý do")));
        assertThat(form.data()).containsEntry("decision", "APPROVE").containsEntry("idNumber", "***");

        assertThatThrownBy(() -> engine.services.complete(approve.id(), Map.of("decision", "REJECT"), claimed,
                "an-complete-1"))
                .isInstanceOfSatisfying(FormValidationException.class, e -> {
                    assertThat(e.code()).isEqualTo("FORM_VALIDATION_FAILED");
                    assertThat(e.pointers()).containsExactly("/reason");
                });
        assertThat(open(refresh(started), "approve").status()).isEqualTo("CLAIMED");

        ProcessInstance after = engine.services.complete(approve.id(),
                Map.of("decision", "REJECT", "reason", "income not verified"), claimed, "an-complete-2");

        assertThat(after.variables().get("review")).isEqualTo(Map.of("decision", "REJECT", "reason", "income not verified"));
        assertThat(open(after, "screen").status()).isEqualTo("OPEN");
    }

    @Test
    void PRE_031_a_link_task_returns_the_link_and_a_task_without_a_form_is_not_declared() {
        importFixture();
        as("an");
        ProcessInstance started = engine.services.start(new StartRequest("loan-review-form", "LN-2027-0043", Map.of(),
                null));
        ProcessInstance.Task approve = open(started, "approve");
        long claimed = engine.services.claim(approve.id(), approve.lockVersion(), "an-claim-1").lockVersion();
        ProcessInstance atScreen = engine.services.complete(approve.id(), Map.of("decision", "APPROVE"), claimed,
                "an-complete-1");
        ProcessInstance.Task screen = open(atScreen, "screen");

        TaskForm link = engine.services.form(screen.id());

        assertThat(link.isLink()).isTrue();
        assertThat(link.link()).isEqualTo("/loans/review/" + screen.id());
        assertThat(link.schema()).isNull();
        // The host's own screen completes the task with the ordinary call; the engine checks no form for a link.
        long screenClaimed = engine.services.claim(screen.id(), screen.lockVersion(), "an-claim-2").lockVersion();
        ProcessInstance atNote = engine.services.complete(screen.id(), Map.of("anything", 1), screenClaimed,
                "an-complete-2");
        ProcessInstance.Task plain = open(atNote, "plain");
        assertThatThrownBy(() -> engine.services.form(plain.id())).isInstanceOf(FormNotDeclaredException.class);
        long plainClaimed = engine.services.claim(plain.id(), plain.lockVersion(), "an-claim-3").lockVersion();
        assertThat(engine.services.complete(plain.id(), Map.of("note", "ok"), plainClaimed, "an-complete-3").status())
                .isEqualTo("COMPLETED");
        assertThatThrownBy(() -> engine.services.form("01JNOSUCHTASK00000000000A"))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void PRE_031_another_tenant_cannot_read_the_form_of_a_task() {
        importFixture();
        as("an");
        ProcessInstance started = engine.services.start(new StartRequest("loan-review-form", "LN-2027-0044", Map.of(),
                null));
        ProcessInstance.Task approve = open(started, "approve");

        engine.identity.as("bank-b", "an", OFFICER);

        assertThatThrownBy(() -> engine.services.form(approve.id())).isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void PRE_031_a_foreign_link_is_refused_at_import_while_no_origin_is_listed() {
        as("pipeline");

        assertThatThrownBy(() -> engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                TestEngine.resource("/bpmn/loan-review-form-evil-link.bpmn"), null)))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> {
                    assertThat(e.code()).isEqualTo("VALIDATION_FAILED");
                    assertThat(e.problems()).extracting(DefinitionProblem::code).containsExactly("FORM_LINK_NOT_ALLOWED");
                });
    }

    @Test
    void PRE_219_the_same_link_is_accepted_once_its_origin_is_listed() {
        engine(approveSchema(), "https://evil.example");
        as("pipeline");

        assertThat(engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                TestEngine.resource("/bpmn/loan-review-form-evil-link.bpmn"), null)).key())
                .isEqualTo("loan-review-evil");
    }

    private ValidationFailedException importRefused(String attributes) {
        String xml = new String(TestEngine.resource("/bpmn/loan-review-form-evil-link.bpmn"), StandardCharsets.UTF_8)
                .replace("horus:formLink=\"https://evil.example/t/{taskId}\"", attributes);
        as("pipeline");
        return org.junit.jupiter.api.Assertions.assertThrows(ValidationFailedException.class,
                () -> engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                        xml.getBytes(StandardCharsets.UTF_8), null)));
    }

    @Test
    void PRE_219_an_unregistered_form_a_bad_schema_and_bad_hints_are_each_refused_with_their_code() {
        assertThat(importRefused("horus:form=\"credit-review\"").problems()).extracting(DefinitionProblem::code)
                .containsExactly("FORM_NOT_REGISTERED");

        Map<String, Object> withPattern = Map.of("type", "object", "properties",
                Map.of("decision", Map.of("type", "string", "pattern", "^A"), "reason", Map.of("type", "string")));
        engine(withPattern);
        assertThat(importRefused("horus:formSchema=\"forms/approve.schema.json\"").problems())
                .extracting(DefinitionProblem::code).containsExactly("FORM_SCHEMA_INVALID");

        engine(approveSchema());
        assertThat(importRefused("horus:formSchema=\"forms/approve.schema.json\" "
                + "horus:formHints='{\"order\":[\"ghost\"]}'").problems()).extracting(DefinitionProblem::code)
                .containsExactly("FORM_HINTS_INVALID");
    }

    @Test
    void PRE_219_a_task_declares_at_most_one_form_and_a_reference_stays_inside_the_form_folder() {
        assertThat(importRefused("horus:form=\"a\" horus:formLink=\"/x\"").problems())
                .extracting(DefinitionProblem::code).containsExactly("FORM_INVALID");
        assertThat(importRefused("horus:formSchema=\"../../etc/passwd\"").problems())
                .extracting(DefinitionProblem::code).containsExactly("FORM_INVALID");
        assertThat(importRefused("horus:formHints='{}' horus:form=\"a\"").problems())
                .extracting(DefinitionProblem::code).containsExactly("FORM_INVALID");
    }

    @Test
    void PRE_219_the_form_is_part_of_the_definition_hash_so_changing_it_needs_a_new_approval() {
        importFixture();
        String changed = new String(TestEngine.resource("/bpmn/loan-review-form.bpmn"), StandardCharsets.UTF_8)
                .replace("/loans/review/{taskId}", "/loans/collateral/{taskId}");
        as("pipeline");

        var first = engine.services.version("loan-review-form", 1);
        var second = engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                changed.getBytes(StandardCharsets.UTF_8), null));

        assertThat(second.version()).isEqualTo(2);
        assertThat(second.definitionHash()).isNotEqualTo(first.definitionHash());
    }

    @Test
    void PRE_219_request_approval_checks_the_registry_again_so_a_form_removed_since_import_blocks_it() {
        java.util.concurrent.atomic.AtomicBoolean registered = new java.util.concurrent.atomic.AtomicBoolean(true);
        FormRegistry known = registry(approveSchema());
        TestEngine.reset();
        engine = TestEngine.replica("pod-1", clock).autoApprove(false)
                .forms((tenant, reference) -> registered.get() ? known.find(tenant, reference) : Optional.empty())
                .build();
        as("pipeline");
        var draft = engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                TestEngine.resource("/bpmn/loan-review-form.bpmn"), null));
        registered.set(false);
        engine.identity.as("bank-a", "alice", "processes:author", "processes:read");

        assertThatThrownBy(() -> engine.services.requestApproval(draft.ref(), draft.lockVersion(), null, null))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.problems())
                        .extracting(DefinitionProblem::code).containsExactly("FORM_NOT_REGISTERED"));
    }
}
