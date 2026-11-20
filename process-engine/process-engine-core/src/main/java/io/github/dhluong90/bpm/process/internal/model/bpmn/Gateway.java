package io.github.dhluong90.bpm.process.internal.model.bpmn;

/**
 * BPMN {@code tGateway}.
 */
public sealed interface Gateway extends FlowNode
        permits ExclusiveGateway, InclusiveGateway, ParallelGateway, ComplexGateway, EventBasedGateway {

    /** @return the gateway direction */
    GatewayDirection gatewayDirection();
}
