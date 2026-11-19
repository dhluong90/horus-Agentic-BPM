package io.github.dhluong90.bpm.process.internal.exec;

import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Behaviours by node type and compile steps, filled once by the {@link BehaviorModule}s. */
public final class BehaviorRegistry {

    private final Map<Class<?>, FlowNodeBehavior<?>> behaviors = new LinkedHashMap<>();
    private final List<CompileStep> compileSteps = new ArrayList<>();

    /**
     * @param behavior a behaviour; a second behaviour for the same node type is refused
     * @param <N> node type
     * @return this registry
     */
    public <N extends FlowNode> BehaviorRegistry register(FlowNodeBehavior<N> behavior) {
        if (behaviors.putIfAbsent(behavior.nodeType(), behavior) != null) {
            throw new IllegalStateException("Two behaviours for " + behavior.nodeType().getSimpleName());
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
     * @param node a node
     * @param <N> node type
     * @return its behaviour
     */
    @SuppressWarnings("unchecked")
    public <N extends FlowNode> Optional<FlowNodeBehavior<N>> behaviorFor(N node) {
        return Optional.ofNullable((FlowNodeBehavior<N>) behaviors.get(node.getClass()));
    }

    /** @return compile steps in registration order */
    public List<CompileStep> compileSteps() {
        return List.copyOf(compileSteps);
    }
}
