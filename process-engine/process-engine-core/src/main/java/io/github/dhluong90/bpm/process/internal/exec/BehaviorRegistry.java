package io.github.dhluong90.bpm.process.internal.exec;

import io.github.dhluong90.bpm.process.internal.model.bpmn.EventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Behaviours keyed by node class and {@link EventDefinitionKey}, and compile steps, filled once by the
 * {@link BehaviorModule}s. Lookup is exact: a timer start event finds only a behaviour registered for
 * {@code (StartEvent, TimerEventDefinition)}, never the none start event's. A node with no behaviour is refused as
 * not executable (DESIGN section 5.8).
 */
public final class BehaviorRegistry {

    /** A behaviour key. */
    record Key(Class<?> nodeType, EventDefinitionKey eventDefinition) {
    }

    private final Map<Key, FlowNodeBehavior<?>> behaviors = new LinkedHashMap<>();
    private final List<CompileStep> compileSteps = new ArrayList<>();
    private final List<SettleStep> settleSteps = new ArrayList<>();

    /**
     * @param behavior a behaviour; a second behaviour for the same node type and event definition key is refused
     * @param <N> node type
     * @return this registry
     */
    public <N extends FlowNode> BehaviorRegistry register(FlowNodeBehavior<N> behavior) {
        Key key = new Key(behavior.nodeType(), behavior.eventDefinition());
        if (behaviors.putIfAbsent(key, behavior) != null) {
            throw new IllegalStateException("Two behaviours for " + behavior.nodeType().getSimpleName() + " with "
                    + behavior.eventDefinition());
        }
        return this;
    }

    /**
     * @param step a compile step
     * @return this registry
     */
    public BehaviorRegistry compileStep(CompileStep step) {
        compileSteps.add(step);
        return this;
    }

    /**
     * @param step a step run when the agenda of an apply is empty
     * @return this registry
     */
    public BehaviorRegistry settleStep(SettleStep step) {
        settleSteps.add(step);
        return this;
    }

    /**
     * Looks up the behaviour of a node whose event definitions are inline; a reference resolves to nothing, so a node
     * that only references a shared definition finds no behaviour.
     *
     * @param node a node
     * @param <N> node type
     * @return its behaviour
     */
    public <N extends FlowNode> Optional<FlowNodeBehavior<N>> behaviorFor(N node) {
        return behaviorFor(node, id -> Optional.empty());
    }

    /**
     * @param node a node
     * @param shared resolves shared event definitions by id
     * @param <N> node type
     * @return its behaviour, by exact key
     */
    @SuppressWarnings("unchecked")
    public <N extends FlowNode> Optional<FlowNodeBehavior<N>> behaviorFor(N node,
            Function<String, Optional<EventDefinition>> shared) {
        Key key = new Key(node.getClass(), EventDefinitionKey.of(node, shared));
        return Optional.ofNullable((FlowNodeBehavior<N>) behaviors.get(key));
    }

    /** @return settle steps in registration order */
    public List<SettleStep> settleSteps() {
        return List.copyOf(settleSteps);
    }

    /** @return compile steps in registration order */
    public List<CompileStep> compileSteps() {
        return List.copyOf(compileSteps);
    }
}
