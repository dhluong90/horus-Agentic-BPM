package io.github.dhluong90.bpm.process.internal.model.bpmn;

import java.util.List;
import java.util.Objects;

/**
 * BPMN {@code complexGateway}.
 *
 * @param id id
 * @param name name
 * @param incoming incoming
 * @param outgoing outgoing
 * @param gatewayDirection gatewayDirection
 * @param defaultFlow defaultFlow
 * @param activationCondition activationCondition
 * @param info info
 */
public record ComplexGateway(
        String id,
        String name,
        List<String> incoming,
        List<String> outgoing,
        GatewayDirection gatewayDirection,
        String defaultFlow,
        Expression activationCondition,
        ElementInfo info) implements Gateway {

    public ComplexGateway {
        Objects.requireNonNull(id, "id must not be null");
        incoming = incoming == null ? List.of() : List.copyOf(incoming);
        outgoing = outgoing == null ? List.of() : List.copyOf(outgoing);
        gatewayDirection = gatewayDirection == null ? GatewayDirection.UNSPECIFIED : gatewayDirection;
        info = info == null ? ElementInfo.empty() : info;
    }
}
