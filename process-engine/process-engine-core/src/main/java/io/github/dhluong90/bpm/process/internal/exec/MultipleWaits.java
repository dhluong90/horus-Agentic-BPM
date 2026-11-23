package io.github.dhluong90.bpm.process.internal.exec;

import io.github.dhluong90.bpm.process.internal.model.bpmn.ConditionalEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Event;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * What a multiple event (#500) writes on its tokens. An event with several definitions waits with one token per
 * definition, all on the same node and in the same scope; each carries the index of its definition in
 * {@link #DEF} and the id of the first token of the group in {@link #GROUP}. A parallel multiple event marks a token
 * whose definition occurred with {@link #FIRED}.
 */
public final class MultipleWaits {

    /** The local that holds the index of the definition a wait token stands for. */
    public static final String DEF = "$def";
    /** The local that holds the id of the group a wait token belongs to: the first token of the event. */
    public static final String GROUP = "$group";
    /** The local that marks a wait token of a parallel multiple event whose definition has occurred. */
    public static final String FIRED = "$fired";

    private MultipleWaits() {
    }

    /**
     * @param token a token
     * @return the index of the definition it stands for, or -1 when it is not a wait of a multiple event
     */
    public static int definitionOf(Token token) {
        return token.locals().get(DEF) instanceof Number n ? n.intValue() : -1;
    }

    /**
     * @param token a token
     * @return its locals without the ones a multiple event adds
     */
    public static Map<String, Object> withoutMarks(Token token) {
        return token.locals().entrySet().stream()
                .filter(e -> !DEF.equals(e.getKey()) && !GROUP.equals(e.getKey()) && !FIRED.equals(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * @param node the node a waiting token is on
     * @param token the token
     * @return whether it waits on a condition, so the kernel asks it again when the variables change
     */
    public static boolean waitsOnCondition(FlowNode node, Token token) {
        if (!(node instanceof Event event)) {
            return false;
        }
        List<EventDefinition> definitions = event.eventDefinitions();
        if (definitions.size() == 1) {
            return definitions.get(0) instanceof ConditionalEventDefinition;
        }
        int index = definitionOf(token);
        return index >= 0 && index < definitions.size() && definitions.get(index) instanceof ConditionalEventDefinition;
    }
}
