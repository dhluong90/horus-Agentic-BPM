package io.github.dhluong90.bpm.process.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.internal.exec.CompiledProcess;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.model.horus.HandlerRef;
import io.github.dhluong90.bpm.process.internal.model.horus.InputMapping;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BpmnModelBuilderTest {

    @Test
    void builder_fills_incoming_outgoing_and_extensions() {
        Process process = BpmnModelBuilder.process("card-fee-review")
                .startEvent("start")
                .serviceTask("load-profile", "cardProfiles.load", Map.of("customerId", "${instance.customerId}"), "profile")
                .exclusiveGateway("route", "route__auto")
                .userTask("review", "role:CARD_OFFICER", "review")
                .endEvent("end")
                .chain("start", "load-profile", "route")
                .flow("route__review", "route", "review", "profile.tier != 'GOLD'")
                .flow("route__auto", "route", "end")
                .chain("review", "end")
                .build();
        CompiledProcess compiled = CompiledProcess.builder("D1", process).build();
        FlowNode load = compiled.node("load-profile").orElseThrow();
        assertThat(load.incoming()).containsExactly("start__load-profile");
        assertThat(load.outgoing()).containsExactly("load-profile__route");
        assertThat(load.extensions().first(HandlerRef.class)).map(HandlerRef::handler).contains("cardProfiles.load");
        assertThat(load.extensions().all(InputMapping.class)).extracting(InputMapping::name).containsExactly("customerId");
        assertThat(compiled.outgoing("route")).hasSize(2);
        assertThat(compiled.node("end").orElseThrow().incoming()).hasSize(2);
    }
}
