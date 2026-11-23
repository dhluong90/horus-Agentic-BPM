package io.github.dhluong90.bpm.process.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.internal.exec.BehaviorCatalog;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorRegistry;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ComplexGateway;
import io.github.dhluong90.bpm.process.internal.model.bpmn.InclusiveGateway;
import org.junit.jupiter.api.Test;

class BehaviorCatalogTest {

    @Test
    void catalog_lists_the_five_stream_modules_and_builds_a_registry() {
        assertThat(BehaviorCatalog.modules()).hasSize(5);
        BehaviorRegistry registry = BehaviorCatalog.registry();
        assertThat(registry.behaviorFor(new InclusiveGateway("g", null, null, null, null, null, null)))
                .as("the gateway stream registers the inclusive gateway (#494)").isPresent();
        assertThat(registry.behaviorFor(new ComplexGateway("c", null, null, null, null, null, null, null)))
                .as("the gateway stream registers the complex gateway (#500)").isPresent();
        assertThat(registry.settleSteps()).hasSize(1);
    }
}
