package io.github.dhluong90.bpm.process.internal.exec;

import io.github.dhluong90.bpm.process.internal.model.bpmn.Event;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * The second half of a behaviour key: which event definition a node carries. {@link #NONE} for every node that is
 * not an event and for none events; the definition class for an event with exactly one definition, inline or by
 * reference; {@link #MULTIPLE} for an event with more than one (process engine DESIGN section 5.8). A reference
 * that cannot be resolved gives {@link #UNRESOLVED}, which no behaviour registers, so the node is not executable.
 *
 * @param definition the event definition class, or null for {@code NONE}, {@code MULTIPLE} and {@code UNRESOLVED}
 * @param kind the kind of key
 */
public record EventDefinitionKey(Class<? extends EventDefinition> definition, Kind kind) {

    /** Kinds of key. */
    public enum Kind { NONE, ONE, MULTIPLE, UNRESOLVED }

    /** A node without event definitions. */
    public static final EventDefinitionKey NONE = new EventDefinitionKey(null, Kind.NONE);
    /** An event with more than one event definition. */
    public static final EventDefinitionKey MULTIPLE = new EventDefinitionKey(null, Kind.MULTIPLE);
    /** An event whose single definition reference does not resolve. */
    public static final EventDefinitionKey UNRESOLVED = new EventDefinitionKey(null, Kind.UNRESOLVED);

    public EventDefinitionKey {
        if ((kind == Kind.ONE) != (definition != null)) {
            throw new IllegalArgumentException("A ONE key names exactly one definition class; other kinds name none");
        }
    }

    /**
     * @param definition the event definition class
     * @return the key for an event with exactly that one definition
     */
    public static EventDefinitionKey of(Class<? extends EventDefinition> definition) {
        return new EventDefinitionKey(definition, Kind.ONE);
    }

    /**
     * The key of a node, with shared definitions resolved by id.
     *
     * @param node the node
     * @param shared resolves {@code eventDefinitionRef} ids to shared definitions
     * @return the key
     */
    public static EventDefinitionKey of(FlowNode node, Function<String, Optional<EventDefinition>> shared) {
        if (!(node instanceof Event event)) {
            return NONE;
        }
        int count = event.eventDefinitions().size() + event.eventDefinitionRefs().size();
        if (count == 0) {
            return NONE;
        }
        if (count > 1) {
            return MULTIPLE;
        }
        List<EventDefinition> all = new ArrayList<>(event.eventDefinitions());
        if (all.isEmpty()) {
            Optional<EventDefinition> resolved = shared.apply(event.eventDefinitionRefs().get(0));
            if (resolved.isEmpty()) {
                return UNRESOLVED;
            }
            all.add(resolved.get());
        }
        return of(all.get(0).getClass());
    }
}
