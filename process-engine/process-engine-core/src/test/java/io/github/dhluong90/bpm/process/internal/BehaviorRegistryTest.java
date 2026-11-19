package io.github.dhluong90.bpm.process.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.internal.exec.BehaviorRegistry;
import io.github.dhluong90.bpm.process.internal.exec.EventDefinitionKey;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.FlowNodeBehavior;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.MessageEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.StartEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.TimerEventDefinition;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** DESIGN 5.8: behaviours are keyed by node class and event definition, and lookup is exact. */
class BehaviorRegistryTest {

    record StartBehavior(EventDefinitionKey key) implements FlowNodeBehavior<StartEvent> {
        @Override
        public Class<StartEvent> nodeType() {
            return StartEvent.class;
        }

        @Override
        public EventDefinitionKey eventDefinition() {
            return key;
        }

        @Override
        public void enter(StartEvent node, Token token, ExecutionContext ctx) {
        }
    }

    static StartEvent start(List<EventDefinition> definitions, List<String> refs) {
        return new StartEvent("s", null, null, null, definitions, refs, null, false, true, null);
    }

    static final TimerEventDefinition TIMER = new TimerEventDefinition("t", null, null, null, null);
    static final MessageEventDefinition MESSAGE = new MessageEventDefinition("m", "msg", null, null);

    @Test
    void none_timer_and_multiple_start_events_find_different_behaviours() {
        StartBehavior none = new StartBehavior(EventDefinitionKey.NONE);
        StartBehavior timer = new StartBehavior(EventDefinitionKey.of(TimerEventDefinition.class));
        StartBehavior multiple = new StartBehavior(EventDefinitionKey.MULTIPLE);
        BehaviorRegistry registry = new BehaviorRegistry().register(none).register(timer).register(multiple);

        assertThat(registry.behaviorFor(start(List.of(), List.of()))).containsSame(none);
        assertThat(registry.behaviorFor(start(List.of(TIMER), List.of()))).containsSame(timer);
        assertThat(registry.behaviorFor(start(List.of(TIMER, MESSAGE), List.of()))).containsSame(multiple);
    }

    @Test
    void lookup_is_exact_so_an_unregistered_definition_is_not_executable() {
        BehaviorRegistry registry = new BehaviorRegistry().register(new StartBehavior(EventDefinitionKey.NONE));

        assertThat(registry.behaviorFor(start(List.of(MESSAGE), List.of()))).isEmpty();
        assertThat(registry.behaviorFor(start(List.of(), List.of("shared-timer")))).as("unresolved reference").isEmpty();
    }

    @Test
    void a_shared_definition_resolves_to_its_class() {
        StartBehavior timer = new StartBehavior(EventDefinitionKey.of(TimerEventDefinition.class));
        BehaviorRegistry registry = new BehaviorRegistry().register(timer);
        Map<String, EventDefinition> shared = Map.of("shared-timer", TIMER);

        assertThat(registry.behaviorFor(start(List.of(), List.of("shared-timer")),
                id -> Optional.ofNullable(shared.get(id)))).containsSame(timer);
    }

    @Test
    void two_behaviours_for_one_key_are_refused() {
        BehaviorRegistry registry = new BehaviorRegistry().register(new StartBehavior(EventDefinitionKey.NONE));
        assertThatThrownBy(() -> registry.register(new StartBehavior(EventDefinitionKey.NONE)))
                .isInstanceOf(IllegalStateException.class);
    }
}
