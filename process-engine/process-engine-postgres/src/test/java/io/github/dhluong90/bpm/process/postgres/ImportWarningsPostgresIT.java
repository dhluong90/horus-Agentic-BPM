package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.DefinitionRef;
import io.github.dhluong90.bpm.process.api.DefinitionVersion;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.bpmn.subset.SubsetBpmnReader;
import io.github.dhluong90.bpm.process.bpmn.subset.SubsetBpmnWriter;
import io.github.dhluong90.bpm.process.bpmn.xml.Limits;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorCatalog;
import io.github.dhluong90.bpm.process.internal.expr.V1ExpressionEvaluator;
import io.github.dhluong90.bpm.process.internal.service.EngineSettings;
import io.github.dhluong90.bpm.process.internal.service.LocalServiceInvoker;
import io.github.dhluong90.bpm.process.internal.service.ProcessServices;
import io.github.dhluong90.bpm.process.spi.CallerResolver;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A successful import says what it kept in the stored source but did not read (PD-141, #380): a vendor attribute from
 * another tool is returned as a {@code FOREIGN_EXTENSION_IGNORED} warning, and the same answer is replayed for the
 * same idempotency key.
 */
@Testcontainers
class ImportWarningsPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    private static final String WITH_VENDOR_ATTRIBUTE = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:camunda=\"http://camunda.org/schema/1.0/bpmn\" id=\"d\" targetNamespace=\"urn:t\">"
            + "<bpmn:process id=\"card-block\" isExecutable=\"true\">"
            + "<bpmn:startEvent id=\"s\" camunda:initiator=\"officer\"/><bpmn:endEvent id=\"e\"/>"
            + "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"e\"/></bpmn:process></bpmn:definitions>";

    private static ProcessServices engine;
    private static final Caller CALLER = new Caller();

    @BeforeAll
    static void migrate() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        DataSource db = ds;
        new ProcessEngineSchemaMigrator(db).migrate();
        engine = new ProcessServices(CALLER::tenant, CALLER,
                Clock.fixed(Instant.parse("2027-01-04T09:00:00Z"), ZoneOffset.UTC),
                new ProcessEngineTransactions(new DataSourceTransactionManager(db)), new JdbcProcessStore(db),
                Map.of(SourceFormat.BPMN_XML, new SubsetBpmnReader(Limits.DEFAULTS)),
                Map.of(SourceFormat.BPMN_XML, new SubsetBpmnWriter()::write),
                new LocalServiceInvoker(Map.of(), Optional.empty(), Duration.ofSeconds(5)), event -> { },
                new EngineSettings(false, 500, 1_048_576, Duration.ofDays(1), Duration.ofMillis(200)),
                BehaviorCatalog.registry(), new V1ExpressionEvaluator(100));
    }

    private static ImportRequest request(String key) {
        return new ImportRequest(SourceFormat.BPMN_XML, WITH_VENDOR_ATTRIBUTE.getBytes(StandardCharsets.UTF_8), key);
    }

    @Test
    void PRE_204_a_successful_import_lists_the_vendor_attribute_it_kept_but_did_not_read() {
        CALLER.as("bank-a", "analyst-lan");

        DefinitionVersion imported = engine.importDefinition(request("k-warn-1"));

        assertThat(imported.status()).isEqualTo("DRAFT");
        assertThat(imported.warnings()).singleElement().satisfies(w -> {
            assertThat(w.code()).isEqualTo("FOREIGN_EXTENSION_IGNORED");
            assertThat(w.element()).isEqualTo("camunda:initiator");
            assertThat(w.elementId()).isEqualTo("s");
            assertThat(w.line()).isPositive();
        });
        assertThat(engine.importDefinition(request("k-warn-1")).warnings()).isEqualTo(imported.warnings());
    }

    @Test
    void PRE_204_reading_the_version_again_has_no_warnings_because_only_the_import_answer_carries_them() {
        CALLER.as("bank-a", "analyst-lan");
        DefinitionVersion imported = engine.importDefinition(request(null));

        DefinitionVersion read = engine.find(DefinitionRef.of(imported.key(), imported.version())).orElseThrow();

        assertThat(imported.warnings()).extracting(DefinitionProblem::code).containsExactly("FOREIGN_EXTENSION_IGNORED");
        assertThat(read.warnings()).isEmpty();
    }

    /** The authenticated context of the test. */
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
            return Set.of("processes:read", "processes:deploy");
        }

        @Override
        public Set<String> currentRoles() {
            return Set.of();
        }
    }
}
