package io.github.dhluong90.bpm.process.internal.exec.behavior.gateway;

import io.github.dhluong90.bpm.process.internal.exec.BehaviorModule;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorRegistry;

/**
 * Behaviours of the gateway stream: the inclusive gateway (#494). The complex gateway follows in this module.
 */
public final class GatewayBehaviors implements BehaviorModule {

    @Override
    public void register(BehaviorRegistry registry) {
        registry.register(new InclusiveGatewayBehavior())
                .compileStep(new InclusiveJoinCompileStep())
                .settleStep(new InclusiveJoinSettleStep());
        registry.register(new ComplexGatewayBehavior()).compileStep(new ComplexGatewayBehavior.ArityStep());
    }
}
