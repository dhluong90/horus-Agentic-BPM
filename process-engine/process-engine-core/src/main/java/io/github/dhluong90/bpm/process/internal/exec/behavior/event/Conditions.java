package io.github.dhluong90.bpm.process.internal.exec.behavior.event;

import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionException;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ConditionalEventDefinition;

/** The condition of a conditional event, evaluated on the token's variables (#492). */
final class Conditions {

    private Conditions() {
    }

    /**
     * Evaluates a condition on arrival. A value that is not a boolean is a defect of the definition and fails the step,
     * as it does at an exclusive gateway.
     */
    static boolean holdsOnArrival(ConditionalEventDefinition definition, Token token, ExecutionContext ctx) {
        Object value = ctx.evaluate(definition.condition().text(), definition.condition().language(), token);
        if (!(value instanceof Boolean b)) {
            throw new ExecutionException("CONDITION_ERROR", token.nodeId(), "The condition did not produce a boolean");
        }
        return b;
    }

    /**
     * Asks a waiting condition again after a variable changed. A condition that cannot be evaluated now, for example
     * because a variable it reads is not set yet, is not true: it must never fail the unrelated step that changed the
     * variables.
     */
    static boolean holdsNow(ConditionalEventDefinition definition, Token token, ExecutionContext ctx) {
        try {
            return ctx.evaluate(definition.condition().text(), definition.condition().language(), token)
                    instanceof Boolean b && b;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
