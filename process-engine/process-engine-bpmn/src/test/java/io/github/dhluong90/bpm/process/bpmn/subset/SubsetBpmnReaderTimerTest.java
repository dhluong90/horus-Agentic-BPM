package io.github.dhluong90.bpm.process.bpmn.subset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.BpmnUnsupportedElementException;
import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.ValidationFailedException;
import io.github.dhluong90.bpm.process.bpmn.xml.Limits;
import io.github.dhluong90.bpm.process.internal.exec.BehaviorCatalog;
import io.github.dhluong90.bpm.process.internal.exec.ProcessCompiler;
import io.github.dhluong90.bpm.process.internal.json.CanonicalProcessWriter;
import io.github.dhluong90.bpm.process.internal.model.bpmn.IntermediateCatchEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.model.bpmn.TimerEventDefinition;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The intermediate timer catch event of the subset: a duration or a date; cycles and other events are named. */
class SubsetBpmnReaderTimerTest {

    private static final String HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
            + "xmlns:horus=\"urn:horus-bpm:bpmn:extensions:v1\" id=\"d\" targetNamespace=\"urn:t\">"
            + "<bpmn:process id=\"card-activation-reminder\" isExecutable=\"true\">"
            + "<bpmn:startEvent id=\"start\"/>";
    private static final String TAIL = "<bpmn:endEvent id=\"end\"/>"
            + "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"wait\"/>"
            + "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"wait\" targetRef=\"end\"/>"
            + "</bpmn:process></bpmn:definitions>";

    private final SubsetBpmnReader reader = new SubsetBpmnReader(Limits.DEFAULTS);

    private static byte[] waitFor(String timerBody) {
        return (HEAD + "<bpmn:intermediateCatchEvent id=\"wait\" name=\"Three days\">" + timerBody
                + "</bpmn:intermediateCatchEvent>" + TAIL).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void PRE_006_a_duration_timer_reads_compiles_and_hashes_as_a_timer_step() {
        Process process = reader.read(waitFor("<bpmn:timerEventDefinition id=\"t\"><bpmn:timeDuration>P3D"
                + "</bpmn:timeDuration></bpmn:timerEventDefinition>"));

        IntermediateCatchEvent wait = (IntermediateCatchEvent) process.flowElements().stream()
                .filter(e -> e.id().equals("wait")).findFirst().orElseThrow();
        assertThat(((TimerEventDefinition) wait.eventDefinitions().get(0)).timeDuration().text()).isEqualTo("P3D");
        assertThat(wait.incoming()).containsExactly("f1");
        assertThat(new ProcessCompiler(BehaviorCatalog.registry(), 500).compile("D1", process).node("wait")).isPresent();
        assertThat(CanonicalProcessWriter.canonicalText(process))
                .contains("{\"duration\":\"P3D\",\"id\":\"wait\",\"name\":\"Three days\",\"next\":\"end\",\"type\":\"timer\"}");
    }

    @Test
    void PRE_006_a_date_timer_reads_as_an_until_step() {
        Process process = reader.read(waitFor("<bpmn:timerEventDefinition><bpmn:timeDate>2027-04-01T00:00:00+07:00"
                + "</bpmn:timeDate></bpmn:timerEventDefinition>"));

        assertThat(CanonicalProcessWriter.canonicalText(process)).contains("\"until\":\"2027-04-01T00:00:00+07:00\"");
    }

    @Test
    void PRE_006_a_timer_cycle_is_named_as_not_supported_yet() {
        assertThatThrownBy(() -> reader.read(waitFor("<bpmn:timerEventDefinition><bpmn:timeCycle>R3/PT1H"
                + "</bpmn:timeCycle></bpmn:timerEventDefinition>")))
                .isInstanceOf(BpmnUnsupportedElementException.class)
                .satisfies(e -> assertThat(((BpmnUnsupportedElementException) e).problems())
                        .extracting(DefinitionProblem::element, DefinitionProblem::elementId)
                        .containsExactly(org.assertj.core.groups.Tuple.tuple("timeCycle", "wait")));
    }

    @Test
    void PRE_006_a_malformed_or_calendar_duration_fails_validation_at_import() {
        for (String duration : new String[] {"3 days", "P1M", "PT0S"}) {
            assertThatThrownBy(() -> reader.read(waitFor("<bpmn:timerEventDefinition><bpmn:timeDuration>" + duration
                    + "</bpmn:timeDuration></bpmn:timerEventDefinition>")))
                    .as(duration).isInstanceOf(ValidationFailedException.class)
                    .satisfies(e -> assertThat(((ValidationFailedException) e).problems())
                            .extracting(DefinitionProblem::code).containsExactly("TIMER_INVALID"));
        }
    }

    @Test
    void PRE_006_a_signal_catch_event_is_still_named_as_unsupported() {
        assertThatThrownBy(() -> reader.read(waitFor("<bpmn:signalEventDefinition/>")))
                .isInstanceOf(BpmnUnsupportedElementException.class)
                .satisfies(e -> assertThat(((BpmnUnsupportedElementException) e).problems())
                        .extracting(DefinitionProblem::element)
                        .containsExactly("intermediateCatchEvent with signalEventDefinition"));
    }
}
