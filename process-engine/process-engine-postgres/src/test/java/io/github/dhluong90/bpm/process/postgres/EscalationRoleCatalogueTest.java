package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.DefinitionVersion;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.ValidationFailedException;
import io.github.dhluong90.bpm.process.spi.RoleCatalogue;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PRE-180 variant B (#449) on PostgreSQL: the role an escalation level hands an overdue task to must be in the tenant's
 * role list when the host registers a {@code RoleCatalogue}. The credit review {@code review-application} of the
 * bank's loan process escalates to a supervisor role; the catalogue of {@code bank-a} lists {@code CREDIT_OFFICER}
 * and {@code BRANCH_SUPERVISOR}.
 */
class EscalationRoleCatalogueTest {

    private static final String[] PIPELINE = {"processes:deploy", "processes:read"};
    private static final String[] AUTHOR = {"processes:author", "processes:read"};
    private static final String[] APPROVER = {"processes:approve", "processes:read"};

    private final TestClock clock = TestClock.at("2026-12-03T08:00:00Z");
    /** The host's role list per tenant; a test changes it the way an identity provider retires a role. */
    private final Map<String, Set<String>> roles = new ConcurrentHashMap<>();
    private final RoleCatalogue catalogue = (tenant, role) -> roles.getOrDefault(tenant, Set.of()).contains(role);

    @BeforeEach
    void setUp() {
        TestEngine.reset();
        roles.put("bank-a", Set.of("CREDIT_OFFICER", "BRANCH_SUPERVISOR"));
        roles.put("bank-b", Set.of("CREDIT_OFFICER", "BRANCH_SUPERVISOR"));
    }

    private static byte[] loan(String escalatesTo) {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><bpmn:definitions "
                + "xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" xmlns:horus=\"urn:horus-bpm:bpmn:extensions:v1\" "
                + "id=\"Definitions_loan-credit-escalation\" targetNamespace=\"urn:horus-bpm:examples\" "
                + "expressionLanguage=\"urn:horus-bpm:expr:v1\"><bpmn:process id=\"loan-credit-escalation\" "
                + "name=\"Loan credit review with escalation\" isExecutable=\"true\"><bpmn:startEvent id=\"start\"/>"
                + "<bpmn:userTask id=\"review-application\" name=\"Review application\" "
                + "horus:assignee=\"role:CREDIT_OFFICER\" horus:resultVariable=\"review\" horus:sla=\"PT8H\" "
                + "horus:escalation=\"" + escalatesTo + "\"/><bpmn:endEvent id=\"end\"/>"
                + "<bpmn:sequenceFlow id=\"s1\" sourceRef=\"start\" targetRef=\"review-application\"/>"
                + "<bpmn:sequenceFlow id=\"s2\" sourceRef=\"review-application\" targetRef=\"end\"/></bpmn:process>"
                + "</bpmn:definitions>").getBytes(StandardCharsets.UTF_8);
    }

    private DefinitionVersion draft(TestEngine engine, String tenant, String escalatesTo) {
        engine.identity.as(tenant, "pipeline", PIPELINE);
        return engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML, loan(escalatesTo), null));
    }

    private DefinitionVersion request(TestEngine engine, String tenant, DefinitionVersion draft) {
        engine.identity.as(tenant, "alice", AUTHOR);
        return engine.services.requestApproval(draft.ref(), draft.lockVersion(), null, null);
    }

    private static String status(TestEngine engine, String tenant, DefinitionVersion v) {
        engine.identity.as(tenant, "auditor", "processes:read");
        return engine.services.find(v.ref()).orElseThrow().status();
    }

    private long history() {
        return TestEngine.jdbc().sql("SELECT count(*) FROM pre_definition_history").query(Long.class).single();
    }

    @Test
    void PRE_180_an_escalation_to_a_role_the_host_does_not_list_is_refused_at_request_and_at_approval() {
        // Given a catalogue listing CREDIT_OFFICER and BRANCH_SUPERVISOR, and a definition whose review-application
        // escalates to role:RISK_HEAD
        TestEngine engine = TestEngine.replica("pod-1", clock).autoApprove(false).roleCatalogue(catalogue).build();
        DefinitionVersion draft = draft(engine, "bank-a", "role:RISK_HEAD");
        long written = history();

        // When it is sent for approval
        assertThatThrownBy(() -> request(engine, "bank-a", draft))
                // Then it is 422 VALIDATION_FAILED naming the step and the role, and nothing is written
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> {
                    assertThat(e.code()).isEqualTo("VALIDATION_FAILED");
                    assertThat(e.problems()).singleElement().satisfies(p -> {
                        assertThat(p.code()).isEqualTo("ESCALATION_ROLE_UNKNOWN");
                        assertThat(p.message()).contains("review-application").contains("role:RISK_HEAD");
                        assertThat(p.elementId()).isEqualTo("review-application");
                    });
                });
        assertThat(history()).isEqualTo(written);
        assertThat(status(engine, "bank-a", draft)).isEqualTo("DRAFT");

        // And when RISK_HEAD was listed at the request but retired before the second person approves
        roles.put("bank-a", Set.of("CREDIT_OFFICER", "BRANCH_SUPERVISOR", "RISK_HEAD"));
        DefinitionVersion pending = request(engine, "bank-a", draft);
        roles.put("bank-a", Set.of("CREDIT_OFFICER", "BRANCH_SUPERVISOR"));
        long beforeApproval = history();
        engine.identity.as("bank-a", "bob", APPROVER);
        assertThatThrownBy(() -> engine.services.approve(pending.ref(), pending.lockVersion(),
                pending.definitionHash(), null, null))
                // Then the approval is the same 422, and the version stays pending
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.problems())
                        .extracting(p -> p.code() + " " + p.elementId())
                        .containsExactly("ESCALATION_ROLE_UNKNOWN review-application"));
        assertThat(history()).isEqualTo(beforeApproval);
        assertThat(status(engine, "bank-a", pending)).isEqualTo("PENDING_APPROVAL");
        engine.close();
    }

    @Test
    void PRE_180_an_escalation_to_a_listed_role_is_sent_for_approval_and_approved() {
        // Given the same definition escalating to role:BRANCH_SUPERVISOR, which the catalogue lists
        TestEngine engine = TestEngine.replica("pod-1", clock).autoApprove(false).roleCatalogue(catalogue).build();
        DefinitionVersion draft = draft(engine, "bank-a", "role:BRANCH_SUPERVISOR;role:CREDIT_OFFICER|PT4H");

        // When it is sent for approval and approved
        DefinitionVersion pending = request(engine, "bank-a", draft);
        engine.identity.as("bank-a", "bob", APPROVER);
        DefinitionVersion active = engine.services.approve(pending.ref(), pending.lockVersion(),
                pending.definitionHash(), null, null);

        // Then both succeed
        assertThat(active.status()).isEqualTo("ACTIVE");
        engine.close();
    }

    @Test
    void PRE_180_a_host_without_a_role_catalogue_checks_no_role() {
        // Given a host with no RoleCatalogue registered
        TestEngine engine = TestEngine.replica("pod-1", clock).autoApprove(false).build();
        DefinitionVersion draft = draft(engine, "bank-a", "role:RISK_HEAD");

        // When the definition escalating to role:RISK_HEAD is sent for approval
        DefinitionVersion pending = request(engine, "bank-a", draft);

        // Then roles are not checked and the request succeeds
        assertThat(pending.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(RoleCatalogue.ACCEPT_ALL.exists("bank-a", "ANYTHING")).isTrue();
        engine.close();
    }

    @Test
    void PRE_180_one_tenants_roles_never_satisfy_another_tenant() {
        // Given a catalogue in which only bank-a holds RISK_HEAD
        roles.put("bank-a", Set.of("CREDIT_OFFICER", "RISK_HEAD"));
        TestEngine engine = TestEngine.replica("pod-1", clock).autoApprove(false).roleCatalogue(catalogue).build();
        DefinitionVersion forB = draft(engine, "bank-b", "role:RISK_HEAD");
        DefinitionVersion forA = draft(engine, "bank-a", "role:RISK_HEAD");

        // When bank-b sends its definition for approval, and bank-a its own
        assertThatThrownBy(() -> request(engine, "bank-b", forB)).isInstanceOfSatisfying(
                ValidationFailedException.class,
                e -> assertThat(e.problems()).extracting(p -> p.code()).containsExactly("ESCALATION_ROLE_UNKNOWN"));

        // Then bank-b is refused and bank-a is not
        assertThat(request(engine, "bank-a", forA).status()).isEqualTo("PENDING_APPROVAL");
        engine.close();
    }
}
