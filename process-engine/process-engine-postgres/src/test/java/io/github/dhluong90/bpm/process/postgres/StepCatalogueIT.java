package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.PermissionDeniedException;
import io.github.dhluong90.bpm.process.api.StepCatalogueView;
import io.github.dhluong90.bpm.process.api.WorkingCalendar;
import io.github.dhluong90.bpm.process.internal.service.Calendars;
import io.github.dhluong90.bpm.process.internal.service.DecisionOutcome;
import io.github.dhluong90.bpm.process.internal.service.DecisionPort;
import io.github.dhluong90.bpm.process.internal.service.DecisionSignature;
import io.github.dhluong90.bpm.process.internal.service.StepCatalogues;
import io.github.dhluong90.bpm.process.spi.AdapterCall;
import io.github.dhluong90.bpm.process.spi.AdapterResult;
import io.github.dhluong90.bpm.process.spi.AgentAdapter;
import io.github.dhluong90.bpm.process.spi.AgentInvocation;
import io.github.dhluong90.bpm.process.spi.HandlerResult;
import io.github.dhluong90.bpm.process.spi.RoleCatalogue;
import io.github.dhluong90.bpm.process.spi.ServiceAdapter;
import io.github.dhluong90.bpm.process.spi.ServiceCall;
import io.github.dhluong90.bpm.process.spi.ServiceTaskHandler;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * PRE-032 on PostgreSQL: the step catalogue lists what the host registered and nothing else. The host of
 * {@code bank-a} registers the remote handler {@code kyc.verifyDocuments} (compensable), the local handler
 * {@code cardProfiles.load}, the agent adapter {@code doc-assessor}, the rule sets {@code card-fee} and
 * {@code agent-output-guardrails}, the calendar {@code vn-banking-hours} and three roles. {@code bank-b} has its own
 * calendar {@code sg-hours} and its own rule set {@code sg-fee}.
 */
class StepCatalogueIT {

    private static final Instant NOW = Instant.parse("2027-01-04T03:00:00Z");
    private static final String[] AUTHOR = {"processes:author", "processes:read"};
    private final TestClock clock = TestClock.at(NOW);
    private TestEngine engine;
    private StepCatalogues catalogue;

    @BeforeEach
    void setUp() {
        TestEngine.reset();
        engine = TestEngine.replica("pod-1", clock).calendarApproval(Calendars.Approval.SINGLE).build();
        calendar("bank-a", "vn-banking-hours", "Asia/Ho_Chi_Minh");
        calendar("bank-b", "sg-hours", "Asia/Singapore");
        catalogue = new StepCatalogues(() -> engine.identity.tenant, engine.identity, clock,
                new io.github.dhluong90.bpm.process.postgres.ProcessEngineTransactions(
                        new DataSourceTransactionManager(TestEngine.dataSource())),
                new JdbcCalendarStore(TestEngine.dataSource()),
                List.of(new LocalHandler()), List.of(new RemoteHandler()), List.of(new DocAssessor()),
                Optional.of(new Rules()), new BankRoles());
    }

    private void calendar(String tenant, String key, String zone) {
        engine.identity.as(tenant, "kim", "processes:calendar:manage", "processes:read");
        EnumMap<DayOfWeek, List<WorkingCalendar.Hours>> week = new EnumMap<>(DayOfWeek.class);
        week.put(DayOfWeek.MONDAY, List.of(WorkingCalendar.Hours.parse("08:00-17:00")));
        engine.calendars.propose(key, new WorkingCalendar(ZoneId.of(zone), week, new TreeMap<>()), 0, "Calendar");
    }

    @Test
    void PRE_032_step_catalogue_lists_registered_steps_only() {
        // Given carol holds processes:author in bank-a
        engine.identity.as("bank-a", "carol", AUTHOR);

        // When she reads the catalogue
        StepCatalogueView view = catalogue.stepCatalogue();

        // Then the handlers with mode, input schema and compensable, the agent, the rule sets, the calendar and roles
        assertThat(view.handlers()).extracting(StepCatalogueView.Handler::name, StepCatalogueView.Handler::mode,
                StepCatalogueView.Handler::compensable).containsExactly(
                        org.assertj.core.groups.Tuple.tuple("cardProfiles.load", "local", false),
                        org.assertj.core.groups.Tuple.tuple("kyc.verifyDocuments", "remote", true));
        assertThat(view.handlers().get(1).inputSchema()).containsEntry("type", "object");
        assertThat(view.agents()).extracting(StepCatalogueView.Agent::name).containsExactly("doc-assessor");
        // And card-fee is a decision rule set only; agent-output-guardrails is a decision and a guardrail rule set
        assertThat(view.decisionRuleSets()).extracting(StepCatalogueView.RuleSet::name)
                .containsExactly("agent-output-guardrails", "card-fee");
        assertThat(view.guardrailRuleSets()).extracting(StepCatalogueView.RuleSet::name)
                .containsExactly("agent-output-guardrails");
        assertThat(view.decisionRuleSets().get(1).outputs()).containsEntry("fee", "integer");
        assertThat(view.calendars()).containsExactly("vn-banking-hours");
        assertThat(view.roles()).containsExactly("card-officer", "credit-officer", "ops");

        // And erin with only processes:read is refused
        engine.identity.as("bank-a", "erin", "processes:read");
        assertThatThrownBy(catalogue::stepCatalogue).isInstanceOf(PermissionDeniedException.class)
                .hasMessageContaining("processes:author");

        // And a bank-b author sees bank-b's calendar and rule set, never bank-a's, and nothing is written
        int before = TestEngine.jdbc().sql("SELECT count(*) FROM pre_calendar").query(Integer.class).single();
        engine.identity.as("bank-b", "carol", AUTHOR);
        StepCatalogueView other = catalogue.stepCatalogue();
        assertThat(other.calendars()).containsExactly("sg-hours");
        assertThat(other.decisionRuleSets()).extracting(StepCatalogueView.RuleSet::name).containsExactly("sg-fee");
        assertThat(other.roles()).containsExactly("sg-officer");
        assertThat(TestEngine.jdbc().sql("SELECT count(*) FROM pre_calendar").query(Integer.class).single())
                .isEqualTo(before);
    }

    private static final class LocalHandler implements ServiceTaskHandler {
        @Override
        public String name() {
            return "cardProfiles.load";
        }

        @Override
        public HandlerResult handle(ServiceCall call) {
            throw new UnsupportedOperationException("the catalogue never calls a handler");
        }
    }

    private static final class RemoteHandler implements ServiceAdapter {
        @Override
        public String name() {
            return "kyc.verifyDocuments";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of("type", "object", "required", List.of("customerId"),
                    "properties", Map.of("customerId", Map.of("type", "string")));
        }

        @Override
        public boolean compensable() {
            return true;
        }

        @Override
        public AdapterResult execute(AdapterCall call) {
            throw new UnsupportedOperationException("the catalogue never calls an adapter");
        }
    }

    private static final class DocAssessor implements AgentAdapter {
        @Override
        public String name() {
            return "doc-assessor";
        }

        @Override
        public AdapterResult execute(AgentInvocation invocation) {
            throw new UnsupportedOperationException("the catalogue never calls an agent");
        }
    }

    private static final class BankRoles implements RoleCatalogue {
        @Override
        public boolean exists(String tenantId, String role) {
            return roles(tenantId).contains(role);
        }

        @Override
        public List<String> roles(String tenantId) {
            return tenantId.equals("bank-a") ? List.of("ops", "credit-officer", "card-officer")
                    : List.of("sg-officer");
        }
    }

    /** Two rule sets in bank-a and one in bank-b; evaluation is never called. */
    private static final class Rules implements DecisionPort {
        @Override
        public DecisionOutcome evaluate(String tenantId, io.github.dhluong90.bpm.process.internal.service.DecisionCaller caller,
                String decisionRef, Map<String, Object> inputs, Instant evaluationTime, String idempotencyKey,
                String correlationId) {
            throw new UnsupportedOperationException("the catalogue never evaluates a rule set");
        }

        @Override
        public List<String> ruleSets(String tenantId, Instant at) {
            return tenantId.equals("bank-a") ? List.of("card-fee", "agent-output-guardrails") : List.of("sg-fee");
        }

        @Override
        public DecisionSignature signature(String tenantId, String decisionRef, Instant at) {
            return switch (decisionRef) {
                case "card-fee" -> new DecisionSignature.Found("card-fee", 3, "FIRST", Map.of("tier", "string"),
                        Map.of("fee", "integer"), true);
                case "agent-output-guardrails" -> new DecisionSignature.Found(decisionRef, 2, "FIRST",
                        Map.of("score", "integer"), Map.of("allow", "boolean", "reasons", "list<string>"), true);
                case "sg-fee" -> new DecisionSignature.Found("sg-fee", 1, "UNIQUE", Map.of(), Map.of("fee", "integer"),
                        true);
                default -> new DecisionSignature.Unavailable("RULE_SET_NOT_FOUND", decisionRef);
            };
        }
    }
}
