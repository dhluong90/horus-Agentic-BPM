package io.github.dhluong90.bpm.process.internal.exec.behavior.gateway;

import io.github.dhluong90.bpm.process.internal.exec.BehaviorModule;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorRegistry;

/**
 * Behaviours of the gateway stream: inclusive and complex gateways. Owned by that stream; registers nothing until it lands.
 */
public final class GatewayBehaviors implements BehaviorModule {

    @Override
    public void register(BehaviorRegistry registry) {
        registry.register(new ComplexGatewayBehavior()).compileStep(new ComplexGatewayBehavior.ArityStep());
    }
}
