package io.github.dhluong90.bpm.process.internal.model.bpmn;

import java.util.List;
import java.util.Objects;

/**
 * BPMN {@code eventBasedGateway}.
 *
 * @param id id
 * @param name name
 * @param incoming incoming
 * @param outgoing outgoing
 * @param gatewayDirection gatewayDirection
 * @param instantiate instantiate
 * @param eventGatewayType eventGatewayType
 * @param info info
 */
public record EventBasedGateway(
        String id,
        String name,
        List<String> incoming,
        List<String> outgoing,
        GatewayDirection gatewayDirection,
        boolean instantiate,
        EventBasedGatewayType eventGatewayType,
        ElementInfo info) implements Gateway {

    public EventBasedGateway {
        Objects.requireNonNull(id, "id must not be null");
        incoming = incoming == null ? List.of() : List.copyOf(incoming);
        outgoing = outgoing == null ? List.of() : List.copyOf(outgoing);
        gatewayDirection = gatewayDirection == null ? GatewayDirection.UNSPECIFIED : gatewayDirection;
        info = info == null ? ElementInfo.empty() : info;
    }
}
