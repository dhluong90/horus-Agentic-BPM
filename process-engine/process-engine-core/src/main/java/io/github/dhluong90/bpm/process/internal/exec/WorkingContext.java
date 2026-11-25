package io.github.dhluong90.bpm.process.internal.exec;

import io.github.dhluong90.bpm.process.api.CancelCause;
import io.github.dhluong90.bpm.process.internal.model.bpmn.BoundaryEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ErrorEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EscalationEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ExclusiveGateway;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Expression;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SequenceFlow;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SubProcess;
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
    private final Loops loops;
    private final Instant now;
    private final String actor;
    private final List<Token> tokens;
    private final Map<String, Object> variables;
    private final java.util.Set<String> participants;
    private final List<Effect> effects = new ArrayList<>();
    private final List<HistoryEntry> history = new ArrayList<>();
    private final Deque<String> agenda = new ArrayDeque<>();
    private final ScopeArming arming;
    private final DataStorePort dataStores;
    private InstanceStatus status;
    private long sequence;
    private boolean conditionArmed;
    private long variableVersion;
    private final java.util.Set<String> deferred = new java.util.LinkedHashSet<>();
    private boolean conditionsSettled;

    WorkingContext(InstanceState state, CompiledProcess process, ExpressionEvaluator expressions, Clock clock,
            String actor, int maxInstances, ScopeArming arming) {
        this(state, process, expressions, clock, actor, maxInstances, arming, DataStorePort.NONE);
    }

    WorkingContext(InstanceState state, CompiledProcess process, ExpressionEvaluator expressions, Clock clock,
            String actor, int maxInstances, ScopeArming arming, DataStorePort dataStores) {
        this.dataStores = dataStores;
        this.arming = arming;
        this.loops = new Loops(this, maxInstances);
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

    @Override
    public DataStorePort dataStores() {
        return dataStores;
    }

    @Override
    public DataStorePort.Access dataStoreAccess(String store, String item, String nodeId, Token token) {
        return new DataStorePort.Access(initial.tenantId(), store, item, initial.instanceId(), nodeId,
                initial.instanceId() + ":" + nodeId + ":" + token.id() + ":" + store + ":" + item);
    }

    @Override
    public String businessKey() {
        return initial.businessKey();
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

    Loops loops() {
        return loops;
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
        variableVersion++;
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
        Optional<Token> waiting = tree().children(scopeTokenId).stream()
                .filter(t -> t.kind() == TokenKind.FLOW && t.status() == TokenStatus.WAITING_EVENT).findFirst();
        if (waiting.isPresent()) {
            resumeAfterCompensation(scope, waiting.get());
            return;
        }
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

    /** The local that marks the walk token of a background compensation; its value is the activity, "" for the scope. */
    static final String BACKGROUND_UNDO = BackgroundWalks.MARK;

    /**
     * A background walk reached the bottom (PD-149): its token completes, which lets the scope end. A walk that already
     * ended is left alone, so the command is safe to repeat.
     *
     * @param tokenId the walk token
     */
    void endBackgroundCompensation(String tokenId) {
        Token walk = tree().get(tokenId).orElseThrow(() -> new ExecutionException("TOKEN_NOT_FOUND", null,
                "No walk token " + tokenId));
        if (!walk.status().live() || !walk.locals().containsKey(BACKGROUND_UNDO)) {
            return;
        }
        history("COMPENSATED", walk, Map.of("scopeTokenId", walk.scopeTokenId(), "background", true));
        complete(walk);
    }

    /**
     * The walk of a throw or a cancel reached the bottom (#488): the instance runs again, the thrower leaves. A
     * compensation throw event goes on by its outgoing flow or, as an end event, ends its token; a cancel end event
     * leaves the transaction through its cancel boundary event.
     */
    private void resumeAfterCompensation(Token scope, Token thrower) {
        status = InstanceStatus.RUNNING;
        history.add(new HistoryEntry("COMPENSATED", scope.nodeId(), scope.id(), null, actor, now,
                Map.of("scopeTokenId", scope.id())));
        FlowNode node = process.node(thrower.nodeId()).orElseThrow(() -> new ExecutionException("UNKNOWN_NODE",
                thrower.nodeId(), "No node " + thrower.nodeId()));
        Token active = thrower.withStatus(TokenStatus.ACTIVE);
        update(active);
        boolean cancel = node instanceof io.github.dhluong90.bpm.process.internal.model.bpmn.EndEvent e
                && e.eventDefinitions().size() == 1 && e.eventDefinitions().get(0) instanceof
                        io.github.dhluong90.bpm.process.internal.model.bpmn.CancelEventDefinition;
        history("NODE_COMPLETED", active, Map.of());
        if (cancel) {
            update(active.withStatus(TokenStatus.COMPLETED));
            update(scope.withStatus(TokenStatus.COMPENSATED));
            disarmBoundaries(scope.nodeId(), scope.scopeTokenId(), null);
            BoundaryEvent boundary = process.boundariesOf(scope.nodeId()).stream().filter(b ->
                    b.eventDefinitions().size() == 1 && b.eventDefinitions().get(0) instanceof
                            io.github.dhluong90.bpm.process.internal.model.bpmn.CancelEventDefinition).findFirst()
                    .orElseThrow(() -> new ExecutionException("CANCEL_BOUNDARY_MISSING", scope.nodeId(),
                            "Transaction " + scope.nodeId() + " has no cancel boundary event"));
            Token flow = createToken(TokenKind.FLOW, boundary.id(), scope.scopeTokenId());
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("boundaryId", boundary.id());
            details.put("attachedTo", boundary.attachedToRef());
            details.put("trigger", "CANCEL");
            details.put("interrupting", true);
            history("NODE_ENTERED", flow, Map.of());
            history("BOUNDARY_FIRED", flow, details);
            history("NODE_COMPLETED", flow, Map.of());
            takeOutgoing(flow, boundary);
        } else if (node instanceof io.github.dhluong90.bpm.process.internal.model.bpmn.EndEvent) {
            complete(active);
        } else {
            takeOutgoing(active, node);
        }
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
                variableVersion++;
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
        if (loops.endsInstance(token, from)) {
            // One repetition of a loop ended (#496); the loop, not the repetition, leaves the activity.
            return;
        }
        // #489: the activity is left, so the timers of its boundary events never fire.
        disarmBoundaries(from.id(), token.scopeTokenId(), null);
        resolveEventGateway(token, from);
        List<SequenceFlow> outgoing = process.outgoing(from.id());
        if (outgoing.isEmpty() && process.adHoc(process.containerOf(from.id())).isPresent()) {
            // #500: an activity of an ad-hoc sub-process has no flow out; its end is the end of that activity.
            complete(token);
            return;
        }
        if (outgoing.isEmpty()) {
            throw new ExecutionException("NO_OUTGOING_FLOW", from.id(), from.id() + " has no outgoing sequence flow");
        }
        String defaultFlow = defaultFlowOf(from);
        List<SequenceFlow> selected = new ArrayList<>();
        boolean conditionHeld = false;
        for (SequenceFlow flow : outgoing) {
            if (flow.id().equals(defaultFlow)) {
                continue;
            }
            Expression condition = flow.conditionExpression();
            if (condition == null || condition.text() == null || condition.text().isBlank()) {
                // An uncontrolled flow is always taken; it does not count as a condition that held (PRE-373).
                selected.add(flow);
            } else if (Boolean.TRUE.equals(test(condition, token, flow))) {
                selected.add(flow);
                conditionHeld = true;
            }
        }
        if (!conditionHeld && defaultFlow != null) {
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
        if (node instanceof io.github.dhluong90.bpm.process.internal.model.bpmn.InclusiveGateway gateway) {
            return gateway.defaultFlow();
        }
        if (node instanceof io.github.dhluong90.bpm.process.internal.model.bpmn.ComplexGateway gateway) {
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
        if (process.adHoc(scope.nodeId()).isPresent()) {
            adHocProgress(scope);
            return;
        }
        settle(scope);
    }

    /** The local that marks an ad-hoc sub-process whose completion condition holds while activities still run. */
    static final String AD_HOC_DONE = "$adHocDone";

    /**
     * An activity of an ad-hoc sub-process ended (#500). With a completion condition it is asked now; true cancels the
     * activities still running ({@code cancelRemainingInstances}) or waits for them. Without one the sub-process ends
     * when nothing runs and every activity has completed once.
     */
    private void adHocProgress(Token scope) {
        io.github.dhluong90.bpm.process.internal.model.bpmn.AdHocSubProcess node = process.adHoc(scope.nodeId())
                .orElseThrow();
        List<Token> running = tree().live(scope.id());
        boolean done;
        Expression condition = node.completionCondition();
        if (Boolean.TRUE.equals(scope.locals().get(AD_HOC_DONE))) {
            done = running.isEmpty();
        } else if (condition != null && condition.text() != null) {
            Object value = evaluate(condition.text(), condition.language(), scope);
            if (!(value instanceof Boolean met)) {
                throw new ExecutionException("CONDITION_ERROR", node.id(), "The completion condition of " + node.id()
                        + " did not produce a boolean");
            }
            if (met) {
                history("AD_HOC_CONDITION_MET", scope, Map.of("running", running.size()));
                if (node.cancelRemainingInstances()) {
                    running.forEach(t -> cancelTree(t, null, CancelCause.ADHOC_CANCEL));
                    done = true;
                } else {
                    Map<String, Object> locals = new LinkedHashMap<>(scope.locals());
                    locals.put(AD_HOC_DONE, true);
                    update(new Token(scope.id(), scope.kind(), scope.status(), scope.scopeTokenId(), scope.nodeId(),
                            scope.activityInstanceId(), locals));
                    done = running.isEmpty();
                }
            } else {
                done = false;
            }
        } else {
            done = running.isEmpty() && node.flowElements().stream()
                    .filter(e -> e instanceof io.github.dhluong90.bpm.process.internal.model.bpmn.Activity)
                    .allMatch(a -> tree().children(scope.id()).stream().anyMatch(t -> t.nodeId().equals(a.id())
                            && t.status() == TokenStatus.COMPLETED));
        }
        if (done) {
            update(scope.withStatus(TokenStatus.COMPLETED));
            leaveBlock(tree().get(scope.id()).orElseThrow());
        }
    }

    /**
     * Ends a scope that has no work left (#487). A token that waits on the start event of an event sub-process is not
     * work: the scope ends over it, and its timer or subscription goes with it. A running event sub-process is work, so
     * a non-interrupting one keeps its scope open, and an interrupting one that ends lets its scope end.
     */
    private void settle(Token scope) {
        List<Token> live = tree().live(scope.id());
        if (live.stream().anyMatch(t -> !isListener(t))) {
            return;
        }
        if (waitsAtInclusiveJoin(scope)) {
            return;
        }
        if (!conditionsSettled && live.stream().anyMatch(this::isConditionalListener) && variablesChanged()) {
            // The variables changed in this apply, so a conditional event sub-process is asked before its scope ends.
            deferred.add(scope.id());
            return;
        }
        live.forEach(this::dropListener);
        update(scope.withStatus(TokenStatus.COMPLETED));
        if (scope.scopeTokenId() != null && loops.endsInstanceScope(scope)) {
            // One repetition of a looped sub-process ended (#496).
            return;
        }
        if (scope.scopeTokenId() == null) {
            status = InstanceStatus.COMPLETED;
            history("INSTANCE_ENDED", null, Map.of("status", "COMPLETED"));
            effect(new Effect.EmitEvent(nextId(), "processes.instance.completed.v1", Map.of("instanceId", initial.instanceId())));
        } else if (process.isEventSubProcess(scope.nodeId())) {
            // An event sub-process has no flow out: the scope it stands in may be done now.
            settle(tree().get(scope.scopeTokenId()).orElseThrow());
        } else {
            leaveBlock(scope);
        }
    }

    /** A saga block, sub-process or ad-hoc sub-process ended (#67, #486, #500): a new flow token leaves it. */
    private void leaveBlock(Token scope) {
        FlowNode block = process.node(scope.nodeId()).orElseThrow(() -> new ExecutionException("UNKNOWN_NODE",
                scope.nodeId(), "No saga block " + scope.nodeId()));
        arming.completed(this, scope, block);
        Token next = createToken(TokenKind.FLOW, block.id(), scope.scopeTokenId());
        history("NODE_COMPLETED", next, Map.of("scopeTokenId", scope.id()));
        takeOutgoing(next, block);
    }

    /** @return whether the token waits on the start event of an event sub-process of the scope it stands in (#487) */
    private boolean isListener(Token token) {
        if (token.kind() != TokenKind.FLOW || !token.status().live()) {
            return false;
        }
        String owner = process.containerOf(token.nodeId());
        return process.isEventSubProcess(owner) && process.node(token.nodeId())
                .filter(n -> n instanceof io.github.dhluong90.bpm.process.internal.model.bpmn.StartEvent).isPresent()
                && tree().get(token.scopeTokenId()).map(s -> !s.nodeId().equals(owner)).orElse(false);
    }

    private boolean isConditionalListener(Token token) {
        return process.node(token.nodeId()).filter(n -> n instanceof io.github.dhluong90.bpm.process.internal.model.bpmn
                .StartEvent && MultipleWaits.waitsOnCondition(n, token)).isPresent();
    }

    /**
     * Ends the scopes whose end was held back for their conditional event sub-processes, now that those were asked
     * (#487).
     *
     * @return whether a scope was settled, which may have moved tokens
     */
    boolean settleDeferred() {
        List<String> ids = List.copyOf(deferred);
        deferred.clear();
        conditionsSettled = true;
        boolean any = false;
        for (String id : ids) {
            Token scope = tree().get(id).orElse(null);
            if (scope != null && scope.status().live()) {
                settle(scope);
                any = true;
            }
        }
        conditionsSettled = false;
        return any;
    }

    /** Stops the wait of a listener, silently: its scope ended, so nothing was interrupted. */
    private void dropListener(Token listener) {
        update(listener.withStatus(TokenStatus.CANCELLED));
        if (listener.status() == TokenStatus.WAITING_TIMER && listener.activityInstanceId() != null) {
            effect(new Effect.CancelJob(listener.activityInstanceId()));
        } else if (listener.status() == TokenStatus.WAITING_EVENT && listener.activityInstanceId() != null) {
            effect(new Effect.DeleteSubscription(listener.activityInstanceId()));
        }
    }

    @Override
    public void conditionArmed() {
        conditionArmed = true;
    }

    /** @return whether a conditional event sub-process was armed in this apply, so its condition is asked once */
    boolean takeConditionArmed() {
        boolean armed = conditionArmed;
        conditionArmed = false;
        return armed;
    }

    /** @return a number that grows with every write to the instance variables, to tell whether a pass changed them */
    long variableVersion() {
        return variableVersion;
    }

    /**
     * A token waiting at an inclusive join is not live, but the scope is not over while one waits: the join fires
     * once the other tokens have ended, and its outgoing token ends the scope.
     */
    private boolean waitsAtInclusiveJoin(Token scope) {
        return tree().children(scope.id()).stream().anyMatch(t -> t.status() == TokenStatus.JOINED
                && process.node(t.nodeId()).orElse(null) instanceof
                io.github.dhluong90.bpm.process.internal.model.bpmn.InclusiveGateway);
    }

    @Override
    public void enterScope(Token token, FlowNode block) {
        FlowNode start = process.startOf(block.id()).orElseThrow(() -> new ExecutionException("NO_START_EVENT",
                block.id(), "Saga block " + block.id() + " has no none start event"));
        Token scope = createToken(TokenKind.SCOPE, block.id(), token.scopeTokenId());
        update(token.withStatus(TokenStatus.COMPLETED));
        history("SCOPE_ENTERED", token, Map.of("scopeTokenId", scope.id()));
        arming.arm(this, scope);
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
                cancelFlow(live, !live.id().equals(failing.id()), CancelCause.COMPENSATION);
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

    @Override
    public void compensateAndResume(Token thrower, String targetActivityId) {
        Token scope = tree().get(thrower.scopeTokenId()).orElseThrow(() -> new ExecutionException("INVALID_STATE",
                thrower.nodeId(), "Token " + thrower.id() + " has no scope"));
        startResumable(thrower, scope, "THROW_COMPENSATION", targetActivityId);
    }

    @Override
    public void compensateInBackground(Token thrower, String targetActivityId) {
        Token scope = tree().get(thrower.scopeTokenId()).orElseThrow(() -> new ExecutionException("INVALID_STATE",
                thrower.nodeId(), "Token " + thrower.id() + " has no scope"));
        Token created = createToken(TokenKind.FLOW, thrower.nodeId(), scope.id());
        Token walk = new Token(created.id(), TokenKind.FLOW, TokenStatus.WAITING_ASYNC, scope.id(), thrower.nodeId(),
                null, Map.of(BACKGROUND_UNDO, targetActivityId == null ? "" : targetActivityId));
        update(walk);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("scopeTokenId", scope.id());
        details.put("scope", scope.nodeId());
        details.put("cause", "THROW_COMPENSATION");
        details.put("background", true);
        if (targetActivityId != null) {
            details.put("activity", targetActivityId);
        }
        history.add(new HistoryEntry("COMPENSATION_STARTED", thrower.nodeId(), walk.id(), null, actor, now, details));
        effect(new Effect.StartCompensation(scope.id(), thrower.nodeId(), "THROW_COMPENSATION", targetActivityId,
                walk.id()));
    }

    @Override
    public void cancelTransaction(Token end) {
        Token scope = tree().get(end.scopeTokenId()).orElseThrow(() -> new ExecutionException("INVALID_STATE",
                end.nodeId(), "Token " + end.id() + " has no scope"));
        startResumable(end, scope, "CANCEL", null);
    }

    /**
     * Starts a compensation that the flow resumes after (#488). The other live tokens under the scope are cancelled
     * with their work, as the BPMN cancel of a transaction does; the thrower waits ({@code WAITING_EVENT}) so the
     * shell's walk can find it, and the instance is {@code COMPENSATING}.
     */
    private void startResumable(Token thrower, Token scope, String cause, String target) {
        for (Token child : tree().children(scope.id())) {
            if (child.status().live() && !child.id().equals(thrower.id())) {
                cancelTree(child, null, "CANCEL".equals(cause) ? CancelCause.TRANSACTION_CANCEL : CancelCause.COMPENSATION);
            }
        }
        update(new Token(thrower.id(), thrower.kind(), TokenStatus.WAITING_EVENT, thrower.scopeTokenId(),
                thrower.nodeId(), null, thrower.locals()));
        status = InstanceStatus.COMPENSATING;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("scopeTokenId", scope.id());
        details.put("scope", scope.nodeId());
        details.put("cause", cause);
        if (target != null) {
            details.put("activity", target);
        }
        history.add(new HistoryEntry("COMPENSATION_STARTED", thrower.nodeId(), thrower.id(), null, actor, now,
                details));
        effect(new Effect.StartCompensation(scope.id(), thrower.nodeId(), cause));
    }

    /** Cancels one live flow token with the task, job or timer it waits on. */
    private void cancelFlow(Token child, boolean cancelWork, CancelCause cause) {
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
        history("TOKEN_CANCELLED", child, Map.of("cause", cause.name()));
    }

    @Override
    public void cancelToken(Token token, CancelCause cause) {
        if (token.status().live()) {
            cancelFlow(token, true, cause);
        } else if (token.status() == TokenStatus.JOINED) {
            update(token.withStatus(TokenStatus.CANCELLED));
        }
    }

    @Override
    public void cancelSubtree(Token scope, CancelCause cause) {
        for (Token child : tree().children(scope.id())) {
            if (child.kind() != TokenKind.FLOW) {
                cancelSubtree(child, cause);
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
                history("TOKEN_CANCELLED", child, Map.of("cause", cause.name()));
            }
        }
        update(scope.withStatus(TokenStatus.CANCELLED));
    }

    // ---- boundary events, error and escalation, terminate, link (#489, #493) --------------------------------------

    /** @return whether a live token already waits on this boundary event in the scope (a retry enters the step again) */
    boolean hasLiveBoundaryToken(BoundaryEvent boundary, String scopeTokenId) {
        return tokens.stream().anyMatch(t -> t.nodeId().equals(boundary.id()) && t.kind() == TokenKind.FLOW
                && (t.status().live() || t.status() == TokenStatus.JOINED)
                && java.util.Objects.equals(t.scopeTokenId(), scopeTokenId));
    }

    /**
     * Stops the waiting timers of the boundary events of an activity, silently: the activity ended on its own, so
     * nothing was interrupted and the timeline has nothing to say.
     */
    private void disarmBoundaries(String activityId, String scopeTokenId, String exceptTokenId) {
        for (BoundaryEvent boundary : process.boundariesOf(activityId)) {
            for (Token t : List.copyOf(tokens)) {
                if (t.kind() == TokenKind.FLOW && t.nodeId().equals(boundary.id())
                        && (t.status().live() || t.status() == TokenStatus.JOINED)
                        && java.util.Objects.equals(t.scopeTokenId(), scopeTokenId) && !t.id().equals(exceptTokenId)) {
                    update(t.withStatus(TokenStatus.CANCELLED));
                    if (t.status() == TokenStatus.WAITING_TIMER && t.activityInstanceId() != null) {
                        effect(new Effect.CancelJob(t.activityInstanceId()));
                    }
                    if (t.status() == TokenStatus.WAITING_EVENT && t.activityInstanceId() != null) {
                        // #490: a message or signal boundary leaves a subscription; a conditional one has none.
                        effect(new Effect.DeleteSubscription(t.activityInstanceId()));
                    }
                }
            }
        }
    }

    /**
     * #495: an event of an event-based gateway arrived, so its token leaves the catch event. The other catch events of
     * the same gateway are cancelled in this apply, which the shell runs under the instance row lock: of two events
     * that reach two replicas at the same moment, the second finds its token cancelled and is refused as stale.
     */
    private void resolveEventGateway(Token winner, FlowNode from) {
        Optional<io.github.dhluong90.bpm.process.internal.model.bpmn.EventBasedGateway> gateway =
                process.eventGatewayOf(from.id());
        if (gateway.isEmpty() || gateway.get().eventGatewayType()
                == io.github.dhluong90.bpm.process.internal.model.bpmn.EventBasedGatewayType.PARALLEL) {
            // #499: every catch of a parallel gateway is awaited; none of them cancels another.
            return;
        }
        List<String> targets = process.outgoing(gateway.get().id()).stream().map(SequenceFlow::targetRef).toList();
        List<String> lost = new ArrayList<>();
        for (Token other : List.copyOf(tokens)) {
            if (other.kind() == TokenKind.FLOW && other.status().live() && !other.id().equals(winner.id())
                    && targets.contains(other.nodeId())
                    && java.util.Objects.equals(other.scopeTokenId(), winner.scopeTokenId())) {
                cancelTree(other, null, CancelCause.EVENT_GATEWAY);
                disarmBoundaries(other.nodeId(), other.scopeTokenId(), null);
                lost.add(other.nodeId());
            }
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("gatewayId", gateway.get().id());
        details.put("winner", from.id());
        details.put("cancelled", lost);
        history("EVENT_GATEWAY_RESOLVED", winner, details);
    }

    /** Cancels a token and everything under it, with the work each waits on. */
    private int cancelTree(Token token, String exceptWorkOf, CancelCause cause) {
        int cancelled = 0;
        if (token.kind() != TokenKind.FLOW) {
            for (Token child : tree().children(token.id())) {
                if (child.status().live()) {
                    cancelled += cancelTree(child, exceptWorkOf, cause);
                }
            }
            update(token.withStatus(TokenStatus.CANCELLED));
            return cancelled;
        }
        boolean listener = isListener(token);
        update(token.withStatus(TokenStatus.CANCELLED));
        boolean work = !token.id().equals(exceptWorkOf) && token.activityInstanceId() != null;
        if (work && token.status() == TokenStatus.WAITING_USER) {
            effect(new Effect.CancelUserTask(token.activityInstanceId()));
        } else if (work && (token.status() == TokenStatus.WAITING_ASYNC || token.status() == TokenStatus.WAITING_TIMER)) {
            effect(new Effect.CancelJob(token.activityInstanceId()));
        } else if (work && token.status() == TokenStatus.WAITING_CHILD) {
            effect(new Effect.CancelChildInstance(token.id(), token.activityInstanceId()));
        } else if (token.status() == TokenStatus.WAITING_EVENT && token.activityInstanceId() != null) {
            // A signal or message wait is a subscription row; a conditional wait has none and the delete is a no-op.
            effect(new Effect.DeleteSubscription(token.activityInstanceId()));
        }
        if (!listener) {
            history("TOKEN_CANCELLED", token, Map.of("cause", cause.name()));
        }
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
        cancelTree(host, exceptWorkOf, CancelCause.BOUNDARY);
        disarmBoundaries(host.nodeId(), host.scopeTokenId(), exceptBoundaryToken);
    }

    @Override
    public void fireBoundary(Token boundaryToken, BoundaryEvent boundary) {
        fireBoundary(boundaryToken, boundary, "TIMER", Map.of(), false);
    }

    @Override
    public void fireBoundary(Token boundaryToken, BoundaryEvent boundary, String trigger, Map<String, Object> extra,
            boolean keepWaiting) {
        Optional<Token> host = hostOf(boundary.attachedToRef(), boundaryToken.scopeTokenId());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("boundaryId", boundary.id());
        details.put("attachedTo", boundary.attachedToRef());
        details.put("trigger", trigger);
        details.put("interrupting", boundary.cancelActivity());
        details.putAll(extra);
        if (boundary.cancelActivity()) {
            host.ifPresent(h -> interrupt(h, null, boundaryToken.id()));
        }
        // A non-interrupting message or signal boundary stays armed; its flow runs on a token of its own.
        Token flow = keepWaiting && !boundary.cancelActivity()
                ? createToken(TokenKind.FLOW, boundary.id(), boundaryToken.scopeTokenId()) : boundaryToken;
        // The boundary is entered when it fires, not when it is armed, so an unfired one never shows as running.
        history("NODE_ENTERED", flow, Map.of());
        history("BOUNDARY_FIRED", flow, details);
        Token active = flow.withStatus(TokenStatus.ACTIVE);
        update(active);
        history("NODE_COMPLETED", flow, Map.of());
        takeOutgoing(active, boundary);
    }

    /**
     * What catches a thrown error or escalation: a boundary event and the activity token it is attached to, or an event
     * sub-process (#487) and the scope token it stands in.
     */
    private record Catcher(BoundaryEvent boundary, SubProcess eventSubProcess, Token host) {
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

    /** The definition of the kind a throw looks for on a boundary event, a multiple one (#500) included. */
    private static Optional<EventDefinition> catching(BoundaryEvent boundary, boolean error) {
        if (boundary.parallelMultiple()) {
            return Optional.empty();
        }
        return boundary.eventDefinitions().stream()
                .filter(d -> error ? d instanceof ErrorEventDefinition : d instanceof EscalationEventDefinition)
                .findFirst();
    }

    /**
     * Walks from the raising token outward and returns the nearest catcher. At each level the event sub-processes of a
     * scope come before the boundary events on that scope's own activity, because they stand inside it (#487); and a
     * specific code comes before a catch-all. The event sub-process the walk comes out of is skipped, so the error
     * thrown by a handler is not caught by the handler itself.
     */
    private Optional<Catcher> findCatcher(Token source, String code, boolean error, boolean fromStep) {
        Token host;
        if (fromStep) {
            host = source;
        } else {
            host = source.scopeTokenId() == null ? null : tree().get(source.scopeTokenId()).orElse(null);
        }
        String leaving = null;
        while (host != null) {
            if (host.kind() == TokenKind.SCOPE) {
                SubProcess caught = matchEventSubProcess(host, code, error, leaving);
                if (caught != null) {
                    return Optional.of(new Catcher(null, caught, host));
                }
            }
            if (host.scopeTokenId() != null) {
                BoundaryEvent specific = null;
                BoundaryEvent any = null;
                for (BoundaryEvent b : process.boundariesOf(host.nodeId())) {
                    Optional<EventDefinition> definition = catching(b, error);
                    if (definition.isEmpty()) {
                        continue;
                    }
                    String declared = codeOf(definition.get(), error);
                    if (declared == null && any == null) {
                        any = b;
                    } else if (declared != null && declared.equals(code) && specific == null) {
                        specific = b;
                    }
                }
                BoundaryEvent caught = specific != null ? specific : any;
                if (caught != null) {
                    return Optional.of(new Catcher(caught, null, host));
                }
            }
            leaving = host.nodeId();
            host = host.scopeTokenId() == null ? null : tree().get(host.scopeTokenId()).orElse(null);
        }
        return Optional.empty();
    }

    /** The event sub-process of a scope whose error or escalation start matches the code, or null. */
    private SubProcess matchEventSubProcess(Token scope, String code, boolean error, String leaving) {
        SubProcess specific = null;
        SubProcess any = null;
        for (SubProcess esp : process.eventSubProcessesOf(scope.nodeId())) {
            if (esp.id().equals(leaving)) {
                continue;
            }
            io.github.dhluong90.bpm.process.internal.model.bpmn.StartEvent start = process.triggerOf(esp.id())
                    .orElse(null);
            if (start == null || start.eventDefinitions().size() != 1) {
                continue;
            }
            EventDefinition d = start.eventDefinitions().get(0);
            if (error ? !(d instanceof ErrorEventDefinition) : !(d instanceof EscalationEventDefinition)) {
                continue;
            }
            String declared = codeOf(d, error);
            if (declared == null && any == null) {
                any = esp;
            } else if (declared != null && declared.equals(code) && specific == null) {
                specific = esp;
            }
        }
        return specific != null ? specific : any;
    }

    /** Runs the flow of a catching boundary in the scope of its host. */
    private void runCatch(Catcher found, Token source, String code, String trigger, boolean fromStep) {
        if (found.eventSubProcess() != null) {
            SubProcess esp = found.eventSubProcess();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("code", code == null ? "-" : code);
            startEventSubProcess(found.host(), process.triggerOf(esp.id()).orElseThrow(), trigger, details,
                    fromStep ? source.id() : null);
            return;
        }
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
        found.ifPresent(c -> details.put("caughtBy", c.boundary() != null ? c.boundary().id()
                : c.eventSubProcess().id()));
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
        found.ifPresent(c -> details.put("caughtBy", c.boundary() != null ? c.boundary().id()
                : c.eventSubProcess().id()));
        history("ESCALATION_THROWN", thrower, details);
        if (found.isEmpty()) {
            return false;
        }
        runCatch(found.get(), thrower, code, "ESCALATION", false);
        Catcher caught = found.get();
        return caught.boundary() != null ? caught.boundary().cancelActivity()
                : process.triggerOf(caught.eventSubProcess().id()).map(
                        io.github.dhluong90.bpm.process.internal.model.bpmn.StartEvent::isInterrupting).orElse(true);
    }

    @Override
    public void startEventSubProcess(Token scope, io.github.dhluong90.bpm.process.internal.model.bpmn.StartEvent start,
            String trigger, Map<String, Object> details, String exceptWorkOf) {
        SubProcess esp = (SubProcess) process.node(process.containerOf(start.id())).orElseThrow();
        boolean interrupting = start.isInterrupting();
        if (interrupting) {
            // The other work of the scope is cancelled with its tasks, jobs, called children and waits; the scope stays.
            for (Token child : tree().live(scope.id())) {
                cancelTree(child, exceptWorkOf, CancelCause.INTERRUPTING_EVENT_SUBPROCESS);
            }
        }
        Token inner = createToken(TokenKind.SCOPE, esp.id(), scope.id());
        Map<String, Object> entry = new LinkedHashMap<>(details);
        entry.put("eventSubProcessId", esp.id());
        entry.put("trigger", trigger);
        entry.put("interrupting", interrupting);
        entry.put("scopeTokenId", inner.id());
        history("EVENT_SUBPROCESS_STARTED", inner, entry);
        arming.arm(this, inner);
        enqueue(createToken(TokenKind.FLOW, start.id(), inner.id()));
    }

    @Override
    public void terminate(Token token) {
        int cancelled = 0;
        Token scope = tree().get(token.scopeTokenId()).orElseThrow();
        for (Token other : tree().children(scope.id())) {
            if (!other.id().equals(token.id()) && other.status().live()) {
                cancelled += cancelTree(other, null, CancelCause.TERMINATE_END);
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
