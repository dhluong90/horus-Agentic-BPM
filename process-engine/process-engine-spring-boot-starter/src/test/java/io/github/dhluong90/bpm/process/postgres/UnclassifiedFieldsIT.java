package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.api.DefinitionVersion;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.UnclassifiedField;
import io.github.dhluong90.bpm.process.internal.service.AsyncSettings;
import io.github.dhluong90.bpm.process.test.StubAgentAdapter;
import io.github.dhluong90.bpm.process.test.TestClock;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PRE-205, PD-146 (#438): the agent step fields of a definition that declare no {@code mask} are listed as reminders
 * on the version and at the approval request, never block it, and are counted once at start-up. The loan document
 * assessment's agent step reads applicationId (declared mask="false") and requestedAmount (undeclared); its output
 * schema masks summary and leaves riskScore and recommendation undeclared.
 */
class UnclassifiedFieldsIT {

    private static final String ASSESSOR = "documents.assess";
    private static final String DEFINITION = "/bpmn/loan-agent-assessment.bpmn";
    private static final String[] PIPELINE = {"processes:deploy", "processes:read", "processes:author"};
    private static final AtomicInteger TENANTS = new AtomicInteger();

    private final TestClock clock = TestClock.at(Instant.parse("2027-04-05T03:00:00Z"));
    private final String tenant = "bank-unclassified-" + TENANTS.incrementAndGet();
    private final List<TestEngine> engines = new ArrayList<>();

    @BeforeEach
    void clean() {
        TestEngine.reset();
    }

    @AfterEach
    void stop() {
        engines.forEach(TestEngine::close);
    }

    private TestEngine engine(TestEngine.Builder builder) {
        TestEngine engine = builder.withRuleEngine().decisionPort(StarterDecisionPorts::over)
                .async(new AsyncSettings(AsyncSettings.DEFAULT_LEASE_MARGIN, AsyncSettings.DEFAULT_TIMEOUT,
                        AsyncSettings.DEFAULT_EXECUTOR_THREADS, Map.of()))
                .agents(StubAgentAdapter.answering(ASSESSOR, Map.of("riskScore", 10, "recommendation", "APPROVE"))).build();
        engines.add(engine);
        engine.ruleIdentity.as(tenant, "lan");
        engine.rules.versionService().importRuleSet(io.github.dhluong90.bpm.rules.api.ImportRequest.yaml(
                TestEngine.resource("/rules/document-assessment-guardrails.yaml")));
        long requested = engine.rules.versionService().requestPublish("agent-output-guardrails", 1,
                WriteOptions.none()).lockVersion();
        engine.ruleIdentity.as(tenant, "minh");
        engine.rules.versionService().approve("agent-output-guardrails", 1, new WriteOptions(null, requested, null));
        engine.ruleIdentity.as(null, null);
        engine.identity.as(tenant, "pipeline", PIPELINE).roles = Set.of();
        return engine;
    }

    private static byte[] assessment() {
        return new String(TestEngine.resource(DEFINITION), StandardCharsets.UTF_8)
                .replace("<horus:input name=\"applicationId\" value=\"${instance.applicationId}\"/>",
                        "<horus:input name=\"applicationId\" value=\"${instance.applicationId}\" mask=\"false\"/>")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> described(List<UnclassifiedField> fields) {
        return fields.stream().map(f -> f.direction() + " " + f.step() + "." + f.field()).toList();
    }

    @Test
    void PRE_205_unclassified_agent_fields_are_listed_and_the_approval_request_still_succeeds() {
        // Given the pipeline imports the assessment as a draft
        TestEngine engine = engine(TestEngine.replica("pod-1", clock).autoApprove(false));
        DefinitionVersion draft = engine.services.importDefinition(
                new ImportRequest(SourceFormat.BPMN_XML, assessment(), null));

        // When the reviewer reads it and the author requests approval
        List<UnclassifiedField> fields = engine.services.unclassifiedFields(draft.ref());
        DefinitionVersion requested = engine.services.requestApproval(draft.ref(), draft.lockVersion(), null, null);

        // Then requestedAmount, riskScore and recommendation are listed; mask="false" and "mask": true count as
        // classified; the request is not blocked
        // (output properties in the canonical order of the stored schema, by name)
        assertThat(described(fields)).containsExactly("INPUT assess-documents.requestedAmount",
                "OUTPUT assess-documents.recommendation", "OUTPUT assess-documents.riskScore");
        assertThat(fields.getFirst().message()).contains("requestedAmount", "not classified", "does not block")
                .doesNotContain("${instance");
        assertThat(requested.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(engine.services.unclassifiedFields(draft.ref())).isEqualTo(fields);
    }

    @Test
    void PRE_205_a_silenced_deployment_lists_nothing_and_logs_no_summary() {
        TestEngine engine = engine(TestEngine.replica("pod-1", clock).warnUnclassified(false));
        DefinitionVersion active = engine.services.importDefinition(
                new ImportRequest(SourceFormat.BPMN_XML, assessment(), null));

        assertThat(engine.services.unclassifiedFields(active.ref())).isEmpty();
        assertThat(engine.services.unclassifiedSummary()).isEmpty();
    }

    @Test
    void PRE_205_the_start_up_summary_counts_fields_per_active_version_without_names_or_values() {
        // Given the assessment active (dev auto-approval)
        TestEngine engine = engine(TestEngine.replica("pod-1", clock));
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML, assessment(), null));

        // When the engine starts
        String summary = engine.services.unclassifiedSummary().orElseThrow();

        // Then one line counts the version and names the setting, never a field or a value
        assertThat(summary).contains("3 agent step fields of 1 active definition versions",
                tenant + "/loan-agent-assessment v1: 3", "horus.bpm.processes.classification.warn-unclassified=false")
                .doesNotContain("requestedAmount", "riskScore");
    }
}
