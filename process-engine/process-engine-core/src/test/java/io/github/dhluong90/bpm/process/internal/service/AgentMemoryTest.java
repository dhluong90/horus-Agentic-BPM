package io.github.dhluong90.bpm.process.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ElementInfo;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ExtensionBag;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowElement;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SequenceFlow;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ServiceTask;
import io.github.dhluong90.bpm.process.internal.model.bpmn.StartEvent;
import io.github.dhluong90.bpm.process.internal.model.horus.AgentTaskSpec;
import io.github.dhluong90.bpm.process.internal.model.horus.HandlerRef;
import io.github.dhluong90.bpm.process.internal.model.horus.HorusExtension;
import io.github.dhluong90.bpm.process.internal.model.horus.MemoryEntry;
import io.github.dhluong90.bpm.process.spi.InputMasker;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Bounded memory of an agent step (#101), as pure functions: the import check and the resolution from recorded results.
 * In {@code kyc-onboarding}, {@code extract-id} reads the identity document, {@code screen-sanctions} screens the
 * applicant, and {@code assess-risk} receives the applicant's name and date of birth and the number of hits.
 */
class AgentMemoryTest {

    private static final String EXTRACT_SCHEMA = "{\"type\":\"object\",\"properties\":{"
            + "\"fullName\":{\"type\":\"string\"},\"dob\":{\"type\":\"string\"},"
            + "\"idNumber\":{\"type\":\"string\",\"mask\":true},\"address\":{\"type\":\"string\"},"
            + "\"document\":{\"type\":\"object\",\"mask\":true,\"properties\":{\"issuer\":{\"type\":\"string\"}}}}}";
    private static final String SCREEN_SCHEMA = "{\"type\":\"object\",\"properties\":{"
            + "\"hits\":{\"type\":\"integer\"},\"listVersion\":{\"type\":\"string\"}}}";
    private static final InputMasker MASKER = (tenant, plain) -> "hmac:k1:" + tenant + ":" + plain.length();

    private static ServiceTask agent(String id, String schema, HorusExtension... more) {
        List<HorusExtension> ext = new ArrayList<>();
        ext.add(new AgentTaskSpec("kyc." + id, schema, null));
        ext.addAll(List.of(more));
        return new ServiceTask(id, null, List.of(), List.of(), null, null, null,
                new ElementInfo(List.of(), new ExtensionBag(ext, List.of()), Map.of()));
    }

    private static MemoryEntry applicant(String... fields) {
        return new MemoryEntry("extract-id", List.of(fields), "applicant", true, false);
    }

    private static final MemoryEntry SCREENING = new MemoryEntry("screen-sanctions", List.of("hits"), "screening",
            true, false);

    /** start, extract-id, screen-sanctions, assess-risk, in a chain; plus a local step on a side branch. */
    private static Process kyc(MemoryEntry... memory) {
        List<FlowElement> elements = new ArrayList<>();
        elements.add(new StartEvent("start", null, List.of(), List.of(), List.of(), List.of(), null, false, true,
                ElementInfo.empty()));
        elements.add(agent("extract-id", EXTRACT_SCHEMA));
        elements.add(agent("screen-sanctions", SCREEN_SCHEMA));
        elements.add(agent("assess-risk", "{\"type\":\"object\"}", memory));
        elements.add(agent("notify-branch", SCREEN_SCHEMA));
        elements.add(new ServiceTask("load-profile", null, List.of(), List.of(), null, null, null,
                new ElementInfo(List.of(), new ExtensionBag(List.of(new HandlerRef("profiles.load", null, null)),
                        List.of()), Map.of())));
        String[][] flows = {{"start", "load-profile"}, {"load-profile", "extract-id"},
            {"extract-id", "screen-sanctions"}, {"screen-sanctions", "assess-risk"}, {"extract-id", "notify-branch"}};
        for (String[] f : flows) {
            elements.add(new SequenceFlow(f[0] + "__" + f[1], null, f[0], f[1], null, null, null));
        }
        return new Process("kyc-onboarding", null, null, true, false, elements, List.of(), List.of(), null);
    }

    private static FlowNode node(Process process, String id) {
        return process.flowElements().stream().filter(e -> e.id().equals(id)).map(FlowNode.class::cast).findFirst()
                .orElseThrow();
    }

    private static Map<String, Map<String, Object>> recorded() {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("fullName", "Nguyen Van A");
        identity.put("dob", "1990-04-12");
        identity.put("idNumber", "079090001234");
        identity.put("address", "12 Le Loi, Q1");
        identity.put("document", Map.of("issuer", "Ho Chi Minh City police"));
        return Map.of("extract-id", identity, "screen-sanctions", Map.of("hits", 0L, "listVersion", "2026-11-15"));
    }

    private static AgentMemory.Resolution resolve(Process process, Map<String, Map<String, Object>> recorded,
            AgentMemoryLimits limits) {
        return AgentMemory.resolve("bank-a", node(process, "assess-risk"), id -> Optional.of(node(process, id)),
                recorded, limits, MASKER);
    }

    @Test
    void PRE_100_resolution_keeps_only_declared_fields_and_masks_the_audit_copy() {
        // Given assess-risk reads fullName and dob of extract-id, and hits of screen-sanctions
        Process process = kyc(applicant("fullName", "dob"), SCREENING);

        // When the memory is resolved twice from the same recorded results
        AgentMemory.Resolution first = resolve(process, recorded(), AgentMemoryLimits.DEFAULTS);
        AgentMemory.Resolution second = resolve(process, recorded(), AgentMemoryLimits.DEFAULTS);

        // Then the agent gets exactly three fields, the same both times, and nothing else of either result
        assertThat(first).isEqualTo(second).isInstanceOfSatisfying(AgentMemory.Resolution.Resolved.class, r -> {
            assertThat(r.memory()).isEqualTo(Map.of("applicant", Map.of("fullName", "Nguyen Van A",
                    "dob", "1990-04-12"), "screening", Map.of("hits", 0L)));
            assertThat(r.audit()).isEqualTo(r.memory());
            assertThat(r.missing()).isEmpty();
            assertThat(r.memory().toString()).doesNotContain("Le Loi", "079090001234", "2026-11-15");
        });
        assertThat(AgentMemory.sources(process)).containsExactlyInAnyOrder("extract-id", "screen-sanctions");
    }

    @Test
    void PRE_102_include_masked_sends_the_plain_value_and_the_audit_copy_keeps_only_the_fingerprint() {
        // Given the entry lists idNumber and the nested issuer of a masked object, with includeMasked
        Process process = kyc(new MemoryEntry("extract-id", List.of("fullName", "idNumber", "document.issuer"),
                "applicant", true, true));

        // When it is resolved
        AgentMemory.Resolution.Resolved resolved = (AgentMemory.Resolution.Resolved) resolve(process, recorded(),
                AgentMemoryLimits.DEFAULTS);

        // Then the agent gets the plain values, nested as declared, and history only the masker's tokens
        assertThat(resolved.memory()).isEqualTo(Map.of("applicant", Map.of("fullName", "Nguyen Van A",
                "idNumber", "079090001234", "document", Map.of("issuer", "Ho Chi Minh City police"))));
        assertThat(resolved.audit()).isEqualTo(Map.of("applicant", Map.of("fullName", "Nguyen Van A",
                "idNumber", "hmac:k1:bank-a:12", "document", Map.of("issuer", "hmac:k1:bank-a:23"))));
    }

    @Test
    void PRE_101_twenty_kib_against_the_default_limit_is_too_large() {
        // Given the applicant's recorded name is 20 KiB long
        Map<String, Map<String, Object>> recorded = new LinkedHashMap<>(recorded());
        recorded.put("extract-id", Map.of("fullName", "N".repeat(20 * 1024), "dob", "1990-04-12"));

        // When the memory is resolved against the default 16 KiB
        AgentMemory.Resolution resolution = resolve(kyc(applicant("fullName", "dob"), SCREENING), recorded,
                AgentMemoryLimits.DEFAULTS);

        // Then the step fails with AGENT_MEMORY_TOO_LARGE, and the summary carries numbers, never the value
        assertThat(resolution).isInstanceOfSatisfying(AgentMemory.Resolution.Failed.class, f -> {
            assertThat(f.code()).isEqualTo("AGENT_MEMORY_TOO_LARGE");
            assertThat(f.summary()).contains("16384").doesNotContain("NNNN");
        });
    }

    @Test
    void PRE_101_a_missing_required_entry_fails_and_a_missing_optional_one_is_listed() {
        Map<String, Map<String, Object>> withoutScreening = Map.of("extract-id", recorded().get("extract-id"));

        assertThat(resolve(kyc(applicant("fullName"), SCREENING), withoutScreening, AgentMemoryLimits.DEFAULTS))
                .isEqualTo(new AgentMemory.Resolution.Failed("AGENT_MEMORY_MISSING", "screening", "Memory entry "
                        + "screening needs the recorded result of step screen-sanctions, which this instance does not "
                        + "have"));
        MemoryEntry optional = new MemoryEntry("screen-sanctions", List.of("hits"), "screening", false, false);
        assertThat(resolve(kyc(applicant("fullName"), optional), withoutScreening, AgentMemoryLimits.DEFAULTS))
                .isInstanceOfSatisfying(AgentMemory.Resolution.Resolved.class, r -> {
                    assertThat(r.memory()).isEqualTo(Map.of("applicant", Map.of("fullName", "Nguyen Van A")));
                    assertThat(r.missing()).containsExactly("screening");
                });
    }

    @Test
    void PRE_101_the_import_check_names_the_step_the_entry_and_the_problem() {
        // Given (a) an unknown step, (b) a step on another branch, a local step, an undeclared field, and 17 entries
        List<DefinitionProblem> problems = AgentMemory.check(kyc(
                new MemoryEntry("extract-idd", List.of("fullName"), "a", true, false),
                new MemoryEntry("notify-branch", List.of("hits"), "b", true, false),
                new MemoryEntry("load-profile", List.of("tier"), "c", true, false),
                new MemoryEntry("extract-id", List.of("nationality"), "d", true, false),
                new MemoryEntry("assess-risk", List.of("x"), "e", true, false)), AgentMemoryLimits.DEFAULTS);
        List<MemoryEntry> many = new ArrayList<>();
        for (int i = 1; i <= 17; i++) {
            many.add(new MemoryEntry("extract-id", List.of("fullName"), "a" + i, true, false));
        }

        // Then each is one AGENT_MEMORY_INVALID problem on assess-risk, and 17 entries break the host's bound
        assertThat(problems).allSatisfy(p -> {
            assertThat(p.code()).isEqualTo("AGENT_MEMORY_INVALID");
            assertThat(p.elementId()).isEqualTo("assess-risk");
        }).extracting(DefinitionProblem::message).containsExactly(
                "memory entry a reads step extract-idd, which this process does not have",
                "memory entry b reads step notify-branch, which is not upstream of this step",
                "memory entry c reads step load-profile, which is not an agent step with an output schema",
                "memory entry d: the output schema of extract-id does not declare field nationality",
                "memory entry e reads step assess-risk, which is not upstream of this step");
        assertThat(AgentMemory.check(kyc(many.toArray(MemoryEntry[]::new)), AgentMemoryLimits.DEFAULTS))
                .extracting(DefinitionProblem::message).containsExactly(
                        "17 memory entries, at most 16 (agents.memory.max-entries)");
        assertThat(AgentMemory.check(kyc(applicant("fullName"), SCREENING), new AgentMemoryLimits(1, 1024)))
                .extracting(DefinitionProblem::message).containsExactly(
                        "2 memory entries, at most 1 (agents.memory.max-entries)");
        assertThat(AgentMemory.check(kyc(applicant("fullName", "dob"), SCREENING), AgentMemoryLimits.DEFAULTS))
                .isEmpty();
    }

    @Test
    void PRE_102_a_masked_field_without_include_masked_is_refused_by_the_import_check() {
        assertThat(AgentMemory.check(kyc(applicant("fullName", "idNumber", "document.issuer")),
                AgentMemoryLimits.DEFAULTS)).extracting(DefinitionProblem::code, DefinitionProblem::message)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("AGENT_MEMORY_MASKED_FIELD", "memory entry applicant: field "
                                + "idNumber of extract-id is masked (mask: true); list it only with "
                                + "includeMasked=\"true\", which the approval of this definition shows"),
                        org.assertj.core.groups.Tuple.tuple("AGENT_MEMORY_MASKED_FIELD", "memory entry applicant: field "
                                + "document.issuer of extract-id is masked (mask: true); list it only with "
                                + "includeMasked=\"true\", which the approval of this definition shows"));
    }

    @Test
    void PRE_101_memory_limits_are_bounded_by_the_engine() {
        assertThat(AgentMemoryLimits.DEFAULTS).isEqualTo(new AgentMemoryLimits(16, 16_384));
        assertThatThrownBy(() -> new AgentMemoryLimits(17, 1024)).hasMessageContaining("agents.memory.max-entries");
        assertThatThrownBy(() -> new AgentMemoryLimits(1, 16_385)).hasMessageContaining("agents.memory.max-bytes");
        assertThatThrownBy(() -> new AgentMemoryLimits(-1, 1024)).hasMessageContaining("agents.memory.max-entries");
    }
}
