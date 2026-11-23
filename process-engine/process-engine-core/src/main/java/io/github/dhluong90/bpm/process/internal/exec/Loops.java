package io.github.dhluong90.bpm.process.internal.exec;

import io.github.dhluong90.bpm.process.internal.model.bpmn.Activity;
import io.github.dhluong90.bpm.process.internal.model.bpmn.CallActivity;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Expression;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import io.github.dhluong90.bpm.process.internal.model.bpmn.LoopCharacteristics;
import io.github.dhluong90.bpm.process.internal.model.bpmn.MultiInstanceLoopCharacteristics;
import io.github.dhluong90.bpm.process.internal.model.bpmn.StandardLoopCharacteristics;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SubProcess;
import io.github.dhluong90.bpm.process.internal.model.horus.ResultVariable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Standard loops and multi-instance activities (#496, ADR-0020). An activity with loop characteristics is entered
 * through a {@code MI_BODY} token that holds the counters; each repetition is a {@code SCOPE} token under it with its
 * own {@code loopCounter} and element variable, and one flow token on the activity (or, for an embedded sub-process,
 * at its start event) under that scope. A repetition ends when its flow token leaves the activity, or when its
 * sub-process scope ends; the body then decides what comes next: the next repetition, the end of the loop, or the
 * cancellation of the repetitions still running. Pure: it works on the {@link WorkingContext} of one apply.
 */
final class Loops {

    static final String LOOP_COUNTER = "loopCounter";
    static final String NR_OF_INSTANCES = "nrOfInstances";
    static final String NR_OF_ACTIVE_INSTANCES = "nrOfActiveInstances";
    static final String NR_OF_COMPLETED_INSTANCES = "nrOfCompletedInstances";

    /** Incident code: more repetitions than the engine allows for one activity ({@code multi-instance.max}). */
    static final String LIMIT_EXCEEDED = "MULTI_INSTANCE_LIMIT";
    /** Incident code: the input collection is missing, not a list, or the cardinality is not a whole number. */
    static final String COLLECTION_INVALID = "MULTI_INSTANCE_COLLECTION_INVALID";

    // Internal state of a body, kept in its locals; the names start with an underscore so no variable can be named so.
    private static final String ITEMS = "_items";
    private static final String OUTPUTS = "_outputs";
    private static final String NEXT = "_next";

    private final WorkingContext ctx;
    private final int maxInstances;

    Loops(WorkingContext ctx, int maxInstances) {
        this.ctx = ctx;
        this.maxInstances = maxInstances;
    }

    /** @return the loop characteristics of an activity, or null */
    static LoopCharacteristics of(FlowNode node) {
        return node instanceof Activity activity ? activity.activity().loopCharacteristics() : null;
    }

    /** @return whether a token arriving on the node starts a loop; false for the flow token of a repetition itself */
    boolean startsLoop(FlowNode node, Token token) {
        return of(node) != null && !isInstanceScope(ctx.tree().get(token.scopeTokenId() == null ? "" : token.scopeTokenId())
                .orElse(null), node.id());
    }

    private boolean isInstanceScope(Token scope, String nodeId) {
        return scope != null && scope.kind() == TokenKind.SCOPE && scope.nodeId().equals(nodeId)
                && scope.scopeTokenId() != null
                && ctx.tree().get(scope.scopeTokenId()).map(up -> up.kind() == TokenKind.MI_BODY).orElse(false);
    }

    // ---- entering ---------------------------------------------------------------------------------------------

    /** The token arrived at an activity with loop characteristics. */
    void enter(FlowNode node, Token token) {
        switch (of(node)) {
            case MultiInstanceLoopCharacteristics mi -> enterMultiInstance(node, token, mi);
            case StandardLoopCharacteristics loop -> enterLoop(node, token, loop);
            default -> throw new ExecutionException("NOT_EXECUTABLE", node.id(), "No loop characteristics on " + node.id());
        }
    }

    private void enterMultiInstance(FlowNode node, Token token, MultiInstanceLoopCharacteristics mi) {
        List<Object> items = null;
        int count;
        if (mi.loopDataInputRef() != null) {
            Object value = ctx.variables().visible(token).get(mi.loopDataInputRef());
            if (!(value instanceof List<?> list)) {
                refuse(node, token, COLLECTION_INVALID, "Variable " + mi.loopDataInputRef() + " is "
                        + (value == null ? "not set" : "not a list"));
                return;
            }
            items = new ArrayList<>(list);
            count = items.size();
        } else {
            Object value = mi.loopCardinality() == null ? null
                    : ctx.evaluate(mi.loopCardinality().text(), languageOf(mi.loopCardinality()), token);
            if (!(value instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()) || n.intValue() < 0) {
                refuse(node, token, COLLECTION_INVALID, "The cardinality is not a whole number of 0 or more");
                return;
            }
            count = n.intValue();
        }
        if (count > maxInstances) {
            refuse(node, token, LIMIT_EXCEEDED, count + " instances; the limit is " + maxInstances
                    + " (multi-instance.max)");
            return;
        }
        Map<String, Object> locals = new LinkedHashMap<>();
        locals.put(NR_OF_INSTANCES, count);
        locals.put(NR_OF_ACTIVE_INSTANCES, 0);
        locals.put(NR_OF_COMPLETED_INSTANCES, 0);
        locals.put(NEXT, 0);
        if (items != null) {
            locals.put(ITEMS, items);
        }
        if (mi.loopDataOutputRef() != null) {
            List<Object> outputs = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                outputs.add(null);
            }
            locals.put(OUTPUTS, outputs);
            ctx.variables().set(mi.loopDataOutputRef(), new ArrayList<>(outputs));
        }
        Token body = openBody(node, token, locals);
        ctx.history("MI_STARTED", body, counts(body, null));
        if (count == 0) {
            finish(node, body, mi);
        } else if (mi.isSequential()) {
            startInstance(node, body, mi, 0);
        } else {
            for (int i = 0; i < count; i++) {
                startInstance(node, ctx.tree().get(body.id()).orElseThrow(), mi, i);
            }
        }
    }

    private void enterLoop(FlowNode node, Token token, StandardLoopCharacteristics loop) {
        if (loop.loopMaximum() == null) {
            throw new ExecutionException("LOOP_UNBOUNDED", node.id(), "A loop has a loopMaximum");
        }
        if (loop.loopMaximum() > maxInstances) {
            refuse(node, token, LIMIT_EXCEEDED, "loopMaximum " + loop.loopMaximum() + "; the limit is " + maxInstances
                    + " (multi-instance.max)");
            return;
        }
        Map<String, Object> locals = new LinkedHashMap<>();
        locals.put(LOOP_COUNTER, 0);
        locals.put(NR_OF_ACTIVE_INSTANCES, 0);
        locals.put(NR_OF_COMPLETED_INSTANCES, 0);
        locals.put(NEXT, 0);
        Token body = openBody(node, token, locals);
        ctx.history("MI_STARTED", body, counts(body, null));
        if (loop.testBefore() && !holds(loop.loopCondition(), body)) {
            finish(node, body, null);
        } else {
            startInstance(node, body, null, 0);
        }
    }

    /** The arriving token ends, and a body token takes its place in the same scope. */
    private Token openBody(FlowNode node, Token arriving, Map<String, Object> locals) {
        ctx.update(arriving.withStatus(TokenStatus.COMPLETED));
        Token created = ctx.createToken(TokenKind.MI_BODY, node.id(), arriving.scopeTokenId());
        Token body = new Token(created.id(), TokenKind.MI_BODY, TokenStatus.WAITING_SCOPE, arriving.scopeTokenId(),
                node.id(), null, locals);
        ctx.update(body);
        return body;
    }

    /**
     * The step cannot start: the token waits on the step as for a failed call, the shell opens an incident, and no
     * task, job or child is created. An operator corrects the variable and retries the step.
     */
    private void refuse(FlowNode node, Token token, String code, String detail) {
        String attemptId = ctx.nextId();
        ctx.update(new Token(token.id(), token.kind(), TokenStatus.WAITING_ASYNC, token.scopeTokenId(), token.nodeId(),
                attemptId, token.locals()));
        ctx.history("MI_REFUSED", token, Map.of("code", code));
        ctx.effect(new Effect.RaiseIncident(token.id(), node.id(), code, detail));
    }

    // ---- one repetition ---------------------------------------------------------------------------------------

    private void startInstance(FlowNode node, Token body, MultiInstanceLoopCharacteristics mi, int index) {
        Map<String, Object> locals = new LinkedHashMap<>();
        locals.put(LOOP_COUNTER, index + 1);
        if (mi != null && mi.inputDataItem() != null && body.locals().get(ITEMS) instanceof List<?> items) {
            locals.put(mi.inputDataItem().name(), items.get(index));
        }
        Token created = ctx.createToken(TokenKind.SCOPE, node.id(), body.id());
        Token scope = new Token(created.id(), TokenKind.SCOPE, TokenStatus.WAITING_SCOPE, body.id(), node.id(), null,
                locals);
        ctx.update(scope);
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put(NEXT, index + 1);
        changes.put(NR_OF_ACTIVE_INSTANCES, count(body, NR_OF_ACTIVE_INSTANCES) + 1);
        if (mi == null) {
            // A loop condition reads the counter of the repetition that just ended.
            changes.put(LOOP_COUNTER, index + 1);
        }
        Token updated = withCounters(body, changes);
        ctx.update(updated);
        ctx.history("MI_INSTANCE_STARTED", scope, counts(updated, index + 1));
        FlowNode entry = node instanceof SubProcess ? ctx.process().startOf(node.id()).orElseThrow(() ->
                new ExecutionException("NO_START_EVENT", node.id(), "Sub-process " + node.id() + " has no start event"))
                : node;
        ctx.enqueue(ctx.createToken(TokenKind.FLOW, entry.id(), scope.id()));
    }

    /** @return whether the flow token leaving {@code from} is the end of one repetition, and if so ends it */
    boolean endsInstance(Token token, FlowNode from) {
        Token scope = token.scopeTokenId() == null ? null : ctx.tree().get(token.scopeTokenId()).orElse(null);
        if (!isInstanceScope(scope, from.id()) || token.kind() != TokenKind.FLOW) {
            return false;
        }
        ctx.update(token.withStatus(TokenStatus.COMPLETED));
        instanceEnded(scope);
        return true;
    }

    /** @return whether the scope that just ended is the scope of one repetition of a sub-process, and if so ends it */
    boolean endsInstanceScope(Token scope) {
        if (!isInstanceScope(scope, scope.nodeId())) {
            return false;
        }
        instanceEnded(scope);
        return true;
    }

    private void instanceEnded(Token scope) {
        Token body = ctx.tree().get(scope.scopeTokenId()).orElseThrow();
        FlowNode node = ctx.process().node(body.nodeId()).orElseThrow();
        LoopCharacteristics loop = of(node);
        Object output = outputOf(node, loop, scope);
        ctx.update(scope.withStatus(TokenStatus.COMPLETED));
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put(NR_OF_ACTIVE_INSTANCES, count(body, NR_OF_ACTIVE_INSTANCES) - 1);
        changes.put(NR_OF_COMPLETED_INSTANCES, count(body, NR_OF_COMPLETED_INSTANCES) + 1);
        int counter = count(scope, LOOP_COUNTER);
        if (loop instanceof MultiInstanceLoopCharacteristics mi && mi.loopDataOutputRef() != null
                && body.locals().get(OUTPUTS) instanceof List<?> previous) {
            List<Object> outputs = new ArrayList<>(previous);
            outputs.set(counter - 1, output);
            changes.put(OUTPUTS, outputs);
            ctx.variables().set(mi.loopDataOutputRef(), new ArrayList<>(outputs));
        }
        Token updated = withCounters(body, changes);
        ctx.update(updated);
        ctx.history("MI_INSTANCE_COMPLETED", scope, counts(updated, counter));
        switch (loop) {
            case StandardLoopCharacteristics standard -> {
                boolean again = counter < standard.loopMaximum() && holds(standard.loopCondition(), updated);
                if (again) {
                    startInstance(node, updated, null, counter);
                } else {
                    finish(node, updated, null);
                }
            }
            case MultiInstanceLoopCharacteristics mi -> {
                boolean done = count(updated, NR_OF_COMPLETED_INSTANCES) >= count(updated, NR_OF_INSTANCES);
                boolean early = !done && mi.completionCondition() != null && holds(mi.completionCondition(), updated);
                if (done || early) {
                    finish(node, updated, mi);
                } else if (mi.isSequential()) {
                    startInstance(node, updated, mi, count(updated, NEXT));
                }
            }
            default -> throw new ExecutionException("NOT_EXECUTABLE", node.id(), "No loop characteristics on " + node.id());
        }
    }

    /** The value an instance contributes to the output collection: {@code horus:outputElement}, else the result variable. */
    private Object outputOf(FlowNode node, LoopCharacteristics loop, Token scope) {
        if (!(loop instanceof MultiInstanceLoopCharacteristics mi)) {
            return null;
        }
        String name = mi.outputDataItem() != null ? mi.outputDataItem().name()
                : node.extensions().first(ResultVariable.class).map(ResultVariable::name).orElse(null);
        if (name == null) {
            return null;
        }
        Object value = ctx.variables().visible(scope);
        for (String part : name.split("\\.")) {
            value = value instanceof Map<?, ?> map ? map.get(part) : null;
        }
        return value;
    }

    // ---- the end of the loop -----------------------------------------------------------------------------------

    /** The loop is over: repetitions still running are cancelled, and a token leaves the activity in the enclosing scope. */
    private void finish(FlowNode node, Token body, MultiInstanceLoopCharacteristics mi) {
        Token current = body;
        for (Token instance : ctx.tree().children(body.id())) {
            if (instance.status().live()) {
                cancelInstance(instance);
                Token latest = ctx.tree().get(body.id()).orElseThrow();
                current = withCounters(latest, Map.of(NR_OF_ACTIVE_INSTANCES, count(latest, NR_OF_ACTIVE_INSTANCES) - 1));
                ctx.update(current);
                ctx.history("MI_INSTANCE_CANCELLED", instance, counts(current, count(instance, LOOP_COUNTER)));
            }
        }
        ctx.update(current.withStatus(TokenStatus.COMPLETED));
        ctx.history("MI_COMPLETED", current, counts(current, null));
        Token next = ctx.createToken(TokenKind.FLOW, node.id(), body.scopeTokenId());
        ctx.history("NODE_COMPLETED", next, Map.of());
        ctx.takeOutgoing(next, node);
    }

    private void cancelInstance(Token instance) {
        for (Token flow : ctx.tree().live(instance.id())) {
            if (flow.kind() == TokenKind.FLOW && flow.status() == TokenStatus.WAITING_CHILD
                    && ctx.process().node(flow.nodeId()).orElse(null) instanceof CallActivity) {
                ctx.effect(new Effect.CancelChildInstance(flow.id()));
            }
        }
        ctx.cancelSubtree(instance);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private boolean holds(Expression condition, Token in) {
        if (condition == null || condition.text() == null || condition.text().isBlank()) {
            return true;
        }
        Object value = ctx.evaluate(condition.text(), languageOf(condition), in);
        if (!(value instanceof Boolean result)) {
            throw new ExecutionException("CONDITION_ERROR", in.nodeId(), "A loop condition of " + in.nodeId()
                    + " did not produce a boolean");
        }
        return result;
    }

    private static String languageOf(Expression expression) {
        return expression.language() == null ? "urn:horus-bpm:expr:v1" : expression.language();
    }

    private static Token withCounters(Token token, Map<String, Object> changes) {
        Map<String, Object> locals = new LinkedHashMap<>(token.locals());
        locals.putAll(changes);
        return new Token(token.id(), token.kind(), token.status(), token.scopeTokenId(), token.nodeId(),
                token.activityInstanceId(), locals);
    }

    private static int count(Token token, String key) {
        return token.locals().get(key) instanceof Number n ? n.intValue() : 0;
    }

    /** The counters a history entry and an expression see, and the repetition the entry is about. */
    private static Map<String, Object> counts(Token body, Integer loopCounter) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (loopCounter != null) {
            out.put(LOOP_COUNTER, loopCounter);
        }
        for (String key : List.of(NR_OF_INSTANCES, NR_OF_ACTIVE_INSTANCES, NR_OF_COMPLETED_INSTANCES)) {
            if (body.locals().get(key) instanceof Number n) {
                out.put(key, n.intValue());
            }
        }
        return out;
    }
}
