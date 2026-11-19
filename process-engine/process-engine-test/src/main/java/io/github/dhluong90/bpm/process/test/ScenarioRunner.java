package io.github.dhluong90.bpm.process.test;

import io.github.dhluong90.bpm.process.internal.exec.Command;
import io.github.dhluong90.bpm.process.internal.exec.Effect;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionKernel;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionMode;
import io.github.dhluong90.bpm.process.internal.exec.HistoryEntry;
import io.github.dhluong90.bpm.process.internal.exec.InstanceState;
import io.github.dhluong90.bpm.process.internal.exec.InstanceStatus;
import io.github.dhluong90.bpm.process.internal.exec.StepResult;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.exec.TokenKind;
import io.github.dhluong90.bpm.process.internal.exec.TokenStatus;
import io.github.dhluong90.bpm.process.internal.exec.Transition;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Drives one instance through an {@link ExecutionKernel} in memory: start, complete tasks, fire jobs, answer
 * invocations, and assert positions, variables and history. The format of the executable conformance scenarios
 * (standards-compliance section 10.3).
 *
 * <pre>{@code
 * ScenarioRunner run = ScenarioRunner.of(kernel, "I1", "bank-a", "D1", clock)
 *     .onInvoke("card-fee", inputs -> new StepResult.Completed(Map.of("fee", Money.ofMinor("VND", 0))))
 *     .start(Map.of("customerId", "C-001"));
 * run.assertCompleted();
 * }</pre>
 *
 * <p>{@code SYNC_LOCAL} invocations are answered at once, as the shell does in the same transaction. {@code ASYNC}
 * invocations wait until {@link #deliver(String, StepResult)} is called.
 */
public final class ScenarioRunner {

    private final ExecutionKernel kernel;
    private final TestClock clock;
    private final Map<String, Function<Map<String, Object>, StepResult>> handlers = new HashMap<>();
    private final Map<String, Effect.CreateUserTask> openTasks = new LinkedHashMap<>();
    private final Map<String, Effect.InvokeService> pending = new LinkedHashMap<>();
    private final List<Effect> effects = new ArrayList<>();
    private final List<HistoryEntry> history = new ArrayList<>();
    private InstanceState state;

    private ScenarioRunner(ExecutionKernel kernel, InstanceState state, TestClock clock) {
        this.kernel = Objects.requireNonNull(kernel, "kernel must not be null");
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * @param kernel the kernel bound to the definition under test
     * @param instanceId instance id
     * @param tenantId tenant
     * @param definitionId definition id
     * @param clock the clock
     * @return a runner on a created, not yet started instance
     */
    public static ScenarioRunner of(ExecutionKernel kernel, String instanceId, String tenantId, String definitionId,
            TestClock clock) {
        return new ScenarioRunner(kernel, InstanceState.created(instanceId, tenantId, definitionId, null), clock);
    }

    /**
     * Answers invocations of a target (handler name, rule set key, agent name).
     *
     * @param target the target
     * @param handler inputs to result
     * @return this
     */
    public ScenarioRunner onInvoke(String target, Function<Map<String, Object>, StepResult> handler) {
        handlers.put(target, handler);
        return this;
    }

    /** @param variables initial variables @return this */
    public ScenarioRunner start(Map<String, Object> variables) {
        return apply(new Command.Start(variables, "starter"));
    }

    /**
     * Completes the open task on a node.
     *
     * @param nodeId the user task id
     * @param result the completion result
     * @param actor who completes it
     * @return this
     */
    public ScenarioRunner completeTask(String nodeId, Map<String, Object> result, String actor) {
        Effect.CreateUserTask task = openTasks.values().stream().filter(t -> t.nodeId().equals(nodeId)).findFirst()
                .orElseThrow(() -> new AssertionError("No open task on " + nodeId + "; open: " + openTasks.keySet()));
        openTasks.remove(task.taskId());
        return apply(new Command.CompleteTask(task.taskId(), task.tokenId(), result, actor));
    }

    /**
     * Delivers the result of a pending {@code ASYNC} invocation on a node.
     *
     * @param nodeId the node
     * @param result the result
     * @return this
     */
    public ScenarioRunner deliver(String nodeId, StepResult result) {
        Effect.InvokeService call = pending.values().stream().filter(c -> c.nodeId().equals(nodeId)).findFirst()
                .orElseThrow(() -> new AssertionError("No pending invocation on " + nodeId));
        pending.remove(call.attemptId());
        return apply(new Command.AsyncResult(call.tokenId(), call.attemptId(), result));
    }

    /** @param command any command @return this */
    public ScenarioRunner apply(Command command) {
        Deque<Command> queue = new ArrayDeque<>();
        queue.add(command);
        while (!queue.isEmpty()) {
            Transition transition = kernel.apply(state, queue.poll(), clock);
            state = transition.state();
            history.addAll(transition.history());
            for (Effect effect : transition.effects()) {
                effects.add(effect);
                switch (effect) {
                    case Effect.CreateUserTask task -> openTasks.put(task.taskId(), task);
                    case Effect.CancelUserTask cancel -> openTasks.remove(cancel.taskId());
                    case Effect.InvokeService call when call.mode() == ExecutionMode.SYNC_LOCAL ->
                            queue.add(new Command.AsyncResult(call.tokenId(), call.attemptId(), answer(call)));
                    case Effect.InvokeService call -> pending.put(call.attemptId(), call);
                    default -> { }
                }
            }
        }
        return this;
    }

    private StepResult answer(Effect.InvokeService call) {
        Function<Map<String, Object>, StepResult> handler = handlers.get(call.target());
        if (handler == null) {
            throw new AssertionError("No handler for invocation of " + call.target() + " on " + call.nodeId());
        }
        return handler.apply(call.inputs());
    }

    /** @return the current state */
    public InstanceState state() {
        return state;
    }

    /** @return every effect so far */
    public List<Effect> effects() {
        return List.copyOf(effects);
    }

    /** @return every history entry so far */
    public List<HistoryEntry> history() {
        return List.copyOf(history);
    }

    /** @return open user tasks */
    public List<Effect.CreateUserTask> openTasks() {
        return List.copyOf(openTasks.values());
    }

    /**
     * @param nodeId a node
     * @param status the expected status
     * @return this
     */
    public ScenarioRunner assertTokenAt(String nodeId, TokenStatus status) {
        boolean found = state.tokens().stream()
                .anyMatch(t -> t.kind() == TokenKind.FLOW && t.nodeId().equals(nodeId) && t.status() == status);
        if (!found) {
            throw new AssertionError("Expected a " + status + " token at " + nodeId + " but tokens are " + positions());
        }
        return this;
    }

    /** @return this, after checking the instance completed with no live token */
    public ScenarioRunner assertCompleted() {
        if (state.status() != InstanceStatus.COMPLETED) {
            throw new AssertionError("Expected COMPLETED but was " + state.status() + "; tokens " + positions());
        }
        if (state.tokens().stream().anyMatch(t -> t.status().live())) {
            throw new AssertionError("Completed instance still has live tokens " + positions());
        }
        return this;
    }

    private List<String> positions() {
        return state.tokens().stream().map(ScenarioRunner::describe).toList();
    }

    private static String describe(Token t) {
        return t.kind() + ":" + t.nodeId() + ":" + t.status();
    }
}
