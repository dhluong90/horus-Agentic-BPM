package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.api.VariableIntegrityException;
import io.github.dhluong90.bpm.process.api.VariableKeyUnavailableException;
import io.github.dhluong90.bpm.process.bpmn.subset.SubsetBpmnReader;
import io.github.dhluong90.bpm.process.bpmn.xml.Limits;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorCatalog;
import io.github.dhluong90.bpm.process.internal.expr.V1ExpressionEvaluator;
import io.github.dhluong90.bpm.process.internal.service.EngineSettings;
import io.github.dhluong90.bpm.process.internal.service.LocalServiceInvoker;
import io.github.dhluong90.bpm.process.internal.service.ProcessServices;
import io.github.dhluong90.bpm.process.internal.store.VariableVault;
import io.github.dhluong90.bpm.process.spi.CallerResolver;
import io.github.dhluong90.bpm.process.spi.ProcessTracing;
import io.github.dhluong90.bpm.process.spi.VariableEncryptionRules;
import io.github.dhluong90.bpm.process.spi.VariableKeyring;
import io.github.dhluong90.bpm.process.spi.VariableKeyring.Key;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
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
 * PRE-525 to PRE-528 on a real PostgreSQL: a loan application whose amount is encrypted at rest, a key rotation with a
 * re-encryption sweep, the refusals of a lost or a changed key, and no clear value in the table, the log or a span.
 */
@Testcontainers
class VariableEncryptionPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    private static final Key K1 = key(1);
    private static final Key K2 = key(2);
    private static final VariableEncryptionRules RULES = new VariableEncryptionRules(Set.of("amount"), Map.of());
    private static final Caller CALLER = new Caller();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2027-01-04T09:00:00Z"), ZoneOffset.UTC);
    private static DataSource db;
    private static int sequence;

    private static Key key(int seed) {
        byte[] b = new byte[32];
        java.util.Arrays.fill(b, (byte) seed);
        return new Key("k" + seed, b);
    }

    private static VariableKeyring ring(Key current, Key... previous) {
        return new VariableKeyring() {
            @Override
            public Optional<Key> current() {
                return Optional.ofNullable(current);
            }

            @Override
            public List<Key> previous() {
                return List.of(previous);
            }
        };
    }

    @BeforeAll
    static void migrateAndImport() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        db = ds;
        new ProcessEngineSchemaMigrator(db).migrate();
        engine(VariableVault.NONE).importAtStartup("bank-a", ImportRequest.bpmn(bpmn("loan-origination.bpmn")));
        engine(VariableVault.NONE).importAtStartup("bank-b", ImportRequest.bpmn(bpmn("loan-origination.bpmn")));
        engine(VariableVault.NONE).importAtStartup("bank-c", ImportRequest.bpmn(bpmn("loan-origination.bpmn")));
        for (String tenant : List.of("bank-e", "bank-f", "bank-g")) {
            engine(VariableVault.NONE).importAtStartup(tenant, ImportRequest.bpmn(bpmn("loan-origination.bpmn")));
        }
    }

    private static ProcessServices engine(VariableVault vault) {
        SubsetBpmnReader reader = new SubsetBpmnReader(Limits.DEFAULTS);
        return new ProcessServices(CALLER::tenant, CALLER, CLOCK,
                new ProcessEngineTransactions(new DataSourceTransactionManager(db)),
                new JdbcProcessStore(db, Duration.ofSeconds(2), vault), Map.of(SourceFormat.BPMN_XML, reader::read),
                new LocalServiceInvoker(Map.of(), Optional.empty(), Duration.ofSeconds(5)), event -> { },
                new EngineSettings(true, 500, 1_048_576, Duration.ofDays(1), Duration.ofMillis(200)),
                BehaviorCatalog.registry(), new V1ExpressionEvaluator(100));
    }

    private static ProcessInstance startLoan(ProcessServices engine, long amountVnd) {
        String n = "LN-2027-" + (++sequence);
        return engine.start(StartRequest.of("loan-origination").withBusinessKey(n)
                .withVariables(Map.of("amount", Money.ofMinor("VND", amountVnd), "channel", "BRANCH"))
                .withIdempotencyKey("k-" + n));
    }

    private static String stored(String id) {
        return JdbcClient.create(db).sql("SELECT variables::text FROM pre_instance WHERE id = ?")
                .param(id).query(String.class).single();
    }

    @Test
    void PRE_525_the_amount_is_ciphertext_in_the_table_and_whole_again_when_read() {
        CALLER.as("bank-a");
        ProcessServices engine = engine(new VariableVault(ring(K1), RULES));

        ProcessInstance started = startLoan(engine, 150_000_000);

        String row = stored(started.id());
        assertThat(row).contains("$enc", "\"k1\"", "BRANCH").doesNotContain("150000000", "VND");
        assertThat(engine.instance(started.id()).variables().get("amount")).isEqualTo(Money.ofMinor("VND", 150_000_000));
        assertThat(engine.instance(started.id()).openTasks()).extracting(ProcessInstance.Task::nodeId)
                .containsExactly("senior-approval");
    }

    @Test
    void PRE_525_no_table_of_the_engine_holds_the_amount_after_a_start_a_claim_and_a_completion() {
        CALLER.as("bank-a");
        ProcessServices engine = engine(new VariableVault(ring(K1), RULES));
        String n = "LN-2027-ALL-" + (++sequence);
        ProcessInstance started = engine.start(StartRequest.of("loan-origination").withBusinessKey(n)
                .withVariables(Map.of("amount", Money.ofMinor("VND", 987_654_321))).withIdempotencyKey("k-" + n));
        ProcessInstance.Task task = started.openTasks().get(0);
        long claimed = engine.claim(task.id(), task.lockVersion(), "k-claim-" + n).lockVersion();
        engine.complete(task.id(), Map.of("approved", true), claimed, "k-complete-" + n);

        JdbcClient jdbc = JdbcClient.create(db);
        for (String table : jdbc.sql("SELECT tablename FROM pg_tables WHERE schemaname = current_schema() AND "
                + "tablename LIKE 'pre\\_%'").query(String.class).list()) {
            assertThat(jdbc.sql("SELECT count(*) FROM " + table + " t WHERE t::text LIKE '%987654321%'")
                    .query(Long.class).single()).as(table).isZero();
        }
    }

    @Test
    void PRE_526_an_idempotent_replay_after_a_rotation_returns_the_first_answer_whole() {
        CALLER.as("bank-a");
        StartRequest request = StartRequest.of("loan-origination").withBusinessKey("LN-2027-REPLAY")
                .withVariables(Map.of("amount", Money.ofMinor("VND", 150_000_000))).withIdempotencyKey("k-replay");
        ProcessInstance first = engine(new VariableVault(ring(K1), RULES)).start(request);

        ProcessInstance replay = engine(new VariableVault(ring(K2, K1), RULES)).start(request);

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.variables()).isEqualTo(first.variables());
        assertThat(replay.variables().get("amount")).isEqualTo(Money.ofMinor("VND", 150_000_000));
        // and once k1 is gone the stored answer is refused by name, never shown as ciphertext
        assertThatThrownBy(() -> engine(new VariableVault(ring(K2), RULES)).start(request))
                .isInstanceOf(VariableKeyUnavailableException.class);
    }

    @Test
    void PRE_525_a_row_moved_to_another_tenant_is_refused() {
        CALLER.as("bank-a");
        ProcessServices engine = engine(new VariableVault(ring(K1), RULES));
        ProcessInstance started = startLoan(engine, 150_000_000);
        String row = stored(started.id());

        assertThatThrownBy(() -> new VariableVault(ring(K1), RULES).open("bank-b", started.id(), row))
                .isInstanceOf(VariableIntegrityException.class);
    }

    @Test
    void PRE_526_a_rotation_locks_nobody_out_and_the_instance_takes_the_same_path_after_it() {
        CALLER.as("bank-a");
        ProcessInstance started = startLoan(engine(new VariableVault(ring(K1), RULES)), 150_000_000);
        assertThat(stored(started.id())).contains("\"k1\"");

        // k2 is current and k1 still verifies: the task opens, completes, and the rewrite is under k2
        ProcessServices rotated = engine(new VariableVault(ring(K2, K1), RULES));
        ProcessInstance read = rotated.instance(started.id());
        assertThat(read.variables().get("amount")).isEqualTo(Money.ofMinor("VND", 150_000_000));
        ProcessInstance.Task task = read.openTasks().get(0);
        long claimed = rotated.claim(task.id(), task.lockVersion(), "k-claim-" + started.id()).lockVersion();
        ProcessInstance done = rotated.complete(task.id(), Map.of("approved", true), claimed,
                "k-complete-" + started.id());
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(stored(started.id())).contains("\"k2\"").doesNotContain("\"k1\"", "150000000");
        assertThat(done.variables().get("amount")).isEqualTo(Money.ofMinor("VND", 150_000_000));
    }

    @Test
    void PRE_526_the_sweep_rewrites_a_waiting_instance_under_the_current_key_without_changing_its_version() {
        CALLER.as("bank-c");
        ProcessInstance started = startLoan(engine(new VariableVault(ring(K1), RULES)), 20_000_000);
        long version = started.lockVersion();
        VariableVault rotated = new VariableVault(ring(K2, K1), RULES);
        JdbcVariableReencryption sweep = new JdbcVariableReencryption(db, rotated);

        JdbcVariableReencryption.Page page = sweep.reencrypt("bank-c", null, 500);
        assertThat(page.rewritten()).isPositive();
        assertThat(page.refused()).isZero();
        assertThat(sweep.reencrypt("bank-c", null, 500).rewritten()).isZero();

        assertThat(stored(started.id())).contains("\"k2\"").doesNotContain("\"k1\"");
        // k1 is dropped: nothing is locked out
        ProcessServices withoutOld = engine(new VariableVault(ring(K2), RULES));
        ProcessInstance read = withoutOld.instance(started.id());
        assertThat(read.variables().get("amount")).isEqualTo(Money.ofMinor("VND", 20_000_000));
        assertThat(read.lockVersion()).isEqualTo(version);
        assertThat(read.openTasks()).extracting(ProcessInstance.Task::nodeId).containsExactly("auto-approve");
    }

    @Test
    void PRE_526_a_sweep_of_one_tenant_touches_no_row_of_another() {
        CALLER.as("bank-a");
        startLoan(engine(new VariableVault(ring(K1), RULES)), 20_000_000);

        JdbcVariableReencryption.Page page = new JdbcVariableReencryption(db, new VariableVault(ring(K2, K1), RULES))
                .reencrypt("bank-b", null, 500);

        assertThat(page.scanned()).isZero();
    }

    @Test
    void PRE_529_removing_a_rule_never_stores_the_encrypted_variable_in_the_clear() {
        CALLER.as("bank-a");
        ProcessInstance started = startLoan(engine(new VariableVault(ring(K1), RULES)), 150_000_000);
        String before = stored(started.id());
        // the same keys, but the configuration no longer names amount
        ProcessServices released = engine(new VariableVault(ring(K1), VariableEncryptionRules.NONE));
        ProcessInstance.Task task = released.instance(started.id()).openTasks().get(0);
        long claimed = released.claim(task.id(), task.lockVersion(), "k-claim-rm").lockVersion();

        assertThatThrownBy(() -> released.complete(task.id(), Map.of("approved", true), claimed, "k-complete-rm"))
                .isInstanceOf(io.github.dhluong90.bpm.process.api.VariableEncryptionRuleRemovedException.class)
                .hasMessageContaining("amount").hasMessageNotContaining("150000000")
                .extracting(e -> ((io.github.dhluong90.bpm.process.api.ProcessException) e).code())
                .isEqualTo("VARIABLE_ENCRYPTION_RULE_REMOVED");

        // nothing changed: the row still holds the envelope, and naming the variable again lets the work go on
        assertThat(stored(started.id())).isEqualTo(before).contains("$enc").doesNotContain("150000000");
        ProcessServices again = engine(new VariableVault(ring(K1), RULES));
        ProcessInstance.Task retry = again.instance(started.id()).openTasks().get(0);
        assertThat(again.complete(retry.id(), Map.of("approved", true), retry.lockVersion(), "k-complete-rm2"))
                .isNotNull();
        assertThat(stored(started.id())).contains("$enc").doesNotContain("150000000");
    }

    @Test
    void PRE_526_the_sweep_runs_in_a_transaction_of_its_own_and_holds_its_row_locks_until_it_has_written() {
        CALLER.as("bank-g");
        ProcessInstance started = startLoan(engine(new VariableVault(ring(K1), RULES)), 20_000_000);
        List<Boolean> writerBlocked = new ArrayList<>();
        // the keyring is asked for the old key while the sweep holds the row: a concurrent writer must wait for it
        VariableKeyring spying = new VariableKeyring() {
            @Override
            public Optional<Key> current() {
                return Optional.of(K2);
            }

            @Override
            public List<Key> previous() {
                return List.of(K1);
            }

            @Override
            public Optional<Key> find(String id) {
                writerBlocked.add(writerWaits(started.id()));
                return VariableKeyring.super.find(id);
            }
        };

        JdbcVariableReencryption.Page page = new JdbcVariableReencryption(db, new VariableVault(spying, RULES))
                .reencrypt("bank-g", null, 500);

        assertThat(page.rewritten()).isEqualTo(1);
        assertThat(page.contended()).isZero();
        assertThat(writerBlocked).isNotEmpty().containsOnly(true);
        assertThat(stored(started.id())).contains("\"k2\"").doesNotContain("\"k1\"");
    }

    /** A second connection tries to write the instance row with a short lock timeout; true when the row was locked. */
    private static boolean writerWaits(String instanceId) {
        try (java.sql.Connection c = db.getConnection(); java.sql.Statement st = c.createStatement()) {
            st.execute("SET lock_timeout = '300ms'");
            st.executeUpdate("UPDATE pre_instance SET seq = seq WHERE id = '" + instanceId + "'");
            return false;
        } catch (java.sql.SQLException e) {
            return "55P03".equals(e.getSQLState());
        }
    }

    @Test
    void PRE_526_a_second_sweeper_of_the_same_tenant_is_told_it_is_busy_and_touches_nothing() throws Exception {
        CALLER.as("bank-e");
        ProcessInstance started = startLoan(engine(new VariableVault(ring(K1), RULES)), 20_000_000);
        String before = stored(started.id());
        try (java.sql.Connection other = db.getConnection(); java.sql.Statement st = other.createStatement()) {
            other.setAutoCommit(false);
            // the first sweeper: holds the tenant's advisory lock inside its own transaction
            st.execute("SELECT pg_advisory_xact_lock(hashtextextended('horus-pre-variable-reencryption/bank-e', 0))");

            JdbcVariableReencryption.Page page = new JdbcVariableReencryption(db,
                    new VariableVault(ring(K2, K1), RULES)).reencrypt("bank-e", null, 500);

            assertThat(page.busy()).isTrue();
            assertThat(page.scanned()).isZero();
            assertThat(stored(started.id())).isEqualTo(before);
            other.rollback();
        }
        // once the first has finished, the second proceeds
        assertThat(new JdbcVariableReencryption(db, new VariableVault(ring(K2, K1), RULES))
                .reencrypt("bank-e", null, 500).rewritten()).isEqualTo(1);
    }

    @Test
    void PRE_526_a_sweep_resumes_from_its_cursor_and_a_row_a_writer_holds_is_left_for_the_next_run() throws Exception {
        CALLER.as("bank-f");
        ProcessInstance first = startLoan(engine(new VariableVault(ring(K1), RULES)), 20_000_000);
        ProcessInstance second = startLoan(engine(new VariableVault(ring(K1), RULES)), 21_000_000);
        VariableVault rotated = new VariableVault(ring(K2, K1), RULES);
        JdbcVariableReencryption sweep = new JdbcVariableReencryption(db, rotated);
        List<String> ids = new ArrayList<>(List.of(first.id(), second.id()));
        java.util.Collections.sort(ids);

        // a writer holds the first row: the sweep skips it and carries on with the second
        try (java.sql.Connection writer = db.getConnection(); java.sql.Statement st = writer.createStatement()) {
            writer.setAutoCommit(false);
            st.execute("SELECT 1 FROM pre_instance WHERE id = '" + ids.get(0) + "' FOR UPDATE");
            JdbcVariableReencryption.Page page = sweep.reencrypt("bank-f", null, 500);
            assertThat(page.rewritten()).isEqualTo(1);
            assertThat(stored(ids.get(0))).contains("\"k1\"");
            assertThat(stored(ids.get(1))).contains("\"k2\"");
            writer.rollback();
        }
        // a page of one resumes from where the last stopped, and a finished sweep rewrites nothing more
        JdbcVariableReencryption.Page one = sweep.reencrypt("bank-f", null, 1);
        assertThat(one.scanned()).isEqualTo(1);
        assertThat(one.lastId()).isEqualTo(ids.get(0));
        assertThat(one.rewritten()).isEqualTo(1);
        JdbcVariableReencryption.Page next = sweep.reencrypt("bank-f", one.lastId(), 1);
        assertThat(next.scanned()).isEqualTo(1);
        assertThat(next.rewritten()).isZero();
        assertThat(sweep.reencrypt("bank-f", null, 500).rewritten()).isZero();
    }

    @Test
    void PRE_527_a_lost_key_refuses_the_read_with_a_named_code_and_the_row_is_untouched() {
        CALLER.as("bank-a");
        ProcessInstance started = startLoan(engine(new VariableVault(ring(K1), RULES)), 150_000_000);
        String before = stored(started.id());
        ProcessServices lost = engine(new VariableVault(ring(K2), RULES));

        assertThatThrownBy(() -> lost.instance(started.id())).isInstanceOf(VariableKeyUnavailableException.class)
                .hasMessageContaining("'k1'").extracting(e -> ((VariableKeyUnavailableException) e).code())
                .isEqualTo("VARIABLE_KEY_UNAVAILABLE");
        assertThat(stored(started.id())).isEqualTo(before);
        // the list shows the instance without its variables, so one lost key stops no page
        assertThat(lost.instances(io.github.dhluong90.bpm.process.api.InstanceQuery.running()).items()).isNotEmpty();
    }

    @Test
    void PRE_527_a_start_with_a_protected_variable_and_no_current_key_stores_nothing() {
        CALLER.as("bank-a");
        ProcessServices keyless = engine(new VariableVault(ring(null), RULES));
        long before = JdbcClient.create(db).sql("SELECT count(*) FROM pre_instance").query(Long.class).single();

        assertThatThrownBy(() -> startLoan(keyless, 150_000_000)).isInstanceOf(VariableKeyUnavailableException.class);

        assertThat(JdbcClient.create(db).sql("SELECT count(*) FROM pre_instance").query(Long.class).single())
                .isEqualTo(before);
    }

    @Test
    void PRE_527_a_changed_ciphertext_is_refused_with_a_named_code() {
        CALLER.as("bank-a");
        ProcessServices engine = engine(new VariableVault(ring(K1), RULES));
        ProcessInstance started = startLoan(engine, 150_000_000);
        JdbcClient.create(db).sql("UPDATE pre_instance SET variables = jsonb_set(variables, '{amount,ct}', "
                + "to_jsonb(substr(variables#>>'{amount,ct}', 2) || 'A')) WHERE id = ?").param(started.id()).update();

        assertThatThrownBy(() -> engine.instance(started.id())).isInstanceOf(VariableIntegrityException.class)
                .extracting(e -> ((VariableIntegrityException) e).code()).isEqualTo("VARIABLE_INTEGRITY_FAILED");
    }

    @Test
    void PRE_528_no_log_line_and_no_span_call_holds_the_amount_or_a_key() {
        CALLER.as("bank-a");
        List<String> seen = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                seen.add(r.getMessage() + java.util.Arrays.toString(r.getParameters()));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
        root.addHandler(handler);
        try {
            ProcessServices engine = engine(new VariableVault(ring(K1), RULES));
            engine.useTracing((tenant, definitionKey, version, kind) -> {
                seen.add(tenant + definitionKey + version + kind);
                return (outcome, code, failure) -> seen.add(outcome + code + (failure == null ? "" : failure.getClass()));
            });
            ProcessInstance started = startLoan(engine, 150_000_000);
            ProcessServices lost = engine(new VariableVault(ring(K2), RULES));
            assertThatThrownBy(() -> lost.instance(started.id())).isInstanceOf(VariableKeyUnavailableException.class);
            new JdbcVariableReencryption(db, new VariableVault(ring(K2), RULES)).reencrypt("bank-a", null, 500);
        } finally {
            root.removeHandler(handler);
        }

        assertThat(seen).isNotEmpty().noneMatch(s -> s.contains("150000000") || s.contains("AQEBAQEB"));
        assertThat(seen).anyMatch(s -> s.contains("Variable re-encryption"));
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private static byte[] bpmn(String name) {
        try (InputStream in = VariableEncryptionPostgresIT.class.getResourceAsStream("/bpmn/" + name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Caller implements CallerResolver {
        private volatile String tenant;

        void as(String tenantId) {
            this.tenant = tenantId;
        }

        String tenant() {
            return tenant;
        }

        @Override
        public String currentCallerId() {
            return "officer-lan";
        }

        @Override
        public Set<String> currentPermissions() {
            return Set.of("processes:start", "processes:read", "processes:task:work", "processes:deploy");
        }

        @Override
        public Set<String> currentRoles() {
            return Set.of("CREDIT_OFFICER", "SENIOR_CREDIT_OFFICER");
        }
    }
}
