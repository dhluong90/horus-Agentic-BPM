package io.github.dhluong90.bpm.process.internal.exec.behavior.event;

import io.github.dhluong90.bpm.process.internal.exec.Command;
import io.github.dhluong90.bpm.process.internal.exec.EventDefinitionKey;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionException;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.MultipleWaits;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.exec.TokenKind;
import io.github.dhluong90.bpm.process.internal.exec.TokenStatus;
import io.github.dhluong90.bpm.process.internal.model.bpmn.BoundaryEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ConditionalEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import io.github.dhluong90.bpm.process.internal.model.bpmn.IntermediateCatchEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.MessageEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SignalEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.StartEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.TimerEventDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Multiple and parallel multiple events (#500): an intermediate catch event, the start event of an event sub-process
 * and a boundary event with several definitions. The event waits with one token per definition, each parked by the
 * behaviour of that single definition (a timer job, a message or signal subscription, a condition). A multiple event
 * fires on the first definition that occurs and cancels the other waits; a parallel multiple event fires when every
 * definition has occurred, each occurrence holding its token as {@code JOINED} meanwhile. The single behaviours run
 * unchanged on a one-definition copy of the node; the moment one of them would leave the event is caught and decided
 * here.
 */
final class MultipleEvents {

    private MultipleEvents() {
    }

    /** What a single behaviour would have done when its definition occurred. */
    private static final class Fired {
        boolean fired;
        String trigger;
        Map<String, Object> details = Map.of();
        String exceptWork;
    }

    private static boolean waits(EventDefinition d) {
        return d instanceof TimerEventDefinition || d instanceof MessageEventDefinition
                || d instanceof SignalEventDefinition || d instanceof ConditionalEventDefinition;
    }

    /** A context that records the leave of a single behaviour instead of doing it; everything else is the real one's. */
    private static ExecutionContext catching(ExecutionContext real, Fired out) {
        return new Catching(real, out);
    }

    private record Catching(ExecutionContext real, Fired out) implements ExecutionContext {
        @Override public io.github.dhluong90.bpm.process.internal.exec.TokenTree tree() { return real.tree(); }
        @Override public io.github.dhluong90.bpm.process.internal.exec.ScopeWalker scopes() { return real.scopes(); }
        @Override public io.github.dhluong90.bpm.process.internal.exec.VariableScopes variables() { return real.variables(); }
        @Override public io.github.dhluong90.bpm.process.internal.exec.CompiledProcess process() { return real.process(); }
        @Override public void takeOutgoing(Token token, FlowNode from) { out.fired = true; }
        @Override public void take(Token token, String sequenceFlowId) { real.take(token, sequenceFlowId); }
        @Override public Token createToken(TokenKind kind, String nodeId, String scopeTokenId) {
            return real.createToken(kind, nodeId, scopeTokenId);
        }
        @Override public void update(Token token) { real.update(token); }
        @Override public void complete(Token token) { real.complete(token); }
        @Override public void cancelToken(Token token, io.github.dhluong90.bpm.process.api.CancelCause cause) { real.cancelToken(token, cause); }
        @Override public void cancelSubtree(Token scope, io.github.dhluong90.bpm.process.api.CancelCause cause) { real.cancelSubtree(scope, cause); }
        @Override public void enterScope(Token token, FlowNode block) { real.enterScope(token, block); }
        @Override public void compensate(Token failing, String cause) { real.compensate(failing, cause); }
        @Override public boolean throwError(Token source, String code, boolean fromStep) {
            return real.throwError(source, code, fromStep);
        }
        @Override public boolean throwEscalation(Token thrower, String code) { return real.throwEscalation(thrower, code); }
        @Override public void terminate(Token token) { real.terminate(token); }
        @Override public void jump(Token token, String nodeId) { real.jump(token, nodeId); }
        @Override public void fireBoundary(Token boundaryToken, BoundaryEvent boundary) { out.fired = true; }
        @Override public void startEventSubProcess(Token scope, StartEvent start, String trigger,
                Map<String, Object> details, String exceptWorkOf) {
            out.fired = true;
            out.trigger = trigger;
            out.details = details;
            out.exceptWork = exceptWorkOf;
        }
        @Override public void conditionArmed() { real.conditionArmed(); }
        @Override public void effect(io.github.dhluong90.bpm.process.internal.exec.Effect effect) { real.effect(effect); }
        @Override public void history(io.github.dhluong90.bpm.process.internal.exec.HistoryEntry entry) { real.history(entry); }
        @Override public void history(String type, Token token, Map<String, Object> details) {
            real.history(type, token, details);
        }
        @Override public String nextId() { return real.nextId(); }
        @Override public Object evaluate(String expression, String languageId, Token in) {
            return real.evaluate(expression, languageId, in);
        }
        @Override public <T> T facet(io.github.dhluong90.bpm.process.internal.exec.FacetKey<T> key) { return real.facet(key); }
        @Override public String actor() { return real.actor(); }
        @Override public java.time.Instant now() { return real.now(); }
    }

    private abstract static class Base<N extends FlowNode> implements FlowNodeBehavior<N> {

        abstract List<EventDefinition> definitions(N node);

        abstract boolean parallel(N node);

        abstract N project(N node, EventDefinition definition);

        abstract FlowNodeBehavior<N> single(EventDefinition definition);

        /** Leaves the event, after its definitions occurred: {@code token} is the one that completes it. */
        abstract void leave(N node, Token token, Fired fired, ExecutionContext ctx);

        @Override
        public EventDefinitionKey eventDefinition() {
            return EventDefinitionKey.MULTIPLE;
        }

        /** Parks one token per waiting definition; the arriving token is the first. */
        void arm(N node, Token token, ExecutionContext ctx) {
            List<EventDefinition> defs = definitions(node);
            String group = token.id();
            Token next = token;
            boolean first = true;
            for (int i = 0; i < defs.size(); i++) {
                EventDefinition d = defs.get(i);
                if (!waits(d)) {
                    continue;
                }
                Token t = first ? next : ctx.createToken(TokenKind.FLOW, node.id(), token.scopeTokenId());
                first = false;
                Map<String, Object> locals = new LinkedHashMap<>(t.locals());
                locals.put(MultipleWaits.DEF, i);
                locals.put(MultipleWaits.GROUP, group);
                t = new Token(t.id(), t.kind(), t.status(), t.scopeTokenId(), t.nodeId(), t.activityInstanceId(), locals);
                ctx.update(t);
                Fired fired = new Fired();
                single(d).enter(project(node, d), t, catching(ctx, fired));
                if (fired.fired) {
                    // The definition held on arrival (a condition that is already true).
                    occurred(node, ctx.tree().get(t.id()).orElseThrow(), fired, ctx);
                    if (!parallel(node)) {
                        return;
                    }
                }
            }
        }

        @Override
        public void trigger(N node, Token token, Command trigger, ExecutionContext ctx) {
            int index = MultipleWaits.definitionOf(token);
            List<EventDefinition> defs = definitions(node);
            if (index < 0 || index >= defs.size()) {
                throw new ExecutionException("INVALID_STATE", node.id(), "Token " + token.id()
                        + " waits on no definition of " + node.id());
            }
            EventDefinition d = defs.get(index);
            Fired fired = new Fired();
            single(d).trigger(project(node, d), token, trigger, catching(ctx, fired));
            if (fired.fired) {
                occurred(node, token, fired, ctx);
            }
        }

        private List<Token> group(Token token, ExecutionContext ctx) {
            Object group = token.locals().get(MultipleWaits.GROUP);
            List<Token> out = new ArrayList<>();
            for (Token t : ctx.tree().children(token.scopeTokenId())) {
                if (t.kind() == TokenKind.FLOW && t.nodeId().equals(token.nodeId()) && !t.id().equals(token.id())
                        && group != null && group.equals(t.locals().get(MultipleWaits.GROUP))) {
                    out.add(t);
                }
            }
            return out;
        }

        private void occurred(N node, Token token, Fired fired, ExecutionContext ctx) {
            List<Token> siblings = group(token, ctx);
            if (!parallel(node)) {
                siblings.forEach(sibling -> ctx.cancelToken(sibling, io.github.dhluong90.bpm.process.api.CancelCause.MULTIPLE_EVENT));
                leave(node, token, fired, ctx);
                return;
            }
            ctx.history("EVENT_DEFINITION_OCCURRED", token, Map.of("definition", MultipleWaits.definitionOf(token)));
            Map<String, Object> locals = new LinkedHashMap<>(token.locals());
            locals.put(MultipleWaits.FIRED, true);
            Token held = new Token(token.id(), token.kind(), TokenStatus.JOINED, token.scopeTokenId(), token.nodeId(),
                    null, locals);
            ctx.update(held);
            if (siblings.stream().anyMatch(t -> t.status().live())) {
                return;
            }
            siblings.stream().filter(t -> t.status() == TokenStatus.JOINED)
                    .forEach(t -> ctx.update(t.withStatus(TokenStatus.COMPLETED)));
            leave(node, held, fired, ctx);
        }

        Token clean(Token token, ExecutionContext ctx) {
            Token active = new Token(token.id(), token.kind(), TokenStatus.ACTIVE, token.scopeTokenId(), token.nodeId(),
                    null, MultipleWaits.withoutMarks(token));
            ctx.update(active);
            return active;
        }
    }

    /** Intermediate catch event with several definitions. */
    static final class Catch extends Base<IntermediateCatchEvent> {

        private final TimerCatchEventBehavior timer = new TimerCatchEventBehavior();
        private final SignalCatchEventBehavior signal = new SignalCatchEventBehavior();
        private final MessageCatchEventBehavior message = new MessageCatchEventBehavior();
        private final ConditionalCatchEventBehavior condition = new ConditionalCatchEventBehavior();

        @Override
        public Class<IntermediateCatchEvent> nodeType() {
            return IntermediateCatchEvent.class;
        }

        @Override
        List<EventDefinition> definitions(IntermediateCatchEvent node) {
            return node.eventDefinitions();
        }

        @Override
        boolean parallel(IntermediateCatchEvent node) {
            return node.parallelMultiple();
        }

        @Override
        IntermediateCatchEvent project(IntermediateCatchEvent n, EventDefinition d) {
            return new IntermediateCatchEvent(n.id(), n.name(), n.incoming(), n.outgoing(), List.of(d), List.of(),
                    n.data(), false, n.info());
        }

        @Override
        FlowNodeBehavior<IntermediateCatchEvent> single(EventDefinition d) {
            return switch (d) {
                case TimerEventDefinition t -> timer;
                case SignalEventDefinition s -> signal;
                case MessageEventDefinition m -> message;
                default -> condition;
            };
        }

        @Override
        public void enter(IntermediateCatchEvent node, Token token, ExecutionContext ctx) {
            arm(node, token, ctx);
        }

        @Override
        void leave(IntermediateCatchEvent node, Token token, Fired fired, ExecutionContext ctx) {
            Token active = clean(token, ctx);
            ctx.history("NODE_COMPLETED", active, Map.of("multiple", true));
            ctx.takeOutgoing(active, node);
        }
    }

    /** Start event of an event sub-process (or of a process) with several definitions. */
    static final class Start extends Base<StartEvent> {

        private final EventSubProcessStarts.Timer timer = new EventSubProcessStarts.Timer();
        private final SignalStartEventBehavior signal = new SignalStartEventBehavior();
        private final MessageStartEventBehavior message = new MessageStartEventBehavior();
        private final ConditionalStartEventBehavior condition = new ConditionalStartEventBehavior();

        @Override
        public Class<StartEvent> nodeType() {
            return StartEvent.class;
        }

        @Override
        List<EventDefinition> definitions(StartEvent node) {
            return node.eventDefinitions();
        }

        @Override
        boolean parallel(StartEvent node) {
            return node.parallelMultiple();
        }

        @Override
        StartEvent project(StartEvent n, EventDefinition d) {
            // Interrupting, so the single behaviour does not re-arm itself: the group re-arms as a whole.
            return new StartEvent(n.id(), n.name(), n.incoming(), n.outgoing(), List.of(d), List.of(), n.data(), false,
                    true, n.info());
        }

        @Override
        FlowNodeBehavior<StartEvent> single(EventDefinition d) {
            return switch (d) {
                case TimerEventDefinition t -> timer;
                case SignalEventDefinition s -> signal;
                case MessageEventDefinition m -> message;
                default -> condition;
            };
        }

        @Override
        public void enter(StartEvent node, Token token, ExecutionContext ctx) {
            if (!EventSubProcessStarts.listening(node, token, ctx)) {
                EventSubProcessStarts.leave(node, token, ctx);
                return;
            }
            arm(node, token, ctx);
        }

        @Override
        void leave(StartEvent node, Token token, Fired fired, ExecutionContext ctx) {
            Token scope = EventSubProcessStarts.scopeOf(token, ctx);
            ctx.update(token.withStatus(TokenStatus.COMPLETED));
            ctx.startEventSubProcess(scope, node, fired.trigger == null ? "MULTIPLE" : fired.trigger, fired.details,
                    fired.exceptWork);
            if (!node.isInterrupting()) {
                Token again = EventSubProcessStarts.again(node, token, ctx);
                enter(node, again, ctx);
            }
        }
    }

    /** Boundary event with several definitions: its timers wait, its error and escalation are found when thrown. */
    static final class Boundary extends Base<BoundaryEvent> {

        private final BoundaryTimerBehavior timer = new BoundaryTimerBehavior();

        @Override
        public Class<BoundaryEvent> nodeType() {
            return BoundaryEvent.class;
        }

        @Override
        List<EventDefinition> definitions(BoundaryEvent node) {
            return node.eventDefinitions();
        }

        @Override
        boolean parallel(BoundaryEvent node) {
            return node.parallelMultiple();
        }

        @Override
        BoundaryEvent project(BoundaryEvent n, EventDefinition d) {
            return new BoundaryEvent(n.id(), n.name(), n.incoming(), n.outgoing(), List.of(d), List.of(), n.data(),
                    false, n.attachedToRef(), n.cancelActivity(), n.info());
        }

        @Override
        FlowNodeBehavior<BoundaryEvent> single(EventDefinition d) {
            return timer;
        }

        @Override
        public void enter(BoundaryEvent node, Token token, ExecutionContext ctx) {
            arm(node, token, ctx);
        }

        @Override
        void leave(BoundaryEvent node, Token token, Fired fired, ExecutionContext ctx) {
            Token active = clean(token, ctx);
            ctx.fireBoundary(active, node);
        }
    }
}
