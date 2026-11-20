package io.github.dhluong90.bpm.process.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.api.StepFailedException;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorCatalog;
import io.github.dhluong90.bpm.process.internal.exec.CompiledProcess;
import io.github.dhluong90.bpm.process.internal.exec.DefaultExecutionKernel;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionException;
import io.github.dhluong90.bpm.process.internal.exec.HistoryEntry;
import io.github.dhluong90.bpm.process.internal.exec.ProcessCompiler;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.exec.TokenStatus;
import io.github.dhluong90.bpm.process.internal.expr.V1ExpressionEvaluator;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.service.DecisionOutcome;
import io.github.dhluong90.bpm.process.internal.service.DecisionPort;
import io.github.dhluong90.bpm.process.internal.service.EngineSettings;
import io.github.dhluong90.bpm.process.internal.service.LocalServiceInvoker;
import io.github.dhluong90.bpm.process.internal.service.ProcessServices;
import io.github.dhluong90.bpm.process.internal.store.ProcessStore;
import io.github.dhluong90.bpm.process.spi.CallerResolver;
import io.github.dhluong90.bpm.process.spi.HandlerResult;
import io.github.dhluong90.bpm.process.spi.ServiceCall;
import io.github.dhluong90.bpm.process.spi.ServiceTaskHandler;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * QA review: exclusive gateway edge cases on the kernel, and the persistence shell's bound on synchronous work in
 * one transaction, with an in-memory store.
 */
class ProcessServicesQaTest {

    private static final String INSTANCE = "01JD0000000000000000000000";
    private final TestClock clock = TestClock.at("2027-01-04T09:00:00Z");

    // ---- exclusive gateway edge cases (PRE-004) -----------------------------------------------------------

    private ScenarioRunner gateway(String c1, String c2, String defaultFlow) {
        BpmnModelBuilder b = BpmnModelBuilder.process("gw")
                .startEvent("start").exclusiveGateway("g", defaultFlow)
                .userTask("first", "role:A", "firstResult").userTask("second", "role:A", "secondResult")
                .userTask("fallback", "role:A", "fallbackResult")
                .endEvent("end")
                .chain("start", "g")
                .flow("g__first", "g", "first", c1)
                .flow("g__second", "g", "second", c2);
        if (defaultFlow != null) {
            b.flow("g__fallback", "g", "fallback");
        } else {
            b.flow("g__fallback", "g", "fallback", "false");
        }
        b.chain("first", "end").chain("second", "end").chain("fallback", "end");
        CompiledProcess compiled = new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1", b.build());
        return ScenarioRunner.of(new DefaultExecutionKernel(compiled, BehaviorCatalog.registry(),
                new V1ExpressionEvaluator(100)), INSTANCE, "bank-a", "D1", clock);
    }

    @Test
    void PRE_004_several_true_conditions_take_only_the_first_in_document_order() {
        ScenarioRunner run = gateway("amount >= 1 VND", "amount >= 2 VND", "g__fallback")
                .start(Map.of("amount", Money.ofMinor("VND", 5)));

        run.assertTokenAt("first", TokenStatus.WAITING_USER);
        assertThat(run.openTasks()).extracting(t -> t.nodeId()).containsExactly("first");
    }

    @Test
    void PRE_004_no_true_condition_takes_the_default_flow() {
        ScenarioRunner run = gateway("amount >= 100 VND", "amount >= 200 VND", "g__fallback")
                .start(Map.of("amount", Money.ofMinor("VND", 5)));

        assertThat(run.openTasks()).extracting(t -> t.nodeId()).containsExactly("fallback");
    }

    @Test
    void PRE_004_no_true_condition_and_no_default_is_NO_PATH() {
        assertThatThrownBy(() -> gateway("amount >= 100 VND", "amount >= 200 VND", null)
                .start(Map.of("amount", Money.ofMinor("VND", 5))))
                .isInstanceOf(ExecutionException.class)
                .satisfies(e -> assertThat(((ExecutionException) e).code()).isEqualTo("NO_PATH"));
    }

    @Test
    void PRE_004_a_missing_variable_in_a_comparison_is_null_so_the_default_flow_is_taken() {
        // Decided in #154: conditions follow FEEL null logic (RLE-006); a comparison with null is null, which does
        // not hold, so the gateway takes its default flow. A type error still fails the step.
        ScenarioRunner run = gateway("amount >= 100 VND", "amount >= 200 VND", "g__fallback").start(Map.of());

        assertThat(run.openTasks()).extracting(t -> t.nodeId()).containsExactly("fallback");
    }

    @Test
    void PRE_004_an_explicit_null_test_routes_without_error() {
        ScenarioRunner run = gateway("amount == null", "amount != null", "g__fallback").start(Map.of());

        assertThat(run.openTasks()).extracting(t -> t.nodeId()).containsExactly("first");
    }

    @Test
    void PRE_004_decimal_numbers_compare_exactly_without_double() {
        ScenarioRunner run = gateway("rate > 0.1", "rate <= 0.1", "g__fallback")
                .start(Map.of("rate", new BigDecimal("0.10000000000000001")));

        assertThat(run.openTasks()).extracting(t -> t.nodeId()).containsExactly("first");
    }

    // ---- ids ----------------------------------------------------------------------------------------------

    @Test
    void two_instances_started_in_the_same_millisecond_get_different_child_ids() {
        Instant sameMillisecond = Instant.parse("2027-01-04T09:00:00.123Z");
        String first = io.github.dhluong90.bpm.process.internal.json.Ulid.next(sameMillisecond);
        String second = io.github.dhluong90.bpm.process.internal.json.Ulid.next(sameMillisecond);

        assertThat(first).isNotEqualTo(second);
        assertThat(io.github.dhluong90.bpm.process.internal.exec.Ids.child(second, 2))
                .as("first token id of %s and of %s", first, second)
                .isNotEqualTo(io.github.dhluong90.bpm.process.internal.exec.Ids.child(first, 2));
    }

    // ---- the shell bounds synchronous work in one transaction ----------------------------------------------

    @Test
    void a_cycle_through_a_local_service_task_is_stopped_by_the_step_limit_not_run_forever() {
        // Given a retry loop whose exit condition never becomes true
        Process loop = BpmnModelBuilder.process("retry-loop")
                .startEvent("start")
                .serviceTask("step", "loop.step", Map.of(), "r")
                .exclusiveGateway("g", "g__again")
                .endEvent("end")
                .chain("start", "step", "g")
                .flow("g__done", "g", "end", "r.stop == true")
                .flow("g__again", "g", "step")
                .build();
        AtomicInteger calls = new AtomicInteger();
        ServiceTaskHandler handler = new ServiceTaskHandler() {
            @Override
            public String name() {
                return "loop.step";
            }

            @Override
            public HandlerResult handle(ServiceCall call) {
                if (calls.incrementAndGet() > 50_000) {
                    throw new IllegalStateException("runaway: 50,000 handler calls in one transaction");
                }
                return HandlerResult.completed(Map.of("stop", false));
            }
        };
        ProcessServices services = services(loop, handler);
        services.importAtStartup("bank-a", new ImportRequest(SourceFormat.BPMN_XML,
                "loop".getBytes(StandardCharsets.UTF_8), null));

        // When an instance starts, Then the engine stops it with its own step limit
        assertThatThrownBy(() -> services.start(StartRequest.of("retry-loop")))
                .isInstanceOf(StepFailedException.class)
                .satisfies(e -> assertThat(((StepFailedException) e).reason()).isEqualTo("STEP_LIMIT"));
        assertThat(calls.get()).isLessThanOrEqualTo(10_000);
    }

    // ---- tenant guard and event delivery (security re-review, lows 2 and 4) ----------------------------------

    private static Process pricing() {
        return BpmnModelBuilder.process("pricing")
                .startEvent("start")
                .businessRuleTask("price", "card-fee", Map.of("tier", "GOLD"), "pricing")
                .endEvent("end")
                .chain("start", "price", "end")
                .build();
    }

    /** A decision engine stand-in: every evaluation matches with a zero fee. */
    private static DecisionPort ruleEngine(AtomicInteger evaluations) {
        return (tenantId, caller, decisionRef, inputs, evaluationTime, idempotencyKey, correlationId) -> {
            evaluations.incrementAndGet();
            return new DecisionOutcome.Matched("01JDDECISION00000000000000", Map.of("fee", Money.ofMinor("VND", 0)));
        };
    }

    // ---- a host with the Process Engine only (ADR-0027) -------------------------------------------------------

    @Test
    void a_host_with_the_process_engine_only_runs_gateway_conditions_with_no_rule_engine_class() {
        // Given a host whose class path has no Rule Engine at all
        assertThatThrownBy(() -> Class.forName("io.github.dhluong90.bpm.rules.api.RuleEngine"))
                .isInstanceOf(ClassNotFoundException.class);
        List<String> visited = new ArrayList<>();
        ServiceTaskHandler record = new ServiceTaskHandler() {
            @Override
            public String name() {
                return "review.record";
            }

            @Override
            public HandlerResult handle(ServiceCall call) {
                visited.add(call.nodeId());
                return HandlerResult.completed(Map.of());
            }
        };
        // and a card limit review: 100,000,000 VND or more for a customer who is not GOLD goes to a senior reviewer
        Process review = BpmnModelBuilder.process("card-limit-review")
                .startEvent("start")
                .exclusiveGateway("amount-check", "amount-check__standard")
                .serviceTask("senior-review", "review.record", Map.of(), "senior")
                .serviceTask("standard-review", "review.record", Map.of(), "standard")
                .endEvent("end")
                .chain("start", "amount-check")
                .flow("amount-check__senior", "amount-check", "senior-review",
                        "amount >= 100000000 VND and customer.tier != 'GOLD'")
                .flow("amount-check__standard", "amount-check", "standard-review")
                .chain("senior-review", "end")
                .chain("standard-review", "end")
                .build();
        ProcessServices services = services(review, record);

        // When the definition is activated and two instances run
        services.importAtStartup("bank-a", new ImportRequest(SourceFormat.BPMN_XML,
                "review".getBytes(StandardCharsets.UTF_8), null));
        services.start(StartRequest.of("card-limit-review").withVariables(Map.of(
                "amount", Money.ofMinor("VND", 150_000_000), "customer", Map.of("tier", "SILVER"))));
        services.start(StartRequest.of("card-limit-review").withVariables(Map.of(
                "amount", Money.ofMinor("VND", 150_000_000), "customer", Map.of("tier", "GOLD"))));

        // Then the conditions are evaluated by the shared expression library: SILVER to senior, GOLD to standard
        assertThat(visited).containsExactly("senior-review", "standard-review");
    }

    @Test
    void a_business_rule_task_without_a_rule_engine_is_refused_at_activation_naming_the_step() {
        // Given a host with no decision engine, When a definition with a business rule task is activated
        ProcessServices services = services(pricing(), () -> "bank-a", Optional.empty(), event -> { });

        // Then it is refused with BPMN_NOT_EXECUTABLE naming the step and the reason, so nothing fails at run time
        assertThatThrownBy(() -> services.importAtStartup("bank-a", new ImportRequest(SourceFormat.BPMN_XML,
                "p".getBytes(StandardCharsets.UTF_8), null)))
                .isInstanceOf(io.github.dhluong90.bpm.process.api.ValidationFailedException.class)
                .satisfies(e -> assertThat(((io.github.dhluong90.bpm.process.api.ValidationFailedException) e)
                        .problems()).singleElement().satisfies(problem -> {
                            assertThat(problem.code()).isEqualTo("BPMN_NOT_EXECUTABLE");
                            assertThat(problem.elementId()).isEqualTo("price");
                            assertThat(problem.message()).contains("price").contains("Rule Engine");
                        }));
    }

    @Test
    void a_decision_step_gets_the_engine_clock_and_a_key_from_instance_node_and_attempt() {
        List<Object[]> calls = new ArrayList<>();
        DecisionPort recording = (tenantId, caller, decisionRef, inputs, evaluationTime, idempotencyKey,
                correlationId) -> {
            calls.add(new Object[] {tenantId, caller, evaluationTime, idempotencyKey, correlationId});
            return new DecisionOutcome.Matched("01JDDECISION00000000000000", Map.of("fee", Money.ofMinor("VND", 0)));
        };
        ProcessServices services = services(pricing(), () -> "bank-a", Optional.of(recording), event -> { });
        services.importAtStartup("bank-a", new ImportRequest(SourceFormat.BPMN_XML, "p".getBytes(StandardCharsets.UTF_8), null));

        String instanceId = services.start(StartRequest.of("pricing").withIdempotencyKey("k-clock")).id();

        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call[0]).isEqualTo("bank-a");
            assertThat(call[1]).isEqualTo(new io.github.dhluong90.bpm.process.internal.service.DecisionCaller.Request(
                    "bank-a", "tuan"));
            assertThat(call[2]).isEqualTo(clock.instant());
            assertThat((String) call[3]).startsWith("pe:" + instanceId + ":price:").hasSizeLessThanOrEqualTo(200);
            assertThat(call[4]).isEqualTo(instanceId);
        });
    }

    @Test
    void a_decision_is_refused_with_TENANT_NOT_ALLOWED_when_the_caller_tenant_is_not_the_instance_tenant() {
        // The instance belongs to bank-a; by the time the businessRuleTask runs, the caller resolves to bank-b.
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger evaluations = new AtomicInteger();
        io.github.dhluong90.bpm.process.spi.TenantResolver switching = () -> calls.getAndIncrement() == 0 ? "bank-a" : "bank-b";
        ProcessServices services = services(pricing(), switching, Optional.of(ruleEngine(evaluations)), event -> { });
        services.importAtStartup("bank-a", new ImportRequest(SourceFormat.BPMN_XML, "p".getBytes(StandardCharsets.UTF_8), null));

        assertThatThrownBy(() -> services.start(StartRequest.of("pricing").withIdempotencyKey("k1")))
                .isInstanceOf(io.github.dhluong90.bpm.process.api.TenantNotAllowedException.class)
                .satisfies(e -> assertThat(((io.github.dhluong90.bpm.process.api.ProcessException) e).code())
                        .isEqualTo("TENANT_NOT_ALLOWED"));
        assertThat(evaluations.get()).as("the Rule Engine was never called").isZero();
    }

    @Test
    void a_plain_listener_that_throws_fails_the_start_so_the_transaction_rolls_back() {
        AtomicInteger delivered = new AtomicInteger();
        ProcessServices services = services(pricing(), () -> "bank-a", Optional.of(ruleEngine(new AtomicInteger())),
                event -> {
                    if (event.type().startsWith("processes.instance.")) {
                        delivered.incrementAndGet();
                        throw new IllegalStateException("listener down");
                    }
                });
        services.importAtStartup("bank-a", new ImportRequest(SourceFormat.BPMN_XML, "p".getBytes(StandardCharsets.UTF_8), null));

        // Events are handed over inside the transaction (DESIGN 6.4, #173): the exception reaches the caller, and the
        // host's transaction manager rolls the state change back with it.
        assertThatThrownBy(() -> services.start(StartRequest.of("pricing").withIdempotencyKey("k2")))
                .isInstanceOf(io.github.dhluong90.bpm.process.api.EventListenerFailedException.class)
                .satisfies(e -> assertThat(((io.github.dhluong90.bpm.process.api.ProcessException) e).code())
                        .isEqualTo("EVENT_LISTENER_FAILED"));
        assertThat(delivered.get()).isEqualTo(1);
    }

    // ---- BPMN export (PRE-034) ------------------------------------------------------------------------------

    private record ExportRig(ProcessServices services, MemoryStore store, AtomicInteger events,
            java.util.concurrent.atomic.AtomicReference<String> tenant) {
    }

    private ExportRig exportRig(Set<String> permissions) {
        MemoryStore store = new MemoryStore();
        AtomicInteger events = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> tenant = new java.util.concurrent.atomic.AtomicReference<>("bank-a");
        CallerResolver caller = new CallerResolver() {
            @Override
            public String currentCallerId() {
                return "erin";
            }

            @Override
            public Set<String> currentPermissions() {
                return permissions;
            }
        };
        Process process = pricing();
        ProcessServices services = new ProcessServices(tenant::get, caller, clock, new Supplier0(), store,
                Map.of(SourceFormat.BPMN_XML, bytes -> process),
                Map.of(SourceFormat.BPMN_XML, p -> ("<exported " + p.id() + "/>").getBytes(StandardCharsets.UTF_8)),
                new LocalServiceInvoker(Map.of(), Optional.of(ruleEngine(new AtomicInteger())), Duration.ofSeconds(5)),
                event -> events.incrementAndGet(),
                new EngineSettings(true, 500, 1_048_576, Duration.ofDays(1), Duration.ofMillis(200)),
                BehaviorCatalog.registry(), new V1ExpressionEvaluator(100));
        services.importAtStartup("bank-a", new ImportRequest(SourceFormat.BPMN_XML, "p".getBytes(StandardCharsets.UTF_8), null));
        return new ExportRig(services, store, events, tenant);
    }

    @Test
    void PRE_034_export_returns_the_version_of_the_callers_tenant_with_its_definition_hash() {
        ExportRig rig = exportRig(Set.of("processes:read"));

        io.github.dhluong90.bpm.process.api.DefinitionExport export =
                rig.services().exportDefinition("pricing", 1, SourceFormat.BPMN_XML);

        assertThat(export.document()).isEqualTo("<exported pricing/>");
        assertThat(export.definitionHash()).isEqualTo(rig.store().definitions.get(0).definitionHash());
        assertThat(export.version()).isEqualTo(1);
    }

    @Test
    void PRE_034_another_tenants_version_is_not_found_and_nothing_is_written() {
        ExportRig rig = exportRig(Set.of("processes:read"));
        int eventsBefore = rig.events().get();
        rig.tenant().set("bank-b");

        assertThatThrownBy(() -> rig.services().exportDefinition("pricing", 1, SourceFormat.BPMN_XML))
                .isInstanceOf(io.github.dhluong90.bpm.process.api.DefinitionNotFoundException.class)
                .satisfies(e -> assertThat(((io.github.dhluong90.bpm.process.api.ProcessException) e).code())
                        .isEqualTo("DEFINITION_NOT_FOUND"));
        assertThat(rig.store().definitions).hasSize(1).allSatisfy(d -> assertThat(d.tenantId()).isEqualTo("bank-a"));
        assertThat(rig.store().idempotency).isEmpty();
        assertThat(rig.events().get()).isEqualTo(eventsBefore);
    }

    @Test
    void PRE_034_export_needs_processes_read_and_a_supported_format() {
        ExportRig withoutRead = exportRig(Set.of("processes:start"));
        assertThatThrownBy(() -> withoutRead.services().exportDefinition("pricing", 1, SourceFormat.BPMN_XML))
                .isInstanceOf(io.github.dhluong90.bpm.process.api.PermissionDeniedException.class);

        ExportRig reader = exportRig(Set.of("processes:read"));
        assertThatThrownBy(() -> reader.services().exportDefinition("pricing", 1, SourceFormat.YAML))
                .isInstanceOf(io.github.dhluong90.bpm.process.api.InputInvalidException.class);
        assertThatThrownBy(() -> reader.services().exportDefinition("pricing", 2, SourceFormat.BPMN_XML))
                .isInstanceOf(io.github.dhluong90.bpm.process.api.DefinitionNotFoundException.class);
    }

    // ---- wiring ---------------------------------------------------------------------------------------------

    private ProcessServices services(Process process, io.github.dhluong90.bpm.process.spi.TenantResolver tenants,
            Optional<DecisionPort> rules, io.github.dhluong90.bpm.process.spi.ProcessEventPublisher events) {
        CallerResolver caller = new CallerResolver() {
            @Override
            public String currentCallerId() {
                return "tuan";
            }

            @Override
            public Set<String> currentPermissions() {
                return Set.of("processes:start", "processes:read", "processes:task:work", "processes:deploy");
            }
        };
        return new ProcessServices(tenants, caller, clock, new Supplier0(), new MemoryStore(),
                Map.of(SourceFormat.BPMN_XML, bytes -> process),
                new LocalServiceInvoker(Map.of(), rules, Duration.ofSeconds(5)),
                events, new EngineSettings(true, 500, 1_048_576, Duration.ofDays(1), Duration.ofMillis(200)), BehaviorCatalog.registry(),
                new V1ExpressionEvaluator(100));
    }

    private ProcessServices services(Process process, ServiceTaskHandler handler) {
        CallerResolver caller = new CallerResolver() {
            @Override
            public String currentCallerId() {
                return "tuan";
            }

            @Override
            public Set<String> currentPermissions() {
                return Set.of("processes:start", "processes:read", "processes:task:work", "processes:deploy");
            }
        };
        return new ProcessServices(() -> "bank-a", caller, clock, new Supplier0(), new MemoryStore(),
                Map.of(SourceFormat.BPMN_XML, bytes -> process),
                new LocalServiceInvoker(Map.of(handler.name(), handler), Optional.empty(), Duration.ofSeconds(5)),
                event -> { }, new EngineSettings(true, 500, 1_048_576, Duration.ofDays(1), Duration.ofMillis(200)), BehaviorCatalog.registry(),
                new V1ExpressionEvaluator(100));
    }

    /** No real transaction: the work runs directly. */
    private static final class Supplier0 implements io.github.dhluong90.bpm.process.internal.store.TransactionRunner {
        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return work.get();
        }
    }

    /** Minimal in-memory store for one tenant. */
    private static final class MemoryStore implements ProcessStore {
        private final List<StoredDefinition> definitions = new ArrayList<>();
        private final Map<String, InstanceRow> instances = new HashMap<>();
        private final Map<String, List<Token>> tokens = new HashMap<>();
        private final Map<String, TaskRow> tasks = new HashMap<>();
        private final Map<String, IdempotencyRecord> idempotency = new HashMap<>();

        @Override
        public Optional<StoredDefinition> latestDefinition(String tenant, String key) {
            return definitions.stream().filter(d -> d.key().equals(key)).reduce((a, b) -> b);
        }

        @Override
        public Optional<StoredDefinition> effectiveDefinition(String tenant, String key, Instant now) {
            return definitions.stream().filter(d -> d.key().equals(key) && d.status().equals("ACTIVE")).reduce((a, b) -> b);
        }

        @Override
        public Optional<StoredDefinition> definition(String tenant, String id) {
            return definitions.stream().filter(d -> d.id().equals(id)).findFirst();
        }

        @Override
        public Optional<StoredDefinition> definitionVersion(String tenant, String key, int version) {
            return definitions.stream().filter(d -> d.tenantId().equals(tenant) && d.key().equals(key)
                    && d.version() == version).findFirst();
        }

        @Override
        public List<io.github.dhluong90.bpm.process.api.DefinitionSummary> definitionKeys(String tenant, String afterKey,
                int limit, Instant now) {
            return List.of();
        }

        @Override
        public void insertDefinition(StoredDefinition definition) {
            definitions.add(definition);
        }

        @Override
        public List<StoredDefinition> lockDefinitions(String tenant, String key) {
            return definitions.stream().filter(d -> d.key().equals(key)).toList();
        }

        @Override
        public int updateDefinition(StoredDefinition updated) {
            definitions.replaceAll(d -> d.id().equals(updated.id()) ? updated : d);
            return 1;
        }

        @Override
        public void appendDefinitionHistory(DefinitionHistoryRow row) {
        }

        @Override
        public List<DefinitionHistoryRow> definitionHistory(String tenant, String definitionId) {
            return List.of();
        }

        @Override
        public boolean insertInstance(InstanceRow row) {
            instances.put(row.id(), row);
            return true;
        }

        @Override
        public Optional<InstanceRow> lockInstance(String tenant, String id) {
            return Optional.ofNullable(instances.get(id));
        }

        @Override
        public Optional<InstanceRow> findInstance(String tenant, String id) {
            return Optional.ofNullable(instances.get(id));
        }

        @Override
        public Optional<InstanceRow> findInstanceByBusinessKey(String tenant, String key, String businessKey) {
            return instances.values().stream().filter(i -> businessKey.equals(i.businessKey())).findFirst();
        }

        @Override
        public List<InstanceRow> instances(String tenant, List<String> statuses, String definitionKey, Instant from,
                Instant to, Instant afterStartedAt, String afterId, int limit) {
            return List.of();
        }

        @Override
        public void updateInstance(InstanceRow row) {
            instances.put(row.id(), row);
        }

        @Override
        public List<Token> tokens(String tenant, String instanceId) {
            return tokens.getOrDefault(instanceId, List.of());
        }

        @Override
        public void saveTokens(String tenant, String instanceId, String definitionId, List<Token> list, Instant now) {
            tokens.put(instanceId, List.copyOf(list));
        }

        @Override
        public void insertTask(TaskRow task) {
            tasks.put(task.id(), task);
        }

        @Override
        public Optional<TaskRow> findTask(String tenant, String id) {
            return Optional.ofNullable(tasks.get(id));
        }

        @Override
        public int completeTask(String tenant, String id, long expectedLockVersion, String completedBy, Instant at) {
            return 1;
        }

        @Override
        public int claimTask(String tenant, String id, long expectedLockVersion, String claimant, Instant at) {
            return 1;
        }

        @Override
        public int unclaimTask(String tenant, String id, long expectedLockVersion, String claimant) {
            return 1;
        }

        @Override
        public void cancelTask(String tenant, String id) {
        }

        @Override
        public List<TaskRow> tasks(String tenant, String instanceId) {
            return tasks.values().stream().filter(t -> t.instanceId().equals(instanceId)).toList();
        }

        @Override
        public void appendHistory(String tenant, String instanceId, String definitionId, List<HistoryEntry> entries) {
        }

        @Override
        public void insertIncident(ProcessStore.IncidentRow incident) {
        }

        @Override
        public List<ProcessStore.IncidentRow> openIncidents(String tenant, String instanceId) {
            return List.of();
        }

        @Override
        public java.util.Set<String> historyActors(String tenant, String instanceId, String eventType) {
            return java.util.Set.of();
        }

        @Override
        public Optional<String> reviewedJob(String tenant, String instanceId, String taskId) {
            return Optional.empty();
        }

        @Override
        public Optional<IdempotencyRecord> idempotency(String tenant, String operation, String key, Instant now) {
            return Optional.ofNullable(idempotency.get(operation + "/" + key));
        }

        @Override
        public boolean claimIdempotency(String tenant, String operation, String key, String requestHash,
                Instant expiresAt, Instant now) {
            return idempotency.putIfAbsent(operation + "/" + key,
                    new IdempotencyRecord(tenant, operation, key, requestHash, null, expiresAt)) == null;
        }

        @Override
        public byte[] engineKey(String name, byte[] candidate, Instant now) {
            return candidate;
        }

        @Override
        public void completeIdempotency(String tenant, String operation, String key, String responseJson) {
            IdempotencyRecord r = idempotency.get(operation + "/" + key);
            idempotency.put(operation + "/" + key, new IdempotencyRecord(tenant, operation, key, r.requestHash(),
                    responseJson, r.expiresAt()));
        }
    }
}
