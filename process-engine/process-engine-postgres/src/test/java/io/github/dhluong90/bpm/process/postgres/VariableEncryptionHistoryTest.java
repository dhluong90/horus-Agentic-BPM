package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.internal.store.VariableVault;
import io.github.dhluong90.bpm.process.spi.FormRegistry;
import io.github.dhluong90.bpm.process.spi.VariableEncryptionRules;
import io.github.dhluong90.bpm.process.spi.VariableKeyring;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * PRE-528: what history keeps of a task submission when the variable it lands in is encrypted at rest. The credit
 * review of {@code loan-review-form.bpmn} stores its answer in the variable {@code review}.
 */
class VariableEncryptionHistoryTest {

    private static final String[] OFFICER = {"processes:start", "processes:read", "processes:task:work",
            "processes:deploy"};

    private static VariableVault vault(String... names) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 7);
        return new VariableVault(VariableKeyring.of(new VariableKeyring.Key("k7", key)),
                new VariableEncryptionRules(Set.of(names), Map.of()));
    }

    private static FormRegistry registry() {
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of(
                "decision", Map.of("enum", List.of("APPROVE", "REJECT")),
                "reason", Map.of("type", "string")));
        return (tenant, reference) -> Optional.of(new FormRegistry.FormDefinition(schema, Map.of()));
    }

    private String historyOfApproval(VariableVault vault) {
        TestEngine.reset();
        TestEngine engine = TestEngine.replica("pod-1", TestClock.at("2027-03-01T02:00:00Z")).forms(registry())
                .variableVault(vault).build();
        engine.identity.as("bank-a", "pipeline", OFFICER).roles = Set.of("credit-officer");
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                TestEngine.resource("/bpmn/loan-review-form.bpmn"), null));
        engine.identity.as("bank-a", "an", OFFICER).roles = Set.of("credit-officer");
        ProcessInstance started = engine.services.start(new StartRequest("loan-review-form", "LN-2027-0050",
                Map.of("channel", "BRANCH"), null));
        ProcessInstance.Task approve = started.tasks().stream().filter(t -> t.nodeId().equals("approve")).findFirst()
                .orElseThrow();
        long claimed = engine.services.claim(approve.id(), approve.lockVersion(), "an-claim").lockVersion();
        engine.services.complete(approve.id(), Map.of("decision", "REJECT", "reason", "income not verified"), claimed,
                "an-complete");
        // what is stored, and what the engine reads back from it
        lastStored = TestEngine.jdbc().sql("SELECT string_agg(details::text, ' ') FROM pre_history WHERE instance_id = ? "
                + "AND event_type = 'TASK_COMPLETED'").param(started.id()).query(String.class).single();
        return TestEngine.jdbc().sql("SELECT id, details::text FROM pre_history WHERE instance_id = ? "
                + "AND event_type = 'TASK_COMPLETED'").param(started.id())
                .query((rs, n) -> vault.openMembers("bank-a", "history/" + rs.getString(1), rs.getString(2))).list()
                .stream().collect(java.util.stream.Collectors.joining(" "));
    }

    private String lastStored;

    @Test
    void PRE_528_a_submission_that_lands_in_an_encrypted_result_variable_is_masked_in_history() {
        assertThat(historyOfApproval(vault("review"))).doesNotContain("income not verified").contains("masked");
        // PRE-525: with any variable protected the submission member is sealed too, so even the mask is not readable
        assertThat(lastStored).doesNotContain("income not verified", "masked").contains("$enc");
    }

    @Test
    void PRE_528_without_encryption_history_keeps_the_submission_as_the_form_declares_it() {
        assertThat(historyOfApproval(VariableVault.NONE)).contains("income not verified");
    }
}
