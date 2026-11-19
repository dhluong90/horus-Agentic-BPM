package io.github.dhluong90.bpm.process.internal.model.bpmn;

import java.util.List;
import java.util.Objects;

/**
 * BPMN {@code parallelGateway}.
 *
 * @param id id
 * @param name name
 * @param incoming incoming
 * @param outgoing outgoing
 * @param gatewayDirection gatewayDirection
 * @param info info
 */
public record ParallelGateway(
        String id,
        String name,
        List<String> incoming,
        List<String> outgoing,
        GatewayDirection gatewayDirection,
        ElementInfo info) implements Gateway {

    public ParallelGateway {
        Objects.requireNonNull(id, "id must not be null");
        incoming = incoming == null ? List.of() : List.copyOf(incoming);
        outgoing = outgoing == null ? List.of() : List.copyOf(outgoing);
        gatewayDirection = gatewayDirection == null ? GatewayDirection.UNSPECIFIED : gatewayDirection;
        info = info == null ? ElementInfo.empty() : info;
    }
}
