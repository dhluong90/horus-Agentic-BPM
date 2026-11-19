package io.github.dhluong90.bpm.process.internal.model.bpmn;

import java.util.List;
import java.util.Objects;

/**
 * BPMN {@code exclusiveGateway}.
 *
 * @param id id
 * @param name name
 * @param incoming incoming
 * @param outgoing outgoing
 * @param gatewayDirection gatewayDirection
 * @param defaultFlow defaultFlow
 * @param info info
 */
public record ExclusiveGateway(
        String id,
        String name,
        List<String> incoming,
        List<String> outgoing,
        GatewayDirection gatewayDirection,
        String defaultFlow,
        ElementInfo info) implements Gateway {

    public ExclusiveGateway {
        Objects.requireNonNull(id, "id must not be null");
        incoming = incoming == null ? List.of() : List.copyOf(incoming);
        outgoing = outgoing == null ? List.of() : List.copyOf(outgoing);
        gatewayDirection = gatewayDirection == null ? GatewayDirection.UNSPECIFIED : gatewayDirection;
        info = info == null ? ElementInfo.empty() : info;
    }
}
