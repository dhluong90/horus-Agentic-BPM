package io.github.dhluong90.bpm.process.internal.exec;

import io.github.dhluong90.bpm.process.internal.model.bpmn.BoundaryEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ErrorEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EscalationEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ExclusiveGateway;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Expression;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SequenceFlow;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The mutable working copy of one {@link ExecutionKernel#apply}: tokens, variables, effects, history and the agenda
 * of tokens waiting to enter their node. Not thread-safe; created per apply.
 */
final class WorkingContext implements ExecutionContext {

    private final InstanceState initial;
    private final CompiledProcess process;
    private final ExpressionEvaluator expressions;
    private final Instant now;
    private final String actor;
    private final List<Token> tokens;
    private final Map<String, Object> variables;
    private final java.util.Set<String> participants;
    private final List<Effect> effects = new ArrayList<>();
    private final List<HistoryEntry> history = new ArrayList<>();
    private final Deque<String> agenda = new ArrayDeque<>();
    private InstanceStatus status;
    private long sequence;

    WorkingContext(InstanceState state, CompiledProcess process, ExpressionEvaluator expressions, Clock clock,
            String actor) {
        this.initial = state;
        this.process = process;
        this.expressions = expressions;
        this.now = clock.instant();
        this.actor = actor;
        this.tokens = new ArrayList<>(state.tokens());
        this.variables = new LinkedHashMap<>(state.variables());
        this.participants = new java.util.LinkedHashSet<>(state.participants());
        this.status = state.status();
        this.sequence = state.sequence();
    }

    Transition transition() {
        InstanceState state = new InstanceState(initial.instanceId(), initial.tenantId(), initial.definitionId(),
                initial.businessKey(), status, tokens, variables, participants, sequence);
        return new Transition(state, effects, history);
    }

    InstanceState snapshot() {
        return transition().state();
    }

    /** @return the next token waiting to enter its node, or null when the agenda is empty */
    String nextOnAgenda() {
        return agenda.poll();
    }

    void status(InstanceStatus newStatus) {
        this.status = newStatus;
    }

    InstanceStatus status() {
        return status;
    }

    boolean isParticipant(String identity) {
        return identity != null && participants.contains(identity);
    }

    void addParticipant(String identity) {
        if (identity != null) {
            participants.add(identity);
        }
    }

    /** @return whether this apply changed the instance variables, which is when conditional events are asked again */
    boolean variablesChanged() {
        return !variables.equals(initial.variables());
    }

    void putAllVariables(Map<String, Object> values) {
        variables.putAll(values);
    }

    void enqueue(Token token) {
        agenda.add(token.id());
    }

    /**
     * Ends a compensation that walked its scope's stack to the bottom (#67): the scope token is {@code COMPENSATED},
     * every other live token is cancelled, and the instance is {@code FAILED}.
     *
     * @param scopeTokenId the compensated scope
     */
    void endCompensation(String scopeTokenId) {
        Token scope = tree().get(scopeTokenId).orElseThrow(() -> new ExecutionException("TOKEN_NOT_FOUND", null,
                "No scope token " + scopeTokenId));
        for (Token live : tree().all()) {
            if (live.status().live() && !live.id().equals(scopeTokenId)) {
                update(live.withStatus(TokenStatus.CANCELLED));
            }
        }
        update(scope.withStatus(TokenStatus.COMPENSATED));
        history.add(new HistoryEntry("COMPENSATED", scope.nodeId(), scope.id(), null, actor, now,
                Map.of("scopeTokenId", scope.id())));
        status = InstanceStatus.FAILED;
        history("INSTANCE_ENDED", null, Map.of("status", "FAILED"));
        effect(new Effect.EmitEvent(nextId(), "processes.instance.failed.v1",
                Map.of("instanceId", initial.instanceId())));
    }

    // ---- ExecutionContext ---------------------------------------------------------------------------------

    @Override
    public TokenTree tree() {
        return new TokenTree() {
            @Override
            public List<Token> all() {
                return List.copyOf(tokens);
            }

            @Override
            public Optional<Token> get(String id) {
                return tokens.stream().filter(t -> t.id().equals(id)).findFirst();
            }

            @Override
            public Optional<Token> root() {
                return tokens.stream().filter(t -> t.kind() == TokenKind.SCOPE && t.scopeTokenId() == null).findFirst();
            }

            @Override
            public List<Token> children(String scopeTokenId) {
                return tokens.stream().filter(t -> scopeTokenId.equals(t.scopeTokenId())).toList();
            }

            @Override
            public List<Token> live(String scopeTokenId) {
                return children(scopeTokenId).stream().filter(t -> t.status().live()).toList();
            }
        };
    }

    @Override
    public ScopeWalker scopes() {
        return new ScopeWalker() {
            @Override
            public Optional<Token> enclosing(Token token) {
                return token.scopeTokenId() == null ? Optional.empty() : tree().get(token.scopeTokenId());
            }

            @Override
            public List<Token> ancestors(Token token) {
                List<Token> out = new ArrayList<>();
                Optional<Token> current = enclosing(token);
                while (current.isPresent()) {
                    out.add(current.get());
                    current = enclosing(current.get());
                }
                return out;
            }
        };
    }

    @Override
    public VariableScopes variables() {
        return new VariableScopes() {
            @Override
            public Optional<Object> get(String name, Token in) {
                return Optional.ofNullable(visible(in).get(name));
            }

            @Override
            public void set(String name, Object value) {
                variables.put(name, value);
            }

            @Override
            public Map<String, Object> visible(Token in) {
                Map<String, Object> out = new LinkedHashMap<>(variables);
                List<Token> chain = new ArrayList<>(scopes().ancestors(in));
                Collections.reverse(chain);
                chain.forEach(scope -> out.putAll(scope.locals()));
                out.putAll(in.locals());
                return Collections.unmodifiableMap(out);
            }
        };
    }

    @Override
    public CompiledProcess process() {
        return process;
    }

    @Override
    public void takeOutgoing(Token token, FlowNode from) {
        // #489: the activity is left, so the timers of its boundary events never fire.
        disarmBoundaries(from.id(), token.scopeTokenId(), null);
        List<SequenceFlow> outgoing = process.outgoing(from.id());
        if (outgoing.isEmpty()) {
            throw new ExecutionException("NO_OUTGOING_FLOW", from.id(), from.id() + " has no outgoing sequence flow");
        }
        String defaultFlow = defaultFlowOf(from);
        List<SequenceFlow> selected = new ArrayList<>();
        for (SequenceFlow flow : outgoing) {
            if (flow.id().equals(defaultFlow)) {
                continue;
            }
            Expression condition = flow.conditionExpression();
            if (condition == null || condition.text() == null || condition.text().isBlank()
                    || Boolean.TRUE.equals(test(condition, token, flow))) {
                selected.add(flow);
            }
        }
        if (selected.isEmpty() && defaultFlow != null) {
            selected.add(process.flow(defaultFlow).orElseThrow());
        }
        if (selected.isEmpty()) {
            throw new ExecutionException("NO_PATH", from.id(), "No outgoing flow of " + from.id()
                    + " has a true condition and there is no default flow");
        }
        take(token, selected.get(0).id());
        for (int i = 1; i < selected.size(); i++) {
            Token fork = createToken(TokenKind.FLOW, from.id(), token.scopeTokenId());
            take(fork, selected.get(i).id());
        }
    }

    Object test(Expression condition, Token token, SequenceFlow flow) {
        Object value = evaluate(condition.text(), condition.language(), token);
        if (!(value instanceof Boolean)) {
            throw new ExecutionException("CONDITION_ERROR", flow.sourceRef(), "Condition of " + flow.id()
                    + " did not produce a boolean");
        }
        return value;
    }

    private static String defaultFlowOf(FlowNode node) {
        if (node instanceof ExclusiveGateway gateway) {
            return gateway.defaultFlow();
        }
        if (node instanceof io.github.dhluong90.bpm.process.internal.model.bpmn.Activity activity) {
            return activity.activity().defaultFlow();
        }
        return null;
    }

    @Override
    public void take(Token token, String sequenceFlowId) {
        SequenceFlow flow = process.flow(sequenceFlowId).orElseThrow(() ->
                new ExecutionException("UNKNOWN_FLOW", token.nodeId(), "No sequence flow " + sequenceFlowId));
        Token moved = token.movedTo(flow.targetRef(), null);
        update(moved);
        agenda.add(moved.id());
    }

    @Override
    public Token createToken(TokenKind kind, String nodeId, String scopeTokenId) {
        Token token = new Token(nextId(), kind, kind == TokenKind.SCOPE ? TokenStatus.WAITING_SCOPE : TokenStatus.ACTIVE,
                scopeTokenId, nodeId, null, Map.of());
        tokens.add(token);
        return token;
    }

    @Override
    public void update(Token token) {
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).id().equals(token.id())) {
                tokens.set(i, token);
                return;
            }
        }
        tokens.add(token);
    }

    @Override
    public void complete(Token token) {
        update(token.withStatus(TokenStatus.COMPLETED));
        if (token.scopeTokenId() == null) {
            return;
        }
        Token scope = tree().get(token.scopeTokenId()).orElseThrow();
        if (tree().live(scope.id()).isEmpty()) {
            update(scope.withStatus(TokenStatus.COMPLETED));
            if (scope.scopeTokenId() == null) {
                status = InstanceStatus.COMPLETED;
                history("INSTANCE_ENDED", null, Map.of("status", "COMPLETED"));
                effect(new Effect.EmitEvent(nextId(), "processes.instance.completed.v1", Map.of("instanceId", initial.instanceId())));
            } else {
                // A saga block ended (#67): a new flow token leaves the block in the enclosing scope.
                FlowNode block = process.node(scope.nodeId()).orElseThrow(() -> new ExecutionException("UNKNOWN_NODE",
                        scope.nodeId(), "No saga block " + scope.nodeId()));
                Token next = createToken(TokenKind.FLOW, block.id(), scope.scopeTokenId());
                history("NODE_COMPLETED", next, Map.of("scopeTokenId", scope.id()));
                takeOutgoing(next, block);
            }
        }
    }

    @Override
    public void enterScope(Token token, FlowNode block) {
        FlowNode start = process.startOf(block.id()).orElseThrow(() -> new ExecutionException("NO_START_EVENT",
                block.id(), "Saga block " + block.id() + " has no none start event"));
        Token scope = createToken(TokenKind.SCOPE, block.id(), token.scopeTokenId());
        update(token.withStatus(TokenStatus.COMPLETED));
        history("SCOPE_ENTERED", token, Map.of("scopeTokenId", scope.id()));
        Token flow = createToken(TokenKind.FLOW, start.id(), scope.id());
        enqueue(flow);
    }

    @Override
    public void compensate(Token failing, String cause) {
        Token scope = tree().get(failing.scopeTokenId()).orElseThrow(() -> new ExecutionException("INVALID_STATE",
                failing.nodeId(), "Token " + failing.id() + " has no scope"));
        for (Token live : tree().all()) {
            if (live.kind() == TokenKind.FLOW && live.status().live()) {
                // The failing step's own job already failed for good; it is kept as it is for the record.
                cancelFlow(live, !live.id().equals(failing.id()));
            }
        }
        status = InstanceStatus.COMPENSATING;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("scopeTokenId", scope.id());
        details.put("scope", scope.nodeId());
        details.put("cause", cause == null ? "-" : cause);
        history.add(new HistoryEntry("COMPENSATION_STARTED", failing.nodeId(), failing.id(),
                failing.activityInstanceId(), actor, now, details));
        effect(new Effect.StartCompensation(scope.id(), failing.nodeId(), cause));
    }

    /** Cancels one live flow token with the task, job or timer it waits on. */
    private void cancelFlow(Token child, boolean cancelWork) {
        update(child.withStatus(TokenStatus.CANCELLED));
        if (cancelWork && child.status() == TokenStatus.WAITING_USER && child.activityInstanceId() != null) {
            effect(new Effect.CancelUserTask(child.activityInstanceId()));
        }
        if (child.status() == TokenStatus.WAITING_EVENT && child.activityInstanceId() != null) {
            // The wait of a signal catch is a subscription row; a conditional wait has none, and deleting its id is a no-op.
            effect(new Effect.DeleteSubscription(child.activityInstanceId()));
        }
        if (cancelWork && (child.status() == TokenStatus.WAITING_ASYNC || child.status() == TokenStatus.WAITING_TIMER)
                && child.activityInstanceId() != null) {
            // The attempt id of an async step is its job id; a running call's TX2 then finds the job
            // CANCELLED and discards the result (DN-10). The activity instance of a waiting timer is its job.
            effect(new Effect.CancelJob(child.activityInstanceId()));
        }
        history("TOKEN_CANCELLED", child, Map.of());
    }

    @Override
    public void cancelSubtree(Token scope) {
        for (Token child : tree().children(scope.id())) {
            if (child.kind() != TokenKind.FLOW) {
                cancelSubtree(child);
            } else if (child.status().live()) {
                update(child.withStatus(TokenStatus.CANCELLED));
                if (child.status() == TokenStatus.WAITING_USER && child.activityInstanceId() != null) {
                    effect(new Effect.CancelUserTask(child.activityInstanceId()));
                }
                if (child.status() == TokenStatus.WAITING_ASYNC && child.activityInstanceId() != null) {
                    // The attempt id of an async step is its job id; a running call's TX2 then finds the job
                    // CANCELLED and discards the result (DN-10). A local call never leaves a token waiting.
                    effect(new Effect.CancelJob(child.activityInstanceId()));
                }
                if (child.status() == TokenStatus.WAITING_TIMER && child.activityInstanceId() != null) {
                    // The waiting token's activity instance is its timer job; cancel it now (ADR-0020).
                    effect(new Effect.CancelJob(child.activityInstanceId()));
                }
                if (child.status() == TokenStatus.WAITING_EVENT && child.activityInstanceId() != null) {
                    effect(new Effect.DeleteSubscription(child.activityInstanceId()));
                }
                history("TOKEN_CANCELLED", child, Map.of());
            }
        }
        update(scope.withStatus(TokenStatus.CANCELLED));
    }

    // ---- boundary events, error and escalation, terminate, link (#489, #493) --------------------------------------

    /** @return whether a live token already waits on this boundary event in the scope (a retry enters the step again) */
    boolean hasLiveBoundaryToken(BoundaryEvent boundary, String scopeTokenId) {
        return tokens.stream().anyMatch(t -> t.nodeId().equals(boundary.id()) && t.kind() == TokenKind.FLOW
                && t.status().live() && java.util.Objects.equals(t.scopeTokenId(), scopeTokenId));
    }

    /**
     * Stops the waiting timers of the boundary events of an activity, silently: the activity ended on its own, so
     * nothing was interrupted and the timeline has nothing to say.
     */
    private void disarmBoundaries(String activityId, String scopeTokenId, String exceptTokenId) {
        for (BoundaryEvent boundary : process.boundariesOf(activityId)) {
            for (Token t : List.copyOf(tokens)) {
                if (t.kind() == TokenKind.FLOW && t.nodeId().equals(boundary.id()) && t.status().live()
                        && java.util.Objects.equals(t.scopeTokenId(), scopeTokenId) && !t.id().equals(exceptTokenId)) {
                    update(t.withStatus(TokenStatus.CANCELLED));
                    if (t.status() == TokenStatus.WAITING_TIMER && t.activityInstanceId() != null) {
                        effect(new Effect.CancelJob(t.activityInstanceId()));
                    }
                }
            }
        }
    }

    /** Cancels a token and everything under it, with the work each waits on. */
    private int cancelTree(Token token, String exceptWorkOf) {
        int cancelled = 0;
        if (token.kind() != TokenKind.FLOW) {
            for (Token child : tree().children(token.id())) {
                if (child.status().live()) {
                    cancelled += cancelTree(child, exceptWorkOf);
                }
            }
            update(token.withStatus(TokenStatus.CANCELLED));
            return cancelled;
        }
        update(token.withStatus(TokenStatus.CANCELLED));
        boolean work = !token.id().equals(exceptWorkOf) && token.activityInstanceId() != null;
        if (work && token.status() == TokenStatus.WAITING_USER) {
            effect(new Effect.CancelUserTask(token.activityInstanceId()));
        } else if (work && (token.status() == TokenStatus.WAITING_ASYNC || token.status() == TokenStatus.WAITING_TIMER)) {
            effect(new Effect.CancelJob(token.activityInstanceId()));
        } else if (work && token.status() == TokenStatus.WAITING_CHILD) {
            effect(new Effect.CancelChildInstance(token.id(), token.activityInstanceId()));
        }
        history("TOKEN_CANCELLED", token, Map.of());
        return 1;
    }

    /** The token that stands for the running activity {@code activityId} in a scope: a flow token, or the scope token. */
    private Optional<Token> hostOf(String activityId, String scopeTokenId) {
        return tokens.stream().filter(t -> t.nodeId().equals(activityId) && t.status().live()
                && t.kind() != TokenKind.MI_BODY && java.util.Objects.equals(t.scopeTokenId(), scopeTokenId))
                .findFirst();
    }

    /** Cancels a host activity: its tree, and the timers of its other boundaries. */
    private void interrupt(Token host, String exceptWorkOf, String exceptBoundaryToken) {
        cancelTree(host, exceptWorkOf);
        disarmBoundaries(host.nodeId(), host.scopeTokenId(), exceptBoundaryToken);
    }

    @Override
    public void fireBoundary(Token boundaryToken, BoundaryEvent boundary) {
        Optional<Token> host = hostOf(boundary.attachedToRef(), boundaryToken.scopeTokenId());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("boundaryId", boundary.id());
        details.put("attachedTo", boundary.attachedToRef());
        details.put("trigger", "TIMER");
        details.put("interrupting", boundary.cancelActivity());
        if (boundary.cancelActivity()) {
            host.ifPresent(h -> interrupt(h, null, boundaryToken.id()));
        }
        // The boundary is entered when it fires, not when it is armed, so an unfired one never shows as running.
        history("NODE_ENTERED", boundaryToken, Map.of());
        history("BOUNDARY_FIRED", boundaryToken, details);
        Token active = boundaryToken.withStatus(TokenStatus.ACTIVE);
        update(active);
        history("NODE_COMPLETED", boundaryToken, Map.of());
        takeOutgoing(active, boundary);
    }

    /** A boundary that catches a thrown error or escalation, and the activity token it is attached to. */
    private record Catcher(BoundaryEvent boundary, Token host) {
    }

    private static String codeOf(EventDefinition definition, boolean error) {
        if (error && definition instanceof ErrorEventDefinition e) {
            return e.errorCode();
        }
        if (!error && definition instanceof EscalationEventDefinition e) {
            return e.escalationCode();
        }
        return null;
    }

    private static boolean isKind(BoundaryEvent boundary, boolean error) {
        if (boundary.eventDefinitions().size() != 1) {
            return false;
        }
        EventDefinition d = boundary.eventDefinitions().get(0);
        return error ? d instanceof ErrorEventDefinition : d instanceof EscalationEventDefinition;
    }

    /** Walks from the raising token outward and returns the nearest matching boundary (specific code before catch-all). */
    private Optional<Catcher> findCatcher(Token source, String code, boolean error, boolean fromStep) {
        Token host;
        if (fromStep) {
            host = source;
        } else {
            host = source.scopeTokenId() == null ? null : tree().get(source.scopeTokenId()).orElse(null);
        }
        while (host != null && host.scopeTokenId() != null) {
            BoundaryEvent specific = null;
            BoundaryEvent any = null;
            for (BoundaryEvent b : process.boundariesOf(host.nodeId())) {
                if (!isKind(b, error)) {
                    continue;
                }
                String declared = codeOf(b.eventDefinitions().get(0), error);
                if (declared == null && any == null) {
                    any = b;
                } else if (declared != null && declared.equals(code) && specific == null) {
                    specific = b;
                }
            }
            BoundaryEvent caught = specific != null ? specific : any;
            if (caught != null) {
                return Optional.of(new Catcher(caught, host));
            }
            host = tree().get(host.scopeTokenId()).orElse(null);
        }
        return Optional.empty();
    }

    /** Runs the flow of a catching boundary in the scope of its host. */
    private void runCatch(Catcher found, Token source, String code, String trigger, boolean fromStep) {
        BoundaryEvent boundary = found.boundary();
        Token host = found.host();
        if (boundary.cancelActivity()) {
            interrupt(host, fromStep ? source.id() : null, null);
        }
        Token flow = createToken(TokenKind.FLOW, boundary.id(), host.scopeTokenId());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("boundaryId", boundary.id());
        details.put("attachedTo", boundary.attachedToRef());
        details.put("trigger", trigger);
        details.put("code", code == null ? "-" : code);
        details.put("interrupting", boundary.cancelActivity());
        history("NODE_ENTERED", flow, Map.of());
        history("BOUNDARY_FIRED", flow, details);
        history("NODE_COMPLETED", flow, Map.of());
        takeOutgoing(flow, boundary);
    }

    @Override
    public boolean throwError(Token source, String code, boolean fromStep) {
        Optional<Catcher> found = findCatcher(source, code, true, fromStep);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("code", code == null ? "-" : code);
        found.ifPresent(c -> details.put("caughtBy", c.boundary().id()));
        history("ERROR_THROWN", source, details);
        if (found.isEmpty()) {
            return false;
        }
        runCatch(found.get(), source, code, "ERROR", fromStep);
        return true;
    }

    @Override
    public boolean throwEscalation(Token thrower, String code) {
        Optional<Catcher> found = findCatcher(thrower, code, false, false);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("code", code == null ? "-" : code);
        found.ifPresent(c -> details.put("caughtBy", c.boundary().id()));
        history("ESCALATION_THROWN", thrower, details);
        if (found.isEmpty()) {
            return false;
        }
        runCatch(found.get(), thrower, code, "ESCALATION", false);
        return found.get().boundary().cancelActivity();
    }

    @Override
    public void terminate(Token token) {
        int cancelled = 0;
        Token scope = tree().get(token.scopeTokenId()).orElseThrow();
        for (Token other : tree().children(scope.id())) {
            if (!other.id().equals(token.id()) && other.status().live()) {
                cancelled += cancelTree(other, null);
            }
        }
        history("TERMINATED", token, Map.of("scopeTokenId", scope.id(), "cancelled", cancelled));
        complete(token);
    }

    @Override
    public void jump(Token token, String nodeId) {
        Token moved = token.movedTo(nodeId, null);
        update(moved);
        agenda.add(moved.id());
    }

    @Override
    public void effect(Effect effect) {
        effects.add(effect);
    }

    @Override
    public void history(HistoryEntry entry) {
        history.add(entry);
    }

    @Override
    public void history(String type, Token token, Map<String, Object> details) {
        history.add(new HistoryEntry(type, token == null ? null : token.nodeId(), token == null ? null : token.id(),
                token == null ? null : token.activityInstanceId(), actor, now, details));
    }

    @Override
    public String nextId() {
        return Ids.child(initial.instanceId(), ++sequence);
    }

    @Override
    public Object evaluate(String expression, String languageId, Token in) {
        return expressions.evaluate(expression, languageId, variables().visible(in));
    }

    @Override
    public <T> T facet(FacetKey<T> key) {
        return process.facet(key);
    }

    @Override
    public String actor() {
        return actor;
    }

    @Override
    public Instant now() {
        return now;
    }
}
