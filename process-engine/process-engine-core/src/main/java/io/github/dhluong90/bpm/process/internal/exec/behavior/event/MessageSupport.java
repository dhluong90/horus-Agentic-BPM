package io.github.dhluong90.bpm.process.internal.exec.behavior.event;

import io.github.dhluong90.bpm.process.internal.exec.Command;
import io.github.dhluong90.bpm.process.internal.exec.Effect;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionException;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.exec.TokenStatus;
import io.github.dhluong90.bpm.process.internal.exec.behavior.core.StepSupport;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Event;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import io.github.dhluong90.bpm.process.internal.model.bpmn.MessageEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ReceiveTask;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SendTask;
import io.github.dhluong90.bpm.process.internal.model.horus.CorrelationKey;
import io.github.dhluong90.bpm.process.internal.model.horus.OutputMapping;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the message steps share (#491): the message name and correlation key of a step, the subscription a waiting
 * step leaves for the host's correlate call, the delivery that moves the token on, and the message a sending step
 * hands to the host. The key is the value of the variable named by {@code horus:correlationKey}.
 */
final class MessageSupport {

    /** The subscription kind of a message. */
    static final String KIND = "MESSAGE";
    /** Longest correlation key, in characters. */
    static final int MAX_KEY_LENGTH = 200;

    private MessageSupport() {
    }

    /** @return the message name of a message event or task */
    static String name(FlowNode node) {
        String name = switch (node) {
            case ReceiveTask t -> t.messageRef();
            case SendTask t -> t.messageRef();
            case Event e -> e.eventDefinitions().stream().filter(MessageEventDefinition.class::isInstance)
                    .map(MessageEventDefinition.class::cast).map(MessageEventDefinition::messageRef).findFirst()
                    .orElse(null);
            default -> null;
        };
        if (name == null) {
            throw new ExecutionException("MESSAGE_NAME_MISSING", node.id(), "The step names no message");
        }
        return name;
    }

    /**
     * @param node the step
     * @param token the token on it
     * @param ctx the context
     * @param required whether a missing key is a failure of the step
     * @return the key as text, or null when the step names no key variable and none is required
     */
    static String key(FlowNode node, Token token, ExecutionContext ctx, boolean required) {
        String variable = node.extensions().first(CorrelationKey.class).map(CorrelationKey::variable).orElse(null);
        if (variable == null && ctx.process().eventGatewayOf(node.id()).filter(g -> g.instantiate()
                && g.eventGatewayType() == io.github.dhluong90.bpm.process.internal.model.bpmn.EventBasedGatewayType.PARALLEL)
                .isPresent() && ctx.businessKey() != null) {
            // #499: after a parallel instantiating gateway no instance existed to hold a key; the instance's own is used.
            return ctx.businessKey();
        }
        if (variable == null) {
            if (required) {
                throw new ExecutionException("CORRELATION_KEY_REQUIRED", node.id(), "The step names no correlation key");
            }
            return null;
        }
        Object value = ctx.variables().visible(token).get(variable);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new ExecutionException("CORRELATION_VARIABLE_MISSING", node.id(), "Correlation variable " + variable
                    + " is not set");
        }
        String text = String.valueOf(value);
        if (text.length() > MAX_KEY_LENGTH) {
            throw new ExecutionException("CORRELATION_KEY_TOO_LONG", node.id(), "Correlation variable " + variable
                    + " is longer than " + MAX_KEY_LENGTH + " characters");
        }
        return text;
    }

    /** The token waits for its message: one subscription, the token in {@code WAITING_EVENT}. */
    static void waitFor(FlowNode node, Token token, ExecutionContext ctx) {
        String name = name(node);
        String key = key(node, token, ctx, true);
        String subscriptionId = ctx.nextId();
        ctx.update(new Token(token.id(), token.kind(), TokenStatus.WAITING_EVENT, token.scopeTokenId(), token.nodeId(),
                subscriptionId, token.locals()));
        ctx.effect(new Effect.CreateSubscription(subscriptionId, KIND, name, key, token.id(), node.id()));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("message", name);
        details.put("correlationKey", key);
        details.put("subscriptionId", subscriptionId);
        ctx.history("MESSAGE_WAITING", token, details);
    }

    /** The message arrived: its named payload fields become variables and the token leaves the step. */
    static void deliver(FlowNode node, Token token, Command trigger, ExecutionContext ctx) {
        receive(node, token, trigger, ctx);
        Token active = token.withStatus(TokenStatus.ACTIVE);
        ctx.update(active);
        ctx.history("NODE_COMPLETED", token, Map.of());
        ctx.takeOutgoing(active, node);
    }

    /** The first half of a delivery: the named payload fields become variables and the receipt is recorded (#487). */
    static void receive(FlowNode node, Token token, Command trigger, ExecutionContext ctx) {
        if (!(trigger instanceof Command.CorrelateMessage message)) {
            throw new ExecutionException("COMMAND_NOT_SUPPORTED", node.id(), "Expected a message");
        }
        Map<String, Object> brought = new LinkedHashMap<>();
        for (OutputMapping out : node.extensions().all(OutputMapping.class)) {
            // Only what the step names is copied; a field the message lacks is a failure of the step.
            if (!message.payload().containsKey(out.from())) {
                throw new ExecutionException("MESSAGE_FIELD_MISSING", node.id(), "The message has no field " + out.from());
            }
            brought.put(out.name(), message.payload().get(out.from()));
        }
        brought.forEach((k, v) -> ctx.variables().set(k, v));
        ctx.history("MESSAGE_RECEIVED", token, Map.of("message", message.messageName(), "subscriptionId",
                message.subscriptionId()));
    }

    /** The step hands its message to the host's adapter, once; the caller moves the token on. */
    static void send(FlowNode node, Token token, ExecutionContext ctx) {
        String name = name(node);
        String key = key(node, token, ctx, false);
        Map<String, Object> payload = StepSupport.inputs(node, token, ctx);
        String messageId = ctx.nextId();
        ctx.effect(new Effect.ThrowMessage(messageId, name, key, payload, token.id(), node.id()));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("message", name);
        if (key != null) {
            details.put("correlationKey", key);
        }
        details.put("messageId", messageId);
        ctx.history("MESSAGE_SENT", token, details);
    }
}
