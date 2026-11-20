package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.DefinitionExport;
import io.github.dhluong90.bpm.process.api.DefinitionNotFoundException;
import io.github.dhluong90.bpm.process.api.DefinitionVersion;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.bpmn.subset.SubsetBpmnReader;
import io.github.dhluong90.bpm.process.bpmn.subset.SubsetBpmnWriter;
import io.github.dhluong90.bpm.process.bpmn.xml.Limits;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorCatalog;
import io.github.dhluong90.bpm.process.internal.expr.V1ExpressionEvaluator;
import io.github.dhluong90.bpm.process.internal.service.EngineSettings;
import io.github.dhluong90.bpm.process.internal.service.LocalServiceInvoker;
import io.github.dhluong90.bpm.process.internal.service.ProcessServices;
import io.github.dhluong90.bpm.process.spi.CallerResolver;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A loan-review diagram from a BPMN modeller, with a text annotation, its association and a group, is imported on a
 * real PostgreSQL: the stored source keeps the file byte for byte, the instance runs past the notes, and the BPMN
 * export writes them back. Another tenant reaches nothing.
 */
@Testcontainers
class AnnotatedDefinitionPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    private static final String FILE = "loan-review-annotated.bpmn";

    private static DataSource db;
    private static ProcessServices engine;
    private static DefinitionVersion imported;
    private static final Caller CALLER = new Caller();

    @BeforeAll
    static void migrateAndImport() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        db = ds;
        new ProcessEngineSchemaMigrator(db).migrate();
        SubsetBpmnReader reader = new SubsetBpmnReader(Limits.DEFAULTS);
        engine = new ProcessServices(CALLER::tenant, CALLER,
                Clock.fixed(Instant.parse("2027-01-04T09:00:00Z"), ZoneOffset.UTC),
                new ProcessEngineTransactions(new DataSourceTransactionManager(db)), new JdbcProcessStore(db),
                Map.of(SourceFormat.BPMN_XML, reader::read), Map.of(SourceFormat.BPMN_XML, new SubsetBpmnWriter()::write),
                new LocalServiceInvoker(Map.of(), Optional.empty(), Duration.ofSeconds(5)), event -> { },
                new EngineSettings(true, 500, 1_048_576, Duration.ofDays(1), Duration.ofMillis(200)),
                BehaviorCatalog.registry(), new V1ExpressionEvaluator(100));
        imported = engine.importAtStartup("bank-a", ImportRequest.bpmn(bpmn(FILE)));
    }

    @Test
    void the_stored_source_keeps_the_annotation_association_and_group_byte_for_byte() {
        String stored = JdbcClient.create(db).sql("SELECT source_document FROM pre_definition WHERE tenant_id = 'bank-a'"
                + " AND key_name = 'loan-review' AND version_num = ?").param(imported.version())
                .query(String.class).single();

        assertThat(stored).isEqualTo(new String(bpmn(FILE), StandardCharsets.UTF_8));
    }

    @Test
    void an_instance_runs_past_the_notes_to_the_credit_officer_task() {
        CALLER.as("bank-a", "officer-lan");

        ProcessInstance instance = engine.start(StartRequest.of("loan-review").withBusinessKey("LN-2027-000310")
                .withIdempotencyKey("k-annotated"));

        assertThat(instance.openTasks()).extracting(ProcessInstance.Task::nodeId).containsExactly("Activity_0r5s6t7");
    }

    @Test
    void the_bpmn_export_writes_the_notes_back_with_the_same_definition_hash() {
        CALLER.as("bank-a", "officer-lan");

        DefinitionExport export = engine.exportDefinition("loan-review", imported.version(), SourceFormat.BPMN_XML);

        assertThat(export.definitionHash()).isEqualTo(imported.definitionHash());
        assertThat(export.document())
                .contains("<bpmn:text>Loans above 150,000,000 VND also need a senior officer</bpmn:text>")
                .contains("<bpmn:association id=\"Association_0k5l6m7\" associationDirection=\"None\""
                        + " sourceRef=\"Activity_0r5s6t7\" targetRef=\"TextAnnotation_1g2h3j4\"/>")
                .contains("<bpmn:group id=\"Group_1n8p9q0\" categoryValueRef=\"CategoryValue_0r1s2t3\"/>")
                .contains("<bpmn:categoryValue id=\"CategoryValue_0r1s2t3\" value=\"Credit review\"/>");
    }

    @Test
    void another_tenant_cannot_export_the_annotated_definition() {
        CALLER.as("bank-b", "officer-minh");

        assertThatThrownBy(() -> engine.exportDefinition("loan-review", imported.version(), SourceFormat.BPMN_XML))
                .isInstanceOf(DefinitionNotFoundException.class);
    }

    private static byte[] bpmn(String name) {
        try (InputStream in = AnnotatedDefinitionPostgresIT.class.getResourceAsStream("/bpmn/" + name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The authenticated context of the test: tenant and caller, with the credit officer role. */
    private static final class Caller implements CallerResolver {
        private volatile String tenant;
        private volatile String caller;

        void as(String tenantId, String callerId) {
            this.tenant = tenantId;
            this.caller = callerId;
        }

        String tenant() {
            return tenant;
        }

        @Override
        public String currentCallerId() {
            return caller;
        }

        @Override
        public Set<String> currentPermissions() {
            return Set.of("processes:start", "processes:read", "processes:task:work", "processes:deploy");
        }

        @Override
        public Set<String> currentRoles() {
            return Set.of("CREDIT_OFFICER");
        }
    }
}
