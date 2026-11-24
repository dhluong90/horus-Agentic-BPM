package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.DefinitionIntegrityException;
import io.github.dhluong90.bpm.process.api.DefinitionRef;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.spi.FormRegistry;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A stored definition is run only when its source still gives the recorded hashes (PRE-048, ADR-0026): a row whose
 * source was changed in the database, with or without its source hash rewritten to match, is refused and nothing
 * starts.
 */
class DefinitionIntegrityPostgresIT {

    private final TestClock clock = TestClock.at("2027-03-01T02:00:00Z");
    private TestEngine engine;
    private JdbcClient jdbc;

    private static FormRegistry registry() {
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of(
                "decision", Map.of("enum", List.of("APPROVE", "REJECT")),
                "reason", Map.of("type", "string"),
                "idNumber", Map.of("type", "string", "mask", true)));
        return (tenant, reference) -> "forms/approve.schema.json".equals(reference)
                ? Optional.of(new FormRegistry.FormDefinition(schema, Map.of()))
                : Optional.empty();
    }

    @BeforeEach
    void setUp() {
        TestEngine.reset();
        engine = TestEngine.replica("pod-1", clock).forms(registry()).build();
        jdbc = TestEngine.jdbc();
        engine.identity.as("bank-a", "pipeline", "processes:deploy", "processes:read", "processes:start");
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                TestEngine.resource("/bpmn/loan-review-form.bpmn"), null));
    }

    @AfterEach
    void stop() {
        engine.close();
    }

    private long instances() {
        return jdbc.sql("SELECT count(*) FROM pre_instance").query(Long.class).single();
    }

    @Test
    void PRE_048_a_source_swapped_with_a_matching_source_hash_is_refused_and_nothing_starts() {
        // the approver's reviewed credit step is replaced by another one, and the row's source hash is made to match
        jdbc.sql("UPDATE pre_definition SET source_document = replace(source_document, 'Credit review', "
                + "'Skip credit review'), source_hash = encode(sha256(convert_to(replace(source_document, "
                + "'Credit review', 'Skip credit review'), 'UTF8')), 'hex') WHERE tenant_id = 'bank-a'").update();

        assertThatThrownBy(() -> engine.services.start(new StartRequest("loan-review-form", "APP-1", Map.of(), null)))
                .isInstanceOfSatisfying(DefinitionIntegrityException.class,
                        e -> assertThat(e.code()).isEqualTo("DEFINITION_INTEGRITY_FAILED"))
                .hasMessageContaining("loan-review-form v1")
                .hasMessageContaining("definition hash");
        assertThat(instances()).as("the changed process never runs").isZero();
        assertThatThrownBy(() -> engine.services.sourceDocument("loan-review-form", 1))
                .isInstanceOf(DefinitionIntegrityException.class);
    }

    @Test
    void PRE_048_a_source_changed_without_its_hash_is_refused_on_start_export_and_diff() {
        jdbc.sql("UPDATE pre_definition SET source_document = source_document || ' ' WHERE tenant_id = 'bank-a'")
                .update();

        assertThatThrownBy(() -> engine.services.start(new StartRequest("loan-review-form", "APP-2", Map.of(), null)))
                .isInstanceOf(DefinitionIntegrityException.class).hasMessageContaining("source hash");
        assertThatThrownBy(() -> engine.services.exportDefinition("loan-review-form", 1, SourceFormat.BPMN_XML))
                .isInstanceOf(DefinitionIntegrityException.class);
        assertThatThrownBy(() -> engine.services.diff(DefinitionRef.of("loan-review-form", 1),
                DefinitionRef.of("loan-review-form", 1))).isInstanceOf(DefinitionIntegrityException.class);
        assertThat(instances()).isZero();
    }

    @Test
    void PRE_048_the_refusal_writes_one_security_log_entry_with_tenant_key_version_and_no_content() {
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger(
                "io.github.dhluong90.bpm.process.internal.service.ProcessServices");
        List<java.util.logging.LogRecord> records = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.logging.Handler capture = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(capture);
        java.util.logging.Level before = logger.getLevel();
        logger.setLevel(java.util.logging.Level.ALL);
        try {
            jdbc.sql("UPDATE pre_definition SET source_document = replace(source_document, 'Credit review', "
                    + "'Skip credit review') WHERE tenant_id = 'bank-a'").update();

            assertThatThrownBy(() -> engine.services.start(new StartRequest("loan-review-form", "APP-4", Map.of(),
                    null))).isInstanceOf(DefinitionIntegrityException.class);
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(before);
        }

        assertThat(records).hasSize(1);
        java.util.logging.LogRecord entry = records.get(0);
        String line = new java.text.MessageFormat(entry.getMessage()).format(entry.getParameters());
        assertThat(entry.getLevel()).isEqualTo(java.util.logging.Level.SEVERE);
        assertThat(line).startsWith("process.definition.integrity-failed tenant=bank-a key=loan-review-form "
                + "version=1: ");
        assertThat(line).doesNotContain("Skip credit review").doesNotContain("Credit review")
                .doesNotContain("<bpmn").doesNotContain("<?xml");
        assertThat(java.util.Arrays.stream(entry.getParameters()).map(String::valueOf).toList())
                .allSatisfy(p -> assertThat(p.length()).isLessThan(200));
    }

    @Test
    void PRE_048_an_untouched_definition_still_starts() {
        assertThat(engine.services.start(new StartRequest("loan-review-form", "APP-3", Map.of(), null)).status())
                .isEqualTo("RUNNING");
    }
}
