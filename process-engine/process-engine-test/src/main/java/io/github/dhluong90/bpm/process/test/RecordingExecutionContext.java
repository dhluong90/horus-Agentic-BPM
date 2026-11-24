package io.github.dhluong90.bpm.process.test;

import io.github.dhluong90.bpm.process.internal.exec.CompiledProcess;
import io.github.dhluong90.bpm.process.internal.exec.Effect;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.FacetKey;
import io.github.dhluong90.bpm.process.internal.exec.HistoryEntry;
import io.github.dhluong90.bpm.process.internal.exec.ScopeWalker;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.exec.TokenKind;
import io.github.dhluong90.bpm.process.internal.exec.TokenStatus;
import io.github.dhluong90.bpm.process.internal.exec.TokenTree;
import io.github.dhluong90.bpm.process.internal.exec.VariableScopes;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SequenceFlow;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * An {@link ExecutionContext} for unit tests of one behaviour: it records effects, history, flows taken and token
 * changes, and does not enter the next node. Expressions are evaluated by a function the test supplies.
 */
public final class RecordingExecutionContext implements ExecutionContext {

    private final CompiledProcess process;
    private final Clock clock;
    private final List<Token> tokens = new ArrayList<>();
    private final Map<String, Object> variables = new LinkedHashMap<>();
    private final List<Effect> effects = new ArrayList<>();
    private final List<HistoryEntry> history = new ArrayList<>();
    private final List<String> flowsTaken = new ArrayList<>();
    private final List<String> completed = new ArrayList<>();
    private BiFunction<String, Map<String, Object>, Object> evaluator = (expression, vars) -> {
        throw new UnsupportedOperationException("No evaluator set; call evaluateWith");
    };
    private String actor;
    private int ids;

    /**
     * @param process the compiled process the behaviour runs in
     * @param clock the clock
     */
    public RecordingExecutionContext(CompiledProcess process, Clock clock) {
        this.process = Objects.requireNonNull(process, "process must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * @param function evaluates an expression against the visible variables
     * @return this
     */
    public RecordingExecutionContext evaluateWith(BiFunction<String, Map<String, Object>, Object> function) {
        this.evaluator = Objects.requireNonNull(function, "function must not be null");
        return this;
    }

    /** @param name variable @param value value @return this */
    public RecordingExecutionContext variable(String name, Object value) {
        variables.put(name, value);
        return this;
    }

    /** @param who the actor of the current command @return this */
    public RecordingExecutionContext actor(String who) {
        this.actor = who;
        return this;
    }

    /** @return effects in order */
    public List<Effect> effects() {
        return List.copyOf(effects);
    }

    /** @return history in order */
    public List<HistoryEntry> historyEntries() {
        return List.copyOf(history);
    }

    /** @return ids of sequence flows taken, in order */
    public List<String> flowsTaken() {
        return List.copyOf(flowsTaken);
    }

    /** @return ids of tokens completed */
    public List<String> completedTokens() {
        return List.copyOf(completed);
    }

    /** @return process variables */
    public Map<String, Object> processVariables() {
        return Map.copyOf(variables);
    }

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
                java.util.Collections.reverse(chain);
                chain.forEach(scope -> out.putAll(scope.locals()));
                out.putAll(in.locals());
                return out;
            }
        };
    }

    @Override
    public CompiledProcess process() {
        return process;
    }

    @Override
    public void takeOutgoing(Token token, FlowNode from) {
        for (SequenceFlow flow : process.outgoing(from.id())) {
            flowsTaken.add(flow.id());
        }
    }

    @Override
    public void take(Token token, String sequenceFlowId) {
        flowsTaken.add(sequenceFlowId);
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
        completed.add(token.id());
    }

    @Override
    public void cancelSubtree(Token scope, io.github.dhluong90.bpm.process.api.CancelCause cause) {
        for (Token t : tree().children(scope.id())) {
            update(t.withStatus(TokenStatus.CANCELLED));
        }
        update(scope.withStatus(TokenStatus.CANCELLED));
    }

    /** Records the scope entry: a {@code SCOPE} token for the block, and the arriving token ends. */
    @Override
    public void enterScope(Token token, io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode block) {
        createToken(io.github.dhluong90.bpm.process.internal.exec.TokenKind.SCOPE, block.id(), token.scopeTokenId());
        update(token.withStatus(TokenStatus.COMPLETED));
    }

    /** Records the start of a compensation of the token's scope as its effect. */
    @Override
    public void compensate(Token failing, String cause) {
        effects.add(new Effect.StartCompensation(failing.scopeTokenId() == null ? failing.id() : failing.scopeTokenId(),
                failing.nodeId(), cause));
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
                token == null ? null : token.activityInstanceId(), actor, now(), details));
    }

    @Override
    public String nextId() {
        return "ID" + (++ids);
    }

    @Override
    public Object evaluate(String expression, String languageId, Token in) {
        return evaluator.apply(expression, variables().visible(in));
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
        return clock.instant();
    }
}
