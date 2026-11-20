package io.github.dhluong90.bpm.process.bpmn.subset;

import io.github.dhluong90.bpm.process.api.BpmnUnsafeXmlException;
import io.github.dhluong90.bpm.process.api.BpmnUnsupportedElementException;
import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.DefinitionTooLargeException;
import io.github.dhluong90.bpm.process.api.ValidationFailedException;
import io.github.dhluong90.bpm.process.bpmn.xml.BpmnNamespaces;
import io.github.dhluong90.bpm.process.bpmn.xml.Limits;
import io.github.dhluong90.bpm.process.bpmn.xml.XmlHardening;
import io.github.dhluong90.bpm.process.internal.json.Quote;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ActivityAttributes;
import io.github.dhluong90.bpm.process.internal.model.bpmn.BusinessRuleTask;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ElementInfo;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EndEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ExclusiveGateway;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Expression;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ExtensionBag;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowElement;
import io.github.dhluong90.bpm.process.internal.model.bpmn.GatewayDirection;
import io.github.dhluong90.bpm.process.internal.model.bpmn.IntermediateCatchEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.TimerEventDefinition;
import io.github.dhluong90.bpm.process.internal.exec.behavior.event.TimerSchedule;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ParallelGateway;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ProcessType;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SequenceFlow;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ServiceTask;
import io.github.dhluong90.bpm.process.internal.model.bpmn.StartEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.UserTask;
import io.github.dhluong90.bpm.process.internal.model.foreign.ForeignFragment;
import io.github.dhluong90.bpm.process.internal.model.horus.DecisionRef;
import io.github.dhluong90.bpm.process.internal.model.horus.HandlerRef;
import io.github.dhluong90.bpm.process.internal.model.horus.HorusExtension;
import io.github.dhluong90.bpm.process.internal.model.horus.InputMapping;
import io.github.dhluong90.bpm.process.internal.model.horus.ResultVariable;
import io.github.dhluong90.bpm.process.internal.model.horus.TaskAssignment;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads the executable BPMN subset of the first release into the semantic model: none {@code startEvent} and
 * {@code endEvent}, {@code serviceTask} ({@code horus:handler}), {@code businessRuleTask} ({@code horus:ruleSet}),
 * {@code userTask} ({@code horus:assignee}), {@code exclusiveGateway} with {@code default}, {@code parallelGateway},
 * an intermediate timer catch event with {@code timeDuration} or {@code timeDate}, and
 * {@code sequenceFlow} with an optional {@code conditionExpression} in {@code urn:horus-bpm:expr:v1}. Inputs are
 * {@code horus:input} elements and the result variable is {@code horus:resultVariable}.
 *
 * <p>Every other BPMN element is listed with its id, line and the stage that adds it, in one
 * {@code BPMN_UNSUPPORTED_ELEMENT}. BPMNDI is skipped (layout is not behaviour). Foreign extensions are skipped and
 * never executed.
 *
 * <p>Safety: DTD support and external entities are off; any {@code DOCTYPE} or entity is {@code BPMN_UNSAFE_XML}
 * and nothing is resolved; size, depth and element count are bounded while reading.
 *
 * <p>Owned by the walking skeleton. Replaced by {@code BpmnXmlReader} when the BPMN XML stream lands; the subset
 * tests stay and run against that reader.
 */
public final class SubsetBpmnReader {

    private static final String BPMN = BpmnNamespaces.BPMN;
    private static final String HORUS = BpmnNamespaces.HORUS;
    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*$");
    private static final Pattern ASSIGNEE = Pattern.compile("^(user|role|group):[A-Za-z0-9_.@-]{1,100}$");
    private static final Set<String> IGNORED_CHILDREN = Set.of("incoming", "outgoing", "documentation");
    private static final Map<String, String> LATER = Map.ofEntries(
            Map.entry("intermediateCatchEvent", "S1 or S2 (message, signal and conditional catch events)"),
            Map.entry("callActivity", "the next sprint"),
            Map.entry("transaction", "the next sprint (saga)"),
            Map.entry("intermediateThrowEvent", "the next sprint (compensate throw)"),
            Map.entry("boundaryEvent", "S1"),
            Map.entry("subProcess", "S1"),
            Map.entry("inclusiveGateway", "S1"),
            Map.entry("scriptTask", "S1"),
            Map.entry("task", "S1"),
            Map.entry("manualTask", "S1"),
            Map.entry("dataObject", "S1"),
            Map.entry("dataObjectReference", "S1"),
            Map.entry("dataStoreReference", "S1"),
            Map.entry("laneSet", "S1 (display)"),
            Map.entry("sendTask", "S2"),
            Map.entry("receiveTask", "S2"),
            Map.entry("eventBasedGateway", "S2"),
            Map.entry("complexGateway", "S3"),
            Map.entry("adHocSubProcess", "S3"));

    private final Limits limits;

    /** @param limits size, depth and element limits */
    public SubsetBpmnReader(Limits limits) {
        this.limits = limits;
    }

    /**
     * @param source the XML bytes
     * @return the executable process of the document
     */
    public Process read(byte[] source) {
        if (source.length > limits.maxBytes()) {
            throw new DefinitionTooLargeException("The document has " + source.length + " bytes; at most "
                    + limits.maxBytes() + " are accepted (horus.bpm.processes.definitions.max-bytes)");
        }
        String text = XmlHardening.utf8(source);
        XMLInputFactory factory = XmlHardening.newInputFactory(limits);
        try {
            XMLStreamReader xml = factory.createXMLStreamReader(new StringReader(text));
            try {
                XmlHardening.requireUtf8Declaration(xml);
                return new Pass(xml).document();
            } finally {
                xml.close();
            }
        } catch (XMLStreamException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains("DOCTYPE") || message.contains("DTD") || message.contains("ntity")
                    || message.contains("not resolved")) {
                throw new BpmnUnsafeXmlException("DOCTYPE and entity declarations are not accepted");
            }
            throw new ValidationFailedException(List.of(new DefinitionProblem("XML_MALFORMED",
                    "The document is not well-formed XML", null, null,
                    e.getLocation() == null ? 0 : e.getLocation().getLineNumber())));
        }
    }

    private final class Pass {

        private final XMLStreamReader xml;
        private final List<DefinitionProblem> unsupported = new ArrayList<>();
        private final List<DefinitionProblem> invalid = new ArrayList<>();
        private final Set<String> ids = new HashSet<>();
        private int depth;
        private int elements;
        private int previousLine;
        private int startLine;
        private String documentLanguage;
        private final Set<String> sharedDefinitionIds = new HashSet<>();
        private final List<DefinitionProblem> unresolvedRefs = new ArrayList<>();

        Pass(XMLStreamReader xml) {
            this.xml = xml;
        }

        Process document() throws XMLStreamException {
            nextStart();
            if (!BPMN.equals(xml.getNamespaceURI()) || !"definitions".equals(xml.getLocalName())) {
                throw new ValidationFailedException(List.of(new DefinitionProblem("NOT_BPMN",
                        "The root element must be definitions in namespace " + BPMN, xml.getLocalName(), null, line())));
            }
            documentLanguage = attr("expressionLanguage");
            Process process = null;
            while (nextChild()) {
                String ns = xml.getNamespaceURI();
                String local = xml.getLocalName();
                if (BPMN.equals(ns) && "process".equals(local)) {
                    if (process != null) {
                        unsupported(local, attr("id"), "S1 (more than one process, collaboration)");
                        skip();
                    } else {
                        process = process();
                    }
                } else if (BPMN.equals(ns)) {
                    if (local.endsWith("EventDefinition") && attr("id") != null) {
                        sharedDefinitionIds.add(attr("id"));
                    }
                    unsupported(local, attr("id"), "S1 (collaboration and shared root elements)");
                    skip();
                } else {
                    skip();
                }
            }
            for (DefinitionProblem ref : unresolvedRefs) {
                if (!sharedDefinitionIds.contains(ref.message().substring(ref.message().indexOf('\'') + 1,
                        ref.message().indexOf('\'', ref.message().indexOf('\'') + 1)))) {
                    invalid.add(ref);
                }
            }
            if (!unsupported.isEmpty()) {
                throw new BpmnUnsupportedElementException(unsupported);
            }
            if (process == null) {
                invalid.add(DefinitionProblem.of("NO_PROCESS", "The document has no process", null));
            }
            if (!invalid.isEmpty()) {
                throw new ValidationFailedException(invalid);
            }
            return process;
        }

        private Process process() throws XMLStreamException {
            String key = attr("id");
            String name = attr("name");
            int line = line();
            if (key == null || !KEY.matcher(key).matches()) {
                invalid.add(new DefinitionProblem("KEY_INVALID", "The process id is the definition key and must be"
                        + " kebab-case, for example card-fee-review", "process", safe(key), line));
            }
            if (!"true".equals(attr("isExecutable"))) {
                invalid.add(new DefinitionProblem("NOT_EXECUTABLE", "Set isExecutable=\"true\" on the process",
                        "process", safe(key), line));
            }
            List<FlowElement> elements = new ArrayList<>();
            while (nextChild()) {
                String local = xml.getLocalName();
                if (!BPMN.equals(xml.getNamespaceURI())) {
                    skip();
                    continue;
                }
                switch (local) {
                    case "startEvent" -> elements.add(event(true));
                    case "endEvent" -> elements.add(event(false));
                    case "serviceTask" -> elements.add(serviceTask());
                    case "businessRuleTask" -> elements.add(businessRuleTask());
                    case "userTask" -> elements.add(userTask());
                    case "intermediateCatchEvent" -> elements.add(catchEvent());
                    case "exclusiveGateway" -> {
                        String id = id();
                        String gwName = attr("name");
                        String defaultFlow = attr("default");
                        Node children = children(local, id, false);
                        elements.add(new ExclusiveGateway(id, gwName, List.of(), List.of(), GatewayDirection.UNSPECIFIED,
                                defaultFlow, children.info()));
                    }
                    case "parallelGateway" -> {
                        String id = id();
                        String gwName = attr("name");
                        Node children = children(local, id, false);
                        elements.add(new ParallelGateway(id, gwName, List.of(), List.of(), GatewayDirection.UNSPECIFIED,
                                children.info()));
                    }
                    case "sequenceFlow" -> elements.add(sequenceFlow());
                    case "documentation", "extensionElements" -> skip();
                    default -> {
                        unsupported(local, attr("id"), LATER.getOrDefault(local, "a later stage"));
                        skip();
                    }
                }
            }
            return withRefs(new Process(key, name, ProcessType.NONE, true, false, elements, List.of(), List.of(), null));
        }

        private FlowElement event(boolean start) throws XMLStreamException {
            String element = start ? "startEvent" : "endEvent";
            String id = id();
            String name = attr("name");
            while (nextChild()) {
                String local = xml.getLocalName();
                if (BPMN.equals(xml.getNamespaceURI()) && "eventDefinitionRef".equals(local)) {
                    // A reference that does not resolve is a broken document: 422 naming the reference, not
                    // "not executable" at activation.
                    int line = line();
                    String ref = text().strip();
                    unresolvedRefs.add(new DefinitionProblem("EVENT_DEFINITION_REF_UNRESOLVED", "eventDefinitionRef '"
                            + safe(ref) + "' does not resolve to an event definition of this document", element,
                            safe(id), line));
                } else if (BPMN.equals(xml.getNamespaceURI()) && local.endsWith("EventDefinition")) {
                    unsupported(element + " with " + local, id, "S1 or S2 (event definitions)");
                    skip();
                } else if (BPMN.equals(xml.getNamespaceURI()) && !IGNORED_CHILDREN.contains(local)
                        && !"extensionElements".equals(local)) {
                    unsupported(element + " child " + local, id, "a later stage");
                    skip();
                } else {
                    skip();
                }
            }
            return start
                    ? new StartEvent(id, name, List.of(), List.of(), List.of(), List.of(), null, false, true, null)
                    : new EndEvent(id, name, List.of(), List.of(), List.of(), List.of(), null, null);
        }

        /**
         * An intermediate catch event with one {@code timerEventDefinition}: {@code timeDuration} or {@code timeDate}.
         * Every other event definition, and {@code timeCycle}, is named as unsupported.
         */
        private FlowElement catchEvent() throws XMLStreamException {
            String element = "intermediateCatchEvent";
            String id = id();
            String name = attr("name");
            int line = line();
            TimerEventDefinition timer = null;
            int definitions = 0;
            while (nextChild()) {
                String local = xml.getLocalName();
                if (BPMN.equals(xml.getNamespaceURI()) && "timerEventDefinition".equals(local)) {
                    definitions++;
                    timer = timer(id);
                } else if (BPMN.equals(xml.getNamespaceURI()) && local.endsWith("EventDefinition")) {
                    definitions++;
                    unsupported(element + " with " + local, id, "S1 or S2 (event definitions)");
                    skip();
                } else if (BPMN.equals(xml.getNamespaceURI()) && "eventDefinitionRef".equals(local)) {
                    definitions++;
                    unsupported(element + " with eventDefinitionRef", id, "S1 (shared event definitions)");
                    skip();
                } else if (BPMN.equals(xml.getNamespaceURI()) && !IGNORED_CHILDREN.contains(local)
                        && !"extensionElements".equals(local)) {
                    unsupported(element + " child " + local, id, "a later stage");
                    skip();
                } else {
                    skip();
                }
            }
            if (definitions == 0) {
                unsupported(element + " without an event definition", id, "S1 (link and none catch events)");
            } else if (definitions > 1) {
                unsupported(element + " with several event definitions", id, "S1 (multiple events)");
            }
            if (timer != null && definitions == 1) {
                TimerSchedule.problem(timer).ifPresent(problem -> {
                    if (problem.startsWith("timeCycle")) {
                        unsupported.add(new DefinitionProblem("BPMN_UNSUPPORTED_ELEMENT",
                                "is not supported in this release; planned for S1 (timer cycles)", "timeCycle",
                                safe(id), line));
                    } else {
                        invalid.add(new DefinitionProblem("TIMER_INVALID", problem, element, safe(id), line));
                    }
                });
            }
            return new IntermediateCatchEvent(id, name, List.of(), List.of(),
                    timer == null ? List.of() : List.of(timer), List.of(), null, false, null);
        }

        private TimerEventDefinition timer(String eventId) throws XMLStreamException {
            String definitionId = attr("id");
            Expression date = null;
            Expression duration = null;
            Expression cycle = null;
            while (nextChild()) {
                String local = xml.getLocalName();
                if (!BPMN.equals(xml.getNamespaceURI())) {
                    skip();
                    continue;
                }
                switch (local) {
                    case "timeDate" -> date = new Expression(text().strip(), null, null);
                    case "timeDuration" -> duration = new Expression(text().strip(), null, null);
                    case "timeCycle" -> cycle = new Expression(text().strip(), null, null);
                    case "documentation", "extensionElements" -> skip();
                    default -> {
                        unsupported("timerEventDefinition child " + local, eventId, "a later stage");
                        skip();
                    }
                }
            }
            return new TimerEventDefinition(definitionId, date, duration, cycle, null);
        }

        private FlowElement serviceTask() throws XMLStreamException {
            String id = id();
            int line = line();
            String handler = horus("handler");
            String mode = horus("mode");
            String type = horus("type");
            String name = attr("name");
            String result = horus("resultVariable");
            if (type != null) {
                unsupported("serviceTask horus:type=\"" + type + "\"", id, "the next sprint (agentTask)");
            }
            if (mode != null && !"local".equals(mode)) {
                unsupported("serviceTask horus:mode=\"" + mode + "\"", id, "the next sprint (Async Wait-State)");
            }
            if (handler == null) {
                invalid.add(new DefinitionProblem("HANDLER_MISSING", "A serviceTask needs horus:handler", "serviceTask",
                        id, line));
            }
            List<HorusExtension> ext = new ArrayList<>();
            ext.add(new HandlerRef(handler, mode, null));
            if (result != null) {
                ext.add(new ResultVariable(result));
            }
            Node children = children("serviceTask", id, true);
            ext.addAll(children.horus());
            return new ServiceTask(id, name, List.of(), List.of(), ActivityAttributes.defaults(), null, null,
                    info(ext, children.foreign()));
        }

        private FlowElement businessRuleTask() throws XMLStreamException {
            String id = id();
            int line = line();
            String ruleSet = horus("ruleSet");
            String name = attr("name");
            String result = horus("resultVariable");
            if (ruleSet == null) {
                invalid.add(new DefinitionProblem("RULE_SET_MISSING", "A businessRuleTask needs horus:ruleSet",
                        "businessRuleTask", safe(id), line));
            }
            List<HorusExtension> ext = new ArrayList<>();
            ext.add(new DecisionRef(ruleSet));
            if (result != null) {
                ext.add(new ResultVariable(result));
            }
            Node children = children("businessRuleTask", id, true);
            ext.addAll(children.horus());
            return new BusinessRuleTask(id, name, List.of(), List.of(), ActivityAttributes.defaults(), null,
                    info(ext, children.foreign()));
        }

        private FlowElement userTask() throws XMLStreamException {
            String id = id();
            int line = line();
            String assignee = horus("assignee");
            boolean fourEyes = "true".equals(horus("fourEyes"));
            String name = attr("name");
            String result = horus("resultVariable");
            if (assignee == null || !ASSIGNEE.matcher(assignee).matches()) {
                invalid.add(new DefinitionProblem("ASSIGNEE_INVALID",
                        "A userTask needs horus:assignee as user:<id>, role:<role> or group:<group>", "userTask", safe(id), line));
            }
            if (result == null) {
                invalid.add(new DefinitionProblem("RESULT_VARIABLE_REQUIRED", "A userTask needs horus:resultVariable, so"
                        + " a completion result can never overwrite other process variables", "userTask", safe(id), line));
            }
            List<HorusExtension> ext = new ArrayList<>();
            ext.add(new TaskAssignment(assignee, fourEyes));
            if (result != null) {
                ext.add(new ResultVariable(result));
            }
            Node children = children("userTask", id, false);
            return new UserTask(id, name, List.of(), List.of(), ActivityAttributes.defaults(), null,
                    info(ext, children.foreign()));
        }

        private FlowElement sequenceFlow() throws XMLStreamException {
            String id = id();
            String name = attr("name");
            String source = attr("sourceRef");
            String target = attr("targetRef");
            if (source == null || target == null) {
                invalid.add(new DefinitionProblem("FLOW_REF_INVALID", "A sequenceFlow needs sourceRef and targetRef",
                        "sequenceFlow", safe(id), line()));
            }
            Expression condition = null;
            while (nextChild()) {
                String local = xml.getLocalName();
                if (BPMN.equals(xml.getNamespaceURI()) && "conditionExpression".equals(local)) {
                    // A condition without its own language takes the document's (BPMN 2.0 section 8.3.1).
                    String language = attr("language") != null ? attr("language") : documentLanguage;
                    int line = line();
                    String text = text();
                    if (language != null && !BpmnNamespaces.EXPR_V1.equals(language)) {
                        unsupported.add(new DefinitionProblem("BPMN_EXPRESSION_LANGUAGE_UNSUPPORTED", "language "
                                + language + " is not supported; use " + BpmnNamespaces.EXPR_V1 + " (FEEL follows)",
                                "conditionExpression", id, line));
                    }
                    condition = new Expression(text.strip(), language == null ? BpmnNamespaces.EXPR_V1 : language, null);
                } else if (BPMN.equals(xml.getNamespaceURI()) && !"documentation".equals(local)
                        && !"extensionElements".equals(local)) {
                    unsupported("sequenceFlow child " + local, id, "a later stage");
                    skip();
                } else {
                    skip();
                }
            }
            return new SequenceFlow(id, name, source, target, condition, null, null);
        }

        private record Node(List<HorusExtension> horus, List<ForeignFragment> foreign) {
            ElementInfo info() {
                return SubsetBpmnReader.info(horus, foreign);
            }
        }

        private Node children(String element, String id, boolean inputs) throws XMLStreamException {
            List<HorusExtension> horus = new ArrayList<>();
            List<ForeignFragment> foreign = new ArrayList<>();
            while (nextChild()) {
                String ns = xml.getNamespaceURI();
                String local = xml.getLocalName();
                if (BPMN.equals(ns) && IGNORED_CHILDREN.contains(local)) {
                    skip();
                } else if (BPMN.equals(ns) && "extensionElements".equals(local)) {
                    extensions(element, id, inputs, horus, foreign);
                } else if (BPMN.equals(ns)) {
                    unsupported(element + " child " + local, id, LATER.getOrDefault(local, "S1"));
                    skip();
                } else {
                    skip();
                }
            }
            return new Node(horus, foreign);
        }

        private void extensions(String element, String id, boolean inputs, List<HorusExtension> horus,
                List<ForeignFragment> foreign) throws XMLStreamException {
            Set<String> names = new HashSet<>();
            while (nextChild()) {
                String ns = xml.getNamespaceURI();
                if (HORUS.equals(ns) && "input".equals(xml.getLocalName()) && inputs) {
                    String inputName = attr("name");
                    String value = xml.getAttributeValue(null, "value");
                    if (inputName == null || value == null || !inputName.matches("^[A-Za-z_][A-Za-z0-9_]{0,63}$")) {
                        invalid.add(new DefinitionProblem("INPUT_INVALID",
                                "horus:input needs a name (identifier) and a value", element, safe(id), line()));
                    } else if (!names.add(inputName)) {
                        invalid.add(new DefinitionProblem("INPUT_DUPLICATE", "Duplicate horus:input " + inputName,
                                element, safe(id), line()));
                    } else {
                        horus.add(new InputMapping(inputName, value));
                    }
                    skip();
                } else if (HORUS.equals(ns)) {
                    unsupported("horus:" + xml.getLocalName(), id, "the next sprint");
                    skip();
                } else {
                    // Vendor extensions stay opaque and are never executed; the stored source keeps them.
                    foreign.add(new ForeignFragment(ns == null ? "" : ns, xml.getLocalName(), ""));
                    skip();
                }
            }
        }

        // ---- cursor helpers ---------------------------------------------------------------------------------

        private void nextStart() throws XMLStreamException {
            while (xml.hasNext()) {
                int event = next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    enter();
                    return;
                }
            }
            throw new ValidationFailedException(List.of(DefinitionProblem.of("XML_EMPTY", "The document is empty", null)));
        }

        private boolean nextChild() throws XMLStreamException {
            while (xml.hasNext()) {
                int event = next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    enter();
                    return true;
                }
                if (event == XMLStreamConstants.END_ELEMENT) {
                    depth--;
                    return false;
                }
            }
            return false;
        }

        /** Reads the text of the current element through the guarded loop; child elements are refused. */
        private String text() throws XMLStreamException {
            StringBuilder out = new StringBuilder();
            while (xml.hasNext()) {
                int event = next();
                switch (event) {
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
                        out.append(xml.getText());
                        if (out.length() > limits.maxTextChars()) {
                            throw new BpmnUnsafeXmlException("Text longer than " + limits.maxTextChars()
                                    + " characters at line " + line());
                        }
                    }
                    case XMLStreamConstants.END_ELEMENT -> {
                        depth--;
                        return out.toString();
                    }
                    case XMLStreamConstants.START_ELEMENT -> throw new ValidationFailedException(List.of(
                            new DefinitionProblem("XML_MALFORMED", "An expression may not contain elements", null, null,
                                    line())));
                    default -> { }
                }
            }
            return out.toString();
        }

        /**
         * Advances one event. The parser reports a start tag's location at its end, so the line where the tag starts
         * is taken from the end of the previous event.
         */
        private int next() throws XMLStreamException {
            int event = xml.next();
            guard(event);
            int current = xml.getLocation() == null ? 0 : xml.getLocation().getLineNumber();
            if (event == XMLStreamConstants.START_ELEMENT) {
                startLine = previousLine > 0 ? previousLine : current;
            }
            previousLine = current;
            return event;
        }

        private void skip() throws XMLStreamException {
            while (nextChild()) {
                skip();
            }
        }

        private void enter() {
            depth++;
            elements++;
            for (int i = 0; i < xml.getAttributeCount(); i++) {
                if (xml.getAttributeValue(i).length() > limits.maxAttributeChars()) {
                    throw new BpmnUnsafeXmlException("Attribute " + xml.getAttributeLocalName(i) + " longer than "
                            + limits.maxAttributeChars() + " characters at line " + line());
                }
            }
            if (depth > limits.maxDepth()) {
                throw new BpmnUnsafeXmlException("Element nesting deeper than " + limits.maxDepth() + " at line " + line());
            }
            if (elements > limits.maxElements()) {
                throw new BpmnUnsafeXmlException("More than " + limits.maxElements() + " elements");
            }
        }

        private void guard(int event) {
            if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE
                    || event == XMLStreamConstants.ENTITY_DECLARATION) {
                throw new BpmnUnsafeXmlException("DOCTYPE and entity declarations are not accepted (line " + line() + ")");
            }
        }

        private String id() {
            String id = attr("id");
            if (id == null) {
                invalid.add(new DefinitionProblem("ID_MISSING", "Every flow element needs an id", xml.getLocalName(),
                        null, line()));
            } else if (!ids.add(id)) {
                invalid.add(new DefinitionProblem("DUPLICATE_ID", "Duplicate id", xml.getLocalName(), safe(id), line()));
            }
            return id == null ? "_missing_" + elements : id;
        }

        private String attr(String name) {
            String value = xml.getAttributeValue(null, name);
            return value == null || value.isBlank() ? null : value.strip();
        }

        private String horus(String name) {
            String value = xml.getAttributeValue(HORUS, name);
            return value == null || value.isBlank() ? null : value.strip();
        }

        /** @return the line where the current element's start tag begins */
        private int line() {
            return startLine;
        }

        private void unsupported(String element, String id, String stage) {
            unsupported.add(new DefinitionProblem("BPMN_UNSUPPORTED_ELEMENT",
                    "is not supported in this release; planned for " + stage, element, safe(id), line()));
        }
    }

    /** An id echoed in a problem: at most 80 characters, control characters escaped. */
    static String safe(String id) {
        if (id == null) {
            return null;
        }
        String quoted = Quote.of(id);
        return quoted.substring(1, quoted.length() - 1);
    }

    private static ElementInfo info(List<HorusExtension> horus, List<ForeignFragment> foreign) {
        return new ElementInfo(List.of(), new ExtensionBag(horus, foreign), Map.of());
    }

    /** Fills {@code incoming} and {@code outgoing} of every node from the sequence flows. */
    private static Process withRefs(Process p) {
        List<SequenceFlow> flows = p.flowElements().stream().filter(SequenceFlow.class::isInstance)
                .map(SequenceFlow.class::cast).toList();
        List<FlowElement> out = new ArrayList<>();
        for (FlowElement e : p.flowElements()) {
            List<String> in = flows.stream().filter(f -> f.targetRef() != null && f.targetRef().equals(e.id()))
                    .map(SequenceFlow::id).toList();
            List<String> o = flows.stream().filter(f -> f.sourceRef() != null && f.sourceRef().equals(e.id()))
                    .map(SequenceFlow::id).toList();
            out.add(switch (e) {
                case StartEvent s -> new StartEvent(s.id(), s.name(), in, o, s.eventDefinitions(), s.eventDefinitionRefs(),
                        s.data(), s.parallelMultiple(), s.isInterrupting(), s.info());
                case EndEvent s -> new EndEvent(s.id(), s.name(), in, o, s.eventDefinitions(), s.eventDefinitionRefs(),
                        s.data(), s.info());
                case ServiceTask t -> new ServiceTask(t.id(), t.name(), in, o, t.activity(), t.implementation(),
                        t.operationRef(), t.info());
                case BusinessRuleTask t -> new BusinessRuleTask(t.id(), t.name(), in, o, t.activity(), t.implementation(),
                        t.info());
                case UserTask t -> new UserTask(t.id(), t.name(), in, o, t.activity(), t.implementation(), t.info());
                case ExclusiveGateway g -> new ExclusiveGateway(g.id(), g.name(), in, o, g.gatewayDirection(),
                        g.defaultFlow(), g.info());
                case IntermediateCatchEvent c -> new IntermediateCatchEvent(c.id(), c.name(), in, o, c.eventDefinitions(),
                        c.eventDefinitionRefs(), c.data(), c.parallelMultiple(), c.info());
                case ParallelGateway g -> new ParallelGateway(g.id(), g.name(), in, o, g.gatewayDirection(), g.info());
                default -> e;
            });
        }
        return new Process(p.id(), p.name(), p.processType(), p.isExecutable(), p.isClosed(), out, p.artifacts(),
                p.laneSets(), p.info());
    }
}
