package io.github.dhluong90.bpm.process.bpmn.subset;

import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import java.util.Map;

/**
 * The stage that adds each BPMN element, from the coverage table in section 6.1 of
 * {@code docs/architecture/standards-compliance.md}. A refusal names this stage and never a sprint or a date.
 * {@code S0} is the current stage: an element the table lists there but this release has not finished is named
 * with it. An element the table does not list is {@code not planned}.
 */
final class BpmnStages {

    static final String S0 = "S0";
    static final String S1 = "S1";
    static final String S2 = "S2";
    static final String S3 = "S3";
    static final String NOT_PLANNED = DefinitionProblem.NOT_PLANNED;

    private static final Map<String, String> ELEMENTS = Map.ofEntries(
            Map.entry("callActivity", S0),
            Map.entry("transaction", S0),
            Map.entry("intermediateThrowEvent", S0),
            Map.entry("intermediateCatchEvent", S1),
            Map.entry("boundaryEvent", S1),
            Map.entry("subProcess", S1),
            Map.entry("loopCharacteristics", S1),
            Map.entry("error", S1),
            Map.entry("escalation", S1),
            Map.entry("signal", S2),
            Map.entry("eventBasedGateway", S2));

    private static final Map<String, String> EVENT_DEFINITIONS = Map.of(
            "timerEventDefinition", S1,
            "errorEventDefinition", S1,
            "escalationEventDefinition", S1,
            "terminateEventDefinition", S1,
            "linkEventDefinition", S1,
            "messageEventDefinition", S2,
            "signalEventDefinition", S2,
            "conditionalEventDefinition", S2,
            "compensateEventDefinition", S1,
            "cancelEventDefinition", NOT_PLANNED);

    private BpmnStages() {
    }

    /** @param local a BPMN element name @return its stage, or {@code not planned} */
    static String of(String local) {
        if (local.endsWith("EventDefinition")) {
            return EVENT_DEFINITIONS.getOrDefault(local, NOT_PLANNED);
        }
        return ELEMENTS.getOrDefault(local, NOT_PLANNED);
    }

    /** @param stage a stage label @return the sentence that says when the element is supported */
    static String message(String stage) {
        if (NOT_PLANNED.equals(stage)) {
            return "is not supported and is not planned";
        }
        return S0.equals(stage) ? "is not supported yet; it belongs to stage S0"
                : "is not supported in this release; stage " + stage + " adds it";
    }
}
