package io.github.dhluong90.bpm.process.test;

import io.github.dhluong90.bpm.process.spi.AdapterResult;
import io.github.dhluong90.bpm.process.spi.AgentAdapter;
import io.github.dhluong90.bpm.process.spi.AgentInvocation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * A stub {@link AgentAdapter} for tests, examples and demos (#52): no model, no network. It answers each invocation
 * with a function, or with canned answers in order (the last one repeats), and records every invocation, so a test
 * can check what the agent received: the bounded inputs, the attempt id the model call is keyed by
 * ({@link AgentInvocation#modelCallKey()}) and the job id side effects are keyed by
 * ({@link AgentInvocation#idempotencyKey()}). Thread-safe.
 *
 * <pre>
 * StubAgentAdapter assessor = StubAgentAdapter.answering("documents.assess",
 *         Map.of("riskScore", 85, "recommendation", "APPROVE"));
 * </pre>
 */
public final class StubAgentAdapter implements AgentAdapter {

    private final String name;
    private final Function<AgentInvocation, AdapterResult> answer;
    private final List<AgentInvocation> invocations = new CopyOnWriteArrayList<>();

    private StubAgentAdapter(String name, Function<AgentInvocation, AdapterResult> answer) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.answer = Objects.requireNonNull(answer, "answer must not be null");
    }

    /**
     * @param name the name steps refer to in {@code horus:agent}
     * @param answer the answer to each invocation
     * @return the stub
     */
    public static StubAgentAdapter of(String name, Function<AgentInvocation, AdapterResult> answer) {
        return new StubAgentAdapter(name, answer);
    }

    /**
     * @param name the name steps refer to in {@code horus:agent}
     * @param outputs the agent's answers, one per invocation in order; the last one repeats
     * @return the stub
     */
    @SafeVarargs
    public static StubAgentAdapter answering(String name, Map<String, ?>... outputs) {
        if (outputs.length == 0) {
            throw new IllegalArgumentException("At least one answer");
        }
        List<Map<String, ?>> answers = List.of(outputs);
        int[] next = {0};
        return new StubAgentAdapter(name, invocation -> {
            synchronized (next) {
                Map<String, ?> output = answers.get(Math.min(next[0]++, answers.size() - 1));
                return AdapterResult.completed(output);
            }
        });
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public AdapterResult execute(AgentInvocation invocation) {
        invocations.add(invocation);
        return answer.apply(invocation);
    }

    /** @return every invocation so far, in order */
    public List<AgentInvocation> invocations() {
        return Collections.unmodifiableList(new ArrayList<>(invocations));
    }

    /** Forgets the recorded invocations. */
    public void reset() {
        invocations.clear();
    }
}
