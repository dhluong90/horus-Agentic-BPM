package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.DefinitionVersion;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.Incident;
import io.github.dhluong90.bpm.process.api.IncidentNotFoundException;
import io.github.dhluong90.bpm.process.api.InstanceNotFoundException;
import io.github.dhluong90.bpm.process.api.InvalidStateException;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.api.ValidationFailedException;
import io.github.dhluong90.bpm.process.internal.service.AsyncStepExecutor.Claim;
import io.github.dhluong90.bpm.process.internal.service.ProcessServices;
import io.github.dhluong90.bpm.process.spi.AdapterCall;
import io.github.dhluong90.bpm.process.spi.AdapterResult;
import io.github.dhluong90.bpm.process.spi.HandlerResult;
import io.github.dhluong90.bpm.process.spi.ProcessEventPublisher;
import io.github.dhluong90.bpm.process.spi.ServiceAdapter;
import io.github.dhluong90.bpm.process.spi.ServiceCall;
import io.github.dhluong90.bpm.process.spi.ServiceTaskHandler;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Saga compensation (#67, PRE-013, PRE-044, PRE-073 to PRE-076, ADR-0007) on PostgreSQL. A transfer of 25,000,000 VND
 * from account 0011-223344 to 0099-887766 holds the funds in the host's own table, debits the source in core banking
 * and credits the target; when the credit fails, the debit is credited back under the key it was posted with, then
 * the hold is released, newest first. Every undo is a {@code COMPENSATION} job keyed by its own id; a failed or
 * manual undo stops the walk with an incident that operators {@code ops.lan} and {@code ops.minh} retry or resolve.
 * The clock is a {@link TestClock}; the async lane is driven claim by claim; no test sleeps.
 */
class SagaCompensationTest {

    private static final Instant T0 = Instant.parse("2026-11-21T03:00:00Z");
    private static final long AMOUNT_VND = 25_000_000L;
    private static final String SOURCE = "0011-223344";
    private static final String TARGET = "0099-887766";
    private static final String[] OPERATOR = {"processes:incident:manage", "processes:read", "processes:cancel"};

    private final TestClock clock = TestClock.at(T0);
    private final List<TestEngine> engines = new ArrayList<>();
    /** Every call core banking received, forward and undo, in order. */
    private final List<AdapterCall> core = new CopyOnWriteArrayList<>();
    /** Every call a local handler received: the handler name and the call, in order. */
    private final List<Map.Entry<String, ServiceCall>> local = new CopyOnWriteArrayList<>();
    /** What core banking answers to a credit of the target account. */
    private final AtomicReference<AdapterResult> creditAnswer = new AtomicReference<>(
            new AdapterResult.BusinessError("ACCOUNT_CLOSED", "account 0099-887766 is closed"));
    /** What credit-back answers, per call, in order; the last answer repeats. */
    private final List<AdapterResult> creditBack = new CopyOnWriteArrayList<>();
    private final AtomicInteger creditBackCalls = new AtomicInteger();
    /** What the partner hub answers. */
    private final AtomicReference<AdapterResult> partner = new AtomicReference<>(
            new AdapterResult.Failed("PARTNER_REFUSED", "refused", false));
    /** What fx-confirm answers. */
    private final AtomicReference<HandlerResult> fxConfirm = new AtomicReference<>(
            new HandlerResult.BusinessError("DEAL_REJECTED", "rejected by the dealer"));

    @BeforeEach
    void clean() {
        TestEngine.reset();
        TestEngine.jdbc().sql("CREATE TABLE IF NOT EXISTS host_hold (tenant_id TEXT NOT NULL, instance_id TEXT NOT NULL,"
                + " attempt_id TEXT NOT NULL, account TEXT NOT NULL, amount_minor BIGINT NOT NULL, status TEXT NOT NULL,"
                + " PRIMARY KEY (tenant_id, instance_id))").update();
        TestEngine.jdbc().sql("TRUNCATE host_hold").update();
    }

    @AfterEach
    void stop() {
        engines.forEach(TestEngine::close);
    }

    // ---- the host ---------------------------------------------------------------------------------------------

    private ServiceTaskHandler handler(String name, Function<ServiceCall, HandlerResult> body) {
        return new ServiceTaskHandler() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public HandlerResult handle(ServiceCall call) {
                local.add(Map.entry(name, call));
                return body.apply(call);
            }
        };
    }

    private ServiceAdapter adapter(String name, Function<AdapterCall, AdapterResult> body) {
        return new ServiceAdapter() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public AdapterResult execute(AdapterCall call) {
                core.add(call);
                return body.apply(call);
            }
        };
    }

    private static HandlerResult done(String key, Object value) {
        return HandlerResult.completed(Map.of(key, value));
    }

    private TestEngine engine(boolean autoApprove, boolean withUndoHandlers) {
        List<ServiceTaskHandler> handlers = new ArrayList<>(List.of(
                handler("place-hold", call -> {
                    TestEngine.jdbc().sql("INSERT INTO host_hold VALUES (?, ?, ?, ?, ?, 'HELD')").params(call.tenantId(),
                            call.instanceId(), call.attemptId(), call.inputs().get("sourceAccount"),
                            ((Number) call.inputs().get("amountMinor")).longValue()).update();
                    return done("holdId", "HLD-" + call.instanceId().substring(20));
                }),
                handler("register-loan", call -> done("loanId", "LN-2026-0077")),
                handler("reserve-funds", call -> done("reservationId", "RSV-1")),
                handler("open-account", call -> done("accountNo", "0011-998877")),
                handler("fx-buy", call -> done("dealId", "FX-B")),
                handler("fx-sell", call -> done("dealId", "FX-S")),
                handler("fx-confirm", call -> fxConfirm.get())));
        if (withUndoHandlers) {
            handlers.addAll(List.of(
                    handler("release-hold", call -> {
                        TestEngine.jdbc().sql("UPDATE host_hold SET status = 'RELEASED' WHERE tenant_id = ? AND "
                                + "instance_id = ? AND attempt_id = ?").params(call.tenantId(), call.instanceId(),
                                call.forwardAttemptId()).update();
                        return done("released", true);
                    }),
                    handler("cancel-loan", call -> done("cancelled", true)),
                    handler("release-funds", call -> done("released", true)),
                    handler("close-account", call -> done("closed", true)),
                    handler("fx-unbuy", call -> done("reversed", true)),
                    handler("fx-unsell", call -> done("reversed", true))));
        }
        TestEngine engine = TestEngine.replica("pod-1", clock).autoApprove(autoApprove)
                .handlers(handlers.toArray(ServiceTaskHandler[]::new))
                .adapters(adapter("core-banking", call -> switch (call.nodeId()) {
                    case "debit-source" -> AdapterResult.completed(Map.of("postingRef", "FT26325001"));
                    default -> creditAnswer.get();
                }), adapter("credit-back", call -> {
                    int n = creditBackCalls.getAndIncrement();
                    return creditBack.isEmpty() ? AdapterResult.completed(Map.of("reversalRef", "FT26325002"))
                            : creditBack.get(Math.min(n, creditBack.size() - 1));
                }), adapter("partner-hub", call -> partner.get()))
                .build();
        engines.add(engine);
        engine.identity.as("bank-a", "pipeline", "processes:deploy", "processes:start", "processes:read",
                "processes:step:complete");
        return engine;
    }

    private TestEngine engine() {
        return engine(true, true);
    }

    private static DefinitionVersion deploy(TestEngine engine, String name) {
        return engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                TestEngine.resource("/bpmn/" + name + ".bpmn"), null));
    }

    private static ProcessInstance transfer(TestEngine engine, String key, String reference) {
        engine.identity.as("bank-a", "teller.hoa", "processes:start", "processes:read");
        return engine.services.start(new StartRequest(key, reference, Map.of("amountMinor", AMOUNT_VND,
                "currency", "VND", "sourceAccount", SOURCE, "targetAccount", TARGET), null));
    }

    /** Claims due async jobs and runs each on this thread, until none is due; returns the claims in order. */
    private static List<Claim> drain(TestEngine engine) {
        List<Claim> all = new ArrayList<>();
        for (int round = 0; round < 20; round++) {
            List<Claim> claimed = engine.async.claim(10);
            if (claimed.isEmpty()) {
                return all;
            }
            for (Claim claim : claimed) {
                all.add(claim);
                engine.async.runNow(claim);
            }
        }
        return all;
    }

    /** One claim of the next due job and its run. */
    private static Claim step(TestEngine engine) {
        Claim claim = engine.async.claim(1).get(0);
        engine.async.runNow(claim);
        return claim;
    }

    private static String status(String instanceId) {
        return TestEngine.jdbc().sql("SELECT status FROM pre_instance WHERE id = ?").param(instanceId)
                .query(String.class).single();
    }

    /** The stack entries of an instance as {@code step:status}, by scope token and newest first. */
    private static List<String> stack(String instanceId) {
        return TestEngine.jdbc().sql("SELECT s.step_id || ':' || s.status FROM pre_compensation_stack s "
                + "JOIN pre_token t ON t.tenant_id = s.tenant_id AND t.id = s.scope_token_id WHERE s.instance_id = ? "
                + "ORDER BY t.current_node_id COLLATE \"C\", s.seq DESC").param(instanceId).query(String.class).list();
    }

    private static List<String> history(String instanceId, String... types) {
        return TestEngine.jdbc().sql("SELECT event_type || ':' || coalesce(node_id, '-') FROM pre_history "
                + "WHERE instance_id = ? AND event_type = ANY (?) ORDER BY occurred_at, id")
                .params(instanceId, types).query(String.class).list();
    }

    private static String scopeToken(String instanceId, String block) {
        return TestEngine.jdbc().sql("SELECT id FROM pre_token WHERE instance_id = ? AND token_kind = 'SCOPE' AND "
                + "current_node_id = ?").params(instanceId, block).query(String.class).single();
    }

    private static String tokenStatus(String tokenId) {
        return TestEngine.jdbc().sql("SELECT status FROM pre_token WHERE id = ?").param(tokenId).query(String.class)
                .single();
    }

    private static int count(String sql, Object... params) {
        return TestEngine.jdbc().sql(sql).params(params).query(Integer.class).single();
    }

    private static List<ProcessEventPublisher.Event> events(TestEngine engine, String type) {
        return engine.events.stream().filter(e -> e.type().equals(type)).toList();
    }

    private List<String> undoCalls() {
        List<String> out = new ArrayList<>();
        for (AdapterCall call : core) {
            if (call.isUndo()) {
                out.add("credit-back:" + call.nodeId());
            }
        }
        for (Map.Entry<String, ServiceCall> call : local) {
            if (call.getValue().forwardAttemptId() != null) {
                out.add(call.getKey() + ":" + call.getValue().nodeId());
            }
        }
        return out;
    }

    private static Incident incident(TestEngine engine, String instanceId) {
        engine.identity.as("bank-a", "ops.lan", OPERATOR);
        return engine.services.openIncidents(instanceId).get(0);
    }

    /** Runs the transfer until the credit of the target failed: the debit was accepted on job J1, attempt 1. */
    private String failedTransfer(TestEngine engine, String key, String reference) {
        deploy(engine, key);
        ProcessInstance started = transfer(engine, key, reference);
        Claim debit = step(engine);
        assertThat(debit.job().nodeId()).isEqualTo("debit-source");
        Claim credit = step(engine);
        assertThat(credit.job().nodeId()).isEqualTo("credit-target");
        return started.id();
    }

    // ---- PRE-073 ----------------------------------------------------------------------------------------------

    @Test
    void PRE_073_a_failed_transfer_undoes_its_completed_steps_newest_first_with_the_forward_keys() {
        // Given fund-transfer with the saga block transfer: hold-funds (local, undone by release-hold), debit-source
        // (remote, undone by credit-back) and credit-target (onFailure compensate), and 25,000,000 VND to move
        TestEngine engine = engine();
        deploy(engine, "fund-transfer");
        ProcessInstance started = transfer(engine, "fund-transfer", "TRF-2026-0001");
        String hold = local.get(0).getValue().attemptId();
        Claim debit = step(engine);
        String j1 = debit.job().id();

        // When credit-target fails with BUSINESS_ERROR ACCOUNT_CLOSED
        Claim credit = step(engine);
        assertThat(credit.job().nodeId()).isEqualTo("credit-target");

        // Then the instance is COMPENSATING, and the started event carries the transfer scope token and depth 2
        String id = started.id();
        String transferScope = scopeToken(id, "transfer");
        assertThat(status(id)).isEqualTo("COMPENSATING");
        assertThat(count("SELECT count(*) FROM pre_incident WHERE instance_id = ?", id)).isZero();
        assertThat(events(engine, "processes.compensation.started.v1")).singleElement().satisfies(e -> {
            assertThat(e.payload()).containsEntry("scopeId", transferScope).containsEntry("depth", 2L)
                    .containsEntry("nodeId", "credit-target").containsEntry("cause", "ACCOUNT_CLOSED");
        });
        assertThat(stack(id)).containsExactly("debit-source:RUNNING", "hold-funds:PENDING");

        // And credit-back runs first, as COMPENSATION job C1 keyed by its own id, with the debit's job and attempt
        Claim c1 = step(engine);
        assertThat(c1.job().jobKind()).isEqualTo("COMPENSATION");
        AdapterCall undo = core.get(core.size() - 1);
        assertThat(undo.nodeId()).isEqualTo("debit-source");
        assertThat(undo.idempotencyKey()).isEqualTo(c1.job().id()).isNotEqualTo(j1);
        assertThat(undo.forwardJobId()).isEqualTo(j1);
        assertThat(undo.forwardAttemptId()).isEqualTo(ProcessServices.attemptId(j1, 1));
        assertThat(undo.tenantId()).isEqualTo("bank-a");
        assertThat(undo.inputs()).containsEntry("account", SOURCE).containsEntry("amountMinor", AMOUNT_VND)
                .containsEntry("currency", "VND").containsEntry("forwardResult", Map.of("postingRef", "FT26325001"));
        assertThat(count("SELECT count(*) FROM host_hold WHERE instance_id = ? AND status = 'HELD'", id))
                .as("release-hold runs only after C1 is DONE").isOne();

        // And release-hold runs next, in its own transaction, with the hold's attempt id
        Claim c2 = step(engine);
        assertThat(c2.job().id()).isNotEqualTo(c1.job().id());
        assertThat(local.get(local.size() - 1)).satisfies(call -> {
            assertThat(call.getKey()).isEqualTo("release-hold");
            assertThat(call.getValue().forwardAttemptId()).isEqualTo(hold);
        });
        assertThat(count("SELECT count(*) FROM host_hold WHERE instance_id = ? AND status = 'RELEASED'", id)).isOne();

        // And both entries are DONE, the scope COMPENSATED, the instance FAILED; credit-target is never undone
        assertThat(stack(id)).containsExactly("debit-source:DONE", "hold-funds:DONE");
        assertThat(tokenStatus(transferScope)).isEqualTo("COMPENSATED");
        assertThat(status(id)).isEqualTo("FAILED");
        assertThat(count("SELECT count(*) FROM pre_token WHERE instance_id = ? AND token_kind = 'FLOW' AND status NOT IN "
                + "('COMPLETED','CANCELLED')", id)).isZero();
        assertThat(events(engine, "processes.instance.failed.v1")).hasSize(1);
        assertThat(undoCalls()).containsExactly("credit-back:debit-source", "release-hold:hold-funds");
        assertThat(history(id, "COMPENSATION_STARTED", "COMPENSATION_STEP_SCHEDULED", "COMPENSATION_STEP_COMPLETED",
                "COMPENSATED", "INSTANCE_ENDED")).containsExactly("COMPENSATION_STARTED:credit-target",
                "COMPENSATION_STEP_SCHEDULED:debit-source", "COMPENSATION_STEP_COMPLETED:debit-source",
                "COMPENSATION_STEP_SCHEDULED:hold-funds", "COMPENSATION_STEP_COMPLETED:hold-funds",
                "COMPENSATED:transfer", "INSTANCE_ENDED:-");
        assertThat(drain(engine)).isEmpty();
    }

    @Test
    void PRE_073_a_failure_that_is_not_retryable_or_uses_the_last_attempt_compensates_too() {
        // Given the transfer, whose credit fails with a failure that is not retryable
        TestEngine engine = engine();
        creditAnswer.set(new AdapterResult.Failed("ACCOUNT_BLOCKED", "blocked", false));
        String notRetryable = failedTransfer(engine, "fund-transfer", "TRF-2026-0002");

        // Then the compensation starts at once
        assertThat(status(notRetryable)).isEqualTo("COMPENSATING");
        drain(engine);
        assertThat(status(notRetryable)).isEqualTo("FAILED");

        // When a second transfer's credit times out on all 3 attempts
        creditAnswer.set(new AdapterResult.Failed("ADAPTER_TIMEOUT", "no answer", true));
        ProcessInstance second = transfer(engine, "fund-transfer", "TRF-2026-0003");
        step(engine);
        step(engine);
        clock.advance(Duration.ofSeconds(10));
        step(engine);
        assertThat(status(second.id())).as("a retry is left").isEqualTo("RUNNING");
        clock.advance(Duration.ofSeconds(20));
        step(engine);

        // Then the last attempt starts the compensation, and the steps are undone newest first
        assertThat(status(second.id())).isEqualTo("COMPENSATING");
        drain(engine);
        assertThat(status(second.id())).isEqualTo("FAILED");
        assertThat(stack(second.id())).containsExactly("debit-source:DONE", "hold-funds:DONE");
    }

    @Test
    void PRE_073_an_undo_that_names_no_registered_handler_or_adapter_is_refused_at_request_approval() {
        // Given a host without the undo handlers, where imports are drafts
        TestEngine engine = engine(false, false);
        DefinitionVersion draft = deploy(engine, "fund-transfer");
        assertThat(draft.status()).isEqualTo("DRAFT");
        String renamed = new String(TestEngine.resource("/bpmn/fund-transfer.bpmn"), java.nio.charset.StandardCharsets.UTF_8)
                .replace("credit-back", "credit-bak");

        // When it is sent to request-approval, and a version naming an unregistered adapter is too
        engine.identity.as("bank-a", "alice", "processes:author", "processes:read");
        assertThatThrownBy(() -> engine.services.requestApproval(draft.ref(), draft.lockVersion(), null, null))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> {
                    // Then it is 422 VALIDATION_FAILED naming the step and its undo, and the version stays a draft
                    assertThat(e.code()).isEqualTo("VALIDATION_FAILED");
                    assertThat(e.problems()).extracting(DefinitionProblem::code, DefinitionProblem::message)
                            .containsExactly(org.assertj.core.groups.Tuple.tuple("HANDLER_NOT_REGISTERED",
                                    "hold-funds -> release-hold (compensate): no ServiceTaskHandler of this name is"
                                            + " registered in this host"));
                });
        engine.identity.as("bank-a", "pipeline", "processes:deploy", "processes:read");
        DefinitionVersion second = engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                renamed.getBytes(java.nio.charset.StandardCharsets.UTF_8), null));
        engine.identity.as("bank-a", "alice", "processes:author", "processes:read");
        assertThatThrownBy(() -> engine.services.requestApproval(second.ref(), second.lockVersion(), null, null))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.problems())
                        .extracting(DefinitionProblem::code).contains("ADAPTER_NOT_REGISTERED"));
        assertThat(count("SELECT count(*) FROM pre_definition WHERE status = 'DRAFT'")).isEqualTo(2);
    }

    // ---- PRE-074 ----------------------------------------------------------------------------------------------

    @Test
    void PRE_074_a_failed_undo_raises_an_incident_stops_the_walk_and_a_retry_runs_it_again_under_the_same_job() {
        // Given the failed transfer of PRE-073, where credit-back answers ACCOUNT_FROZEN
        TestEngine engine = engine();
        creditBack.add(new AdapterResult.BusinessError("ACCOUNT_FROZEN", "account 0011-223344 is frozen"));
        String id = failedTransfer(engine, "fund-transfer", "TRF-2026-0010");
        String j1 = TestEngine.jdbc().sql("SELECT forward_job_id FROM pre_compensation_stack WHERE instance_id = ? AND "
                + "step_id = 'debit-source'").param(id).query(String.class).single();

        // When the failure is recorded
        Claim c1 = step(engine);

        // Then the debit's entry is FAILED, an incident COMPENSATION_FAILED is open on debit-source, and the walk pauses
        assertThat(stack(id)).containsExactly("debit-source:FAILED", "hold-funds:PENDING");
        assertThat(status(id)).isEqualTo("COMPENSATING");
        Incident open = incident(engine, id);
        assertThat(open.errorCode()).isEqualTo("COMPENSATION_FAILED");
        assertThat(open.nodeId()).isEqualTo("debit-source");
        assertThat(open.causeSummary()).contains("ACCOUNT_FROZEN").contains(j1).contains("entry ")
                .doesNotContain("frozen").doesNotContain(SOURCE);
        assertThat(open.allowedActions()).contains("retry", "resolve");
        assertThat(events(engine, "processes.incident.raised.v1")).singleElement().satisfies(e -> assertThat(
                e.payload()).containsEntry("errorCode", "COMPENSATION_FAILED").containsEntry("businessCode",
                "ACCOUNT_FROZEN"));
        assertThat(drain(engine)).as("release-hold stays PENDING").isEmpty();

        // When ops.lan retries and core banking answers this time
        creditBack.clear();
        Incident retried = engine.services.retry(open.id(), open.lockVersion(), "Account unfrozen by operations",
                "retry-1");
        assertThat(retried.retrying()).isTrue();
        Claim again = step(engine);

        // Then credit-back runs again under the same job id with a new attempt id, the incident is resolved, and the
        // walk continues with release-hold
        assertThat(again.job().id()).isEqualTo(c1.job().id());
        List<AdapterCall> undos = core.stream().filter(AdapterCall::isUndo).toList();
        assertThat(undos).hasSize(2);
        assertThat(undos.get(1).idempotencyKey()).isEqualTo(undos.get(0).idempotencyKey());
        assertThat(undos.get(1).attemptId()).isNotEqualTo(undos.get(0).attemptId());
        engine.identity.as("bank-a", "ops.lan", OPERATOR);
        assertThat(engine.services.incident(open.id())).satisfies(i -> {
            assertThat(i.status()).isEqualTo("RESOLVED");
            assertThat(i.resolution()).isEqualTo("RETRY_SUCCEEDED");
        });
        drain(engine);
        assertThat(stack(id)).containsExactly("debit-source:DONE", "hold-funds:DONE");
        assertThat(status(id)).isEqualTo("FAILED");
    }

    @Test
    void PRE_074_an_undo_that_times_out_on_every_attempt_is_resolved_by_a_second_person_and_the_walk_continues() {
        // Given the failed transfer, where credit-back times out on all 3 attempts
        TestEngine engine = engine();
        creditBack.add(new AdapterResult.Failed("ADAPTER_TIMEOUT", "no answer in 30s", true));
        String id = failedTransfer(engine, "fund-transfer", "TRF-2026-0011");
        step(engine);
        clock.advance(Duration.ofSeconds(10));
        step(engine);
        clock.advance(Duration.ofSeconds(20));
        step(engine);

        // Then the incident names ADAPTER_TIMEOUT, the walk is paused, and release-hold has not run
        Incident open = incident(engine, id);
        assertThat(open.errorCode()).isEqualTo("COMPENSATION_FAILED");
        assertThat(open.causeSummary()).contains("ADAPTER_TIMEOUT");
        assertThat(open.retryCount()).isEqualTo(2);
        assertThat(stack(id)).containsExactly("debit-source:FAILED", "hold-funds:PENDING");

        // When ops.lan proposes that the reversal was posted by hand, and ops.minh approves it
        Incident proposed = engine.services.resolve(open.id(), open.lockVersion(),
                Map.of("reversalRef", "FT26325999"), "Reversed by hand in core banking, ticket OPS-4411", "resolve-1");
        assertThat(proposed.status()).isEqualTo("RESOLUTION_PENDING");
        assertThatThrownBy(() -> engine.services.approveResolution(open.id(), proposed.lockVersion(), null, "self-1"))
                .isInstanceOf(io.github.dhluong90.bpm.process.api.SelfApprovalForbiddenException.class);
        engine.identity.as("bank-a", "ops.minh", OPERATOR);
        Incident resolved = engine.services.approveResolution(open.id(), proposed.lockVersion(), "Checked", "approve-1");

        // Then the entry is DONE by hand, the incident RESOLVED with both people, and the walk goes on
        assertThat(resolved.status()).isEqualTo("RESOLVED");
        assertThat(resolved.resolution()).isEqualTo("COMPLETED_MANUALLY");
        assertThat(resolved.resolvedBy()).isEqualTo("ops.minh");
        assertThat(stack(id)).containsExactly("debit-source:DONE", "hold-funds:RUNNING");
        drain(engine);
        assertThat(status(id)).isEqualTo("FAILED");
        assertThat(creditBackCalls.get()).isEqualTo(3);
    }

    // ---- PRE-075 ----------------------------------------------------------------------------------------------

    @Test
    void PRE_075_a_manual_undo_opens_an_incident_with_the_steps_context_instead_of_being_skipped() {
        // Given debit-source declares compensate manual
        TestEngine engine = engine();
        String id = failedTransfer(engine, "fund-transfer-manual", "TRF-2026-0020");
        String j1 = TestEngine.jdbc().sql("SELECT forward_job_id FROM pre_compensation_stack WHERE instance_id = ? AND "
                + "step_id = 'debit-source'").param(id).query(String.class).single();

        // Then the walk reached its entry: MANUAL, with COMPENSATION_MANUAL_REQUIRED and the step's context
        assertThat(stack(id)).containsExactly("debit-source:MANUAL", "hold-funds:PENDING");
        Incident open = incident(engine, id);
        assertThat(open.errorCode()).isEqualTo("COMPENSATION_MANUAL_REQUIRED");
        assertThat(open.nodeId()).isEqualTo("debit-source");
        assertThat(open.causeSummary()).contains(j1).contains(ProcessServices.attemptId(j1, 1));
        assertThat(open.allowedActions()).containsExactly("resolve");
        Map<String, Object> details = io.github.dhluong90.bpm.process.internal.json.Values.readMap(TestEngine.jdbc()
                .sql("SELECT details FROM pre_history WHERE instance_id = ? AND event_type = 'COMPENSATION_STEP_MANUAL'")
                .param(id).query(String.class).single());
        assertThat(details).containsEntry("forwardJobId", j1)
                .containsEntry("forwardAttemptId", ProcessServices.attemptId(j1, 1)).containsKey("inputSnapshot");
        assertThat(drain(engine)).as("the walk is paused, nothing is skipped").isEmpty();
        assertThat(undoCalls()).isEmpty();

        // And a retry has nothing to run
        assertThatThrownBy(() -> engine.services.retry(open.id(), open.lockVersion(), "Try again", "retry-1"))
                .isInstanceOf(InvalidStateException.class).hasMessageContaining("manual undo");

        // When the reversal is posted by hand and the resolution approved, the walk continues with release-hold
        Incident proposed = engine.services.resolve(open.id(), open.lockVersion(), Map.of("reversalRef", "FT-MANUAL-1"),
                "Reversed by hand, ticket OPS-4412", "resolve-1");
        engine.identity.as("bank-a", "ops.minh", OPERATOR);
        engine.services.approveResolution(open.id(), proposed.lockVersion(), null, "approve-1");
        drain(engine);
        assertThat(stack(id)).containsExactly("debit-source:DONE", "hold-funds:DONE");
        assertThat(status(id)).isEqualTo("FAILED");
    }

    // ---- PRE-076 ----------------------------------------------------------------------------------------------

    @Test
    void PRE_076_a_failure_in_one_saga_undoes_only_its_entries_and_a_compensating_instance_cannot_be_cancelled() {
        // Given fx-booking: open-account in the root scope, then fx-leg-1 and fx-leg-2 with two undoable steps each
        TestEngine engine = engine();
        deploy(engine, "fx-booking");
        engine.identity.as("bank-a", "dealer.an", "processes:start", "processes:read");

        // When (a) fx-leg-2's confirmation fails with onFailure compensate
        ProcessInstance started = engine.services.start(new StartRequest("fx-booking", "FX-2026-0001",
                Map.of("dealerFlag", 0), null));
        String id = started.id();
        assertThat(status(id)).isEqualTo("COMPENSATING");

        // Then (c) a cancel while COMPENSATING is 409 INVALID_STATE, and nothing is written
        int rows = count("SELECT count(*) FROM pre_history WHERE instance_id = ?", id);
        engine.identity.as("bank-a", "ops.lan", OPERATOR);
        ProcessInstance compensating = engine.services.instance(id);
        assertThatThrownBy(() -> engine.services.cancel(id, compensating.lockVersion(), "Customer withdrew",
                "cancel-1")).isInstanceOfSatisfying(InvalidStateException.class, e -> {
                    assertThat(e.code()).isEqualTo("INVALID_STATE");
                    assertThat(e.getMessage()).contains("a compensation is running");
                });
        assertThat(count("SELECT count(*) FROM pre_history WHERE instance_id = ?", id)).isEqualTo(rows);

        // And only the two entries of fx-leg-2 are undone, newest first; fx-leg-1 and open-account are untouched
        drain(engine);
        assertThat(stack(id)).containsExactly("open-account:PENDING", "leg1-sell:PENDING", "leg1-buy:PENDING",
                "leg2-sell:DONE", "leg2-buy:DONE");
        assertThat(undoCalls()).containsExactly("fx-unsell:leg2-sell", "fx-unbuy:leg2-buy");
        assertThat(tokenStatus(scopeToken(id, "fx-leg-2"))).isEqualTo("COMPENSATED");
        assertThat(tokenStatus(scopeToken(id, "fx-leg-1"))).isEqualTo("COMPLETED");
        assertThat(status(id)).isEqualTo("FAILED");
        // A step without an undo pushes no entry
        assertThat(count("SELECT count(*) FROM pre_compensation_stack WHERE step_id = 'leg2-confirm'")).isZero();
    }

    @Test
    void PRE_076_throw_compensation_walks_only_its_own_saga() {
        // Given fx-booking, whose dealer flags the first leg
        TestEngine engine = engine();
        deploy(engine, "fx-booking");
        engine.identity.as("bank-a", "dealer.an", "processes:start", "processes:read");

        // When (b) throwCompensation runs inside fx-leg-1
        String id = engine.services.start(new StartRequest("fx-booking", "FX-2026-0002", Map.of("dealerFlag", 1),
                null)).id();
        drain(engine);

        // Then only fx-leg-1's entries are walked, newest first, and the second leg never started
        assertThat(stack(id)).containsExactly("open-account:PENDING", "leg1-sell:DONE", "leg1-buy:DONE");
        assertThat(undoCalls()).containsExactly("fx-unsell:leg1-sell", "fx-unbuy:leg1-buy");
        assertThat(tokenStatus(scopeToken(id, "fx-leg-1"))).isEqualTo("COMPENSATED");
        assertThat(count("SELECT count(*) FROM pre_token WHERE instance_id = ? AND current_node_id = 'fx-leg-2'", id))
                .isZero();
        assertThat(history(id, "COMPENSATION_STARTED")).containsExactly("COMPENSATION_STARTED:leg1-abort");
        assertThat(status(id)).isEqualTo("FAILED");
    }

    @Test
    void PRE_076_another_tenant_can_neither_read_the_compensating_instance_nor_command_its_incident() {
        // Given bank-a's transfer whose undo failed with an open incident
        TestEngine engine = engine();
        creditBack.add(new AdapterResult.BusinessError("ACCOUNT_FROZEN", "frozen"));
        String id = failedTransfer(engine, "fund-transfer", "TRF-2026-0030");
        step(engine);
        Incident open = incident(engine, id);
        int history = count("SELECT count(*) FROM pre_history");
        int jobs = count("SELECT count(*) FROM pre_timer WHERE status = 'DUE'");

        // When a bank-b operator reads the instance and retries and resolves the incident
        engine.identity.as("bank-b", "ops.lan", OPERATOR);

        // Then each is 404 and nothing is written
        assertThatThrownBy(() -> engine.services.instance(id)).isInstanceOf(InstanceNotFoundException.class);
        assertThatThrownBy(() -> engine.services.retry(open.id(), open.lockVersion(), "retry", "x-1"))
                .isInstanceOf(IncidentNotFoundException.class);
        assertThatThrownBy(() -> engine.services.resolve(open.id(), open.lockVersion(), Map.of(), "done", "x-2"))
                .isInstanceOf(IncidentNotFoundException.class);
        assertThat(count("SELECT count(*) FROM pre_history")).isEqualTo(history);
        assertThat(count("SELECT count(*) FROM pre_timer WHERE status = 'DUE'")).isEqualTo(jobs);
        assertThat(count("SELECT count(*) FROM pre_compensation_stack WHERE tenant_id = 'bank-b'")).isZero();
    }

    // ---- PRE-044 and PRE-013 ----------------------------------------------------------------------------------

    @Test
    void PRE_044_compensation_walks_only_the_entries_of_its_own_scope_token() {
        // Given loan-booking: register-loan in the root scope, then the saga block disburse with reserve-funds and
        // open-account in parallel and notify-partner, which the partner refuses
        TestEngine engine = engine();
        deploy(engine, "loan-booking");
        engine.identity.as("bank-a", "officer.binh", "processes:start", "processes:read");

        // When two instances start and run until notify-partner fails
        String id = engine.services.start(new StartRequest("loan-booking", "LN-2026-0077", Map.of(), null)).id();
        String other = engine.services.start(new StartRequest("loan-booking", "LN-2026-0078", Map.of(), null)).id();
        String root = TestEngine.jdbc().sql("SELECT id FROM pre_token WHERE instance_id = ? AND token_kind = 'SCOPE' AND "
                + "scope_token_id IS NULL AND current_node_id = 'loan-booking'").param(id).query(String.class).single();
        String disburse = scopeToken(id, "disburse");
        assertThat(TestEngine.jdbc().sql("SELECT scope_token_id FROM pre_token WHERE id = ?").param(disburse)
                .query(String.class).single()).isEqualTo(root);
        assertThat(TestEngine.jdbc().sql("SELECT s.handler || '@' || t.current_node_id FROM pre_compensation_stack s "
                + "JOIN pre_token t ON t.tenant_id = s.tenant_id AND t.id = s.scope_token_id WHERE s.instance_id = ? "
                + "ORDER BY s.handler").param(id).query(String.class).list())
                .containsExactly("cancel-loan@loan-booking", "close-account@disburse", "release-funds@disburse");
        drain(engine);

        // Then the two disburse undos ran newest first, cancel-loan never, and the disburse scope ends COMPENSATED
        assertThat(stack(id)).containsExactly("open-account:DONE", "reserve-funds:DONE", "register-loan:PENDING");
        assertThat(undoCalls().stream().filter(c -> !c.isEmpty()).toList()).containsSubsequence(
                "close-account:open-account", "release-funds:reserve-funds").doesNotContain(
                "cancel-loan:register-loan");
        assertThat(tokenStatus(disburse)).isEqualTo("COMPENSATED");
        // And the second instance shares no token or stack row with the first
        assertThat(count("SELECT count(*) FROM pre_compensation_stack a JOIN pre_compensation_stack b ON a.id = b.id "
                + "OR a.scope_token_id = b.scope_token_id WHERE a.instance_id = ? AND b.instance_id = ?", id, other))
                .isZero();
        assertThat(stack(other)).containsExactly("open-account:DONE", "reserve-funds:DONE", "register-loan:PENDING");
    }

    @Test
    void PRE_013_failure_executes_reverse_saga_compensation() {
        // Given reserve-funds (undone by release-funds), open-account (undone by close-account) and notify-partner
        // (onFailure compensate), whose partner keeps failing
        TestEngine engine = engine();
        partner.set(new AdapterResult.Failed("PARTNER_DOWN", "down", true));
        deploy(engine, "account-opening");
        engine.identity.as("bank-a", "officer.binh", "processes:start", "processes:read");
        String id = engine.services.start(new StartRequest("account-opening", "ACC-2026-0101", Map.of(), null)).id();
        String reserve = local.get(0).getValue().attemptId();
        String open = local.get(1).getValue().attemptId();

        // When notify-partner exhausts its retries
        step(engine);
        clock.advance(Duration.ofSeconds(10));
        step(engine);
        clock.advance(Duration.ofSeconds(20));
        step(engine);
        drain(engine);

        // Then close-account runs first and release-funds second, each with a unique attempt id and the forward one
        List<Map.Entry<String, ServiceCall>> undos = local.stream().filter(c -> c.getValue().forwardAttemptId() != null)
                .toList();
        assertThat(undos).extracting(Map.Entry::getKey).containsExactly("close-account", "release-funds");
        assertThat(undos.get(0).getValue().forwardAttemptId()).isEqualTo(open);
        assertThat(undos.get(1).getValue().forwardAttemptId()).isEqualTo(reserve);
        assertThat(undos.get(0).getValue().attemptId()).isNotEqualTo(undos.get(1).getValue().attemptId());
        assertThat(status(id)).isEqualTo("FAILED");
    }
}
