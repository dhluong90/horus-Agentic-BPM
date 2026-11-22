package io.github.dhluong90.bpm.process.bpmn.subset;

import io.github.dhluong90.bpm.process.api.BpmnUnsafeXmlException;
import io.github.dhluong90.bpm.process.api.BpmnUnsupportedElementException;
import io.github.dhluong90.bpm.process.api.CmmnUnsupportedException;
import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.DefinitionTooLargeException;
import io.github.dhluong90.bpm.process.api.ValidationFailedException;
import io.github.dhluong90.bpm.process.bpmn.xml.BpmnNamespaces;
import io.github.dhluong90.bpm.process.bpmn.xml.Limits;
import io.github.dhluong90.bpm.process.bpmn.xml.XmlHardening;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ActivityAttributes;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Artifact;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Association;
import io.github.dhluong90.bpm.process.internal.model.bpmn.AssociationDirection;
import io.github.dhluong90.bpm.process.internal.model.bpmn.BusinessRuleTask;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ElementInfo;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EndEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ExclusiveGateway;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Expression;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ExtensionBag;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowElement;
import io.github.dhluong90.bpm.process.internal.model.bpmn.GatewayDirection;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Group;
import io.github.dhluong90.bpm.process.internal.model.bpmn.CompensateEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.IntermediateCatchEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.IntermediateThrowEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Transaction;
import io.github.dhluong90.bpm.process.internal.model.bpmn.TimerEventDefinition;
import io.github.dhluong90.bpm.process.internal.exec.behavior.event.TimerSchedule;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ParallelGateway;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ProcessType;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SequenceFlow;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ServiceTask;
import io.github.dhluong90.bpm.process.internal.model.bpmn.StartEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.TextAnnotation;
import io.github.dhluong90.bpm.process.internal.model.bpmn.UserTask;
import io.github.dhluong90.bpm.process.internal.model.foreign.ForeignFragment;
import io.github.dhluong90.bpm.process.internal.model.horus.CompensateHandlerRef;
import io.github.dhluong90.bpm.process.internal.model.horus.DecisionRef;
import io.github.dhluong90.bpm.process.internal.model.horus.OnFailure;
import io.github.dhluong90.bpm.process.internal.model.horus.HandlerRef;
import io.github.dhluong90.bpm.process.internal.model.horus.HorusExtension;
import io.github.dhluong90.bpm.process.internal.json.CanonicalJson;
import io.github.dhluong90.bpm.process.internal.json.EngineJson;
import io.github.dhluong90.bpm.process.internal.model.horus.AgentTaskSpec;
import io.github.dhluong90.bpm.process.internal.model.horus.GuardrailSpec;
import io.github.dhluong90.bpm.process.internal.model.horus.InputMapping;
import io.github.dhluong90.bpm.process.internal.model.horus.MemoryEntry;
import io.github.dhluong90.bpm.process.internal.model.horus.ResultVariable;
import io.github.dhluong90.bpm.process.internal.model.horus.RetryPolicy;
import io.github.dhluong90.bpm.process.internal.model.horus.EscalationSpec;
import io.github.dhluong90.bpm.process.internal.model.horus.SlaSpec;
import io.github.dhluong90.bpm.process.internal.model.horus.TaskAssignment;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads the executable BPMN subset of the first release into the semantic model: none {@code startEvent} and
 * {@code endEvent}, {@code serviceTask} ({@code horus:handler}, or {@code horus:type="agentTask"} with
 * {@code horus:agent}, {@code <horus:outputSchema>} and {@code <horus:guardrail>}), {@code businessRuleTask}
 * ({@code horus:ruleSet}),
 * {@code userTask} ({@code horus:assignee}), {@code exclusiveGateway} with {@code default}, {@code parallelGateway},
 * an intermediate timer catch event with {@code timeDuration} or {@code timeDate}, and
 * {@code sequenceFlow} with an optional {@code conditionExpression} in {@code urn:horus-bpm:expr:v1}. Inputs are
 * {@code horus:input} elements and the result variable is {@code horus:resultVariable}.
 *
 * <p>Every other BPMN element is listed with its id, line and the stage that adds it, in one
 * {@code BPMN_UNSUPPORTED_ELEMENT}. BPMNDI is skipped (layout is not behaviour). Foreign extensions are skipped and
 * never executed.
 *
 * <p>The artifacts that modellers add to a diagram, {@code textAnnotation}, {@code association} and {@code group}
 * (with the document-level {@code category} its {@code categoryValueRef} points to), are read into
 * {@link Process#artifacts()}. Like {@code documentation} they are never executed and are not part of the definition
 * hash; the stored source keeps them and the BPMN export writes them back. The group's category label is carried on
 * the group, see {@link #categoryValueLabel(Group)}.
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
    /** The longest SLA a user task may declare: a year of working time is already beyond any service level. */
    static final java.time.Duration MAX_SLA = java.time.Duration.ofDays(366);
    private static final int MAX_CALENDAR_KEY = 100;
    private static final Pattern ASSIGNEE = Pattern.compile("^(user|role|group):[A-Za-z0-9_.@-]{1,100}$");
    /** A {@code horus:compensate} value: a handler or adapter name, or {@code manual}. */
    private static final Pattern COMPENSATE_HANDLER = Pattern.compile("^[A-Za-z][A-Za-z0-9_.-]{0,99}$");
    private static final Pattern RULE_SET = Pattern.compile("^[a-z][a-z0-9]*([.-][a-z0-9]+)*$");
    /** The step id a {@code horus:memory} entry reads from: an id, never an expression or a variable path. */
    private static final Pattern MEMORY_STEP = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.-]{0,199}$");
    /** The name a {@code horus:memory} entry is passed under. */
    private static final Pattern MEMORY_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");
    /** A field path of a {@code horus:memory} entry: identifiers joined by dots, at most four levels. */
    private static final Pattern MEMORY_PATH = Pattern.compile(
            "^[A-Za-z_][A-Za-z0-9_]{0,63}(\\.[A-Za-z_][A-Za-z0-9_]{0,63}){0,3}$");
    /** Most fields one {@code horus:memory} entry lists. */
    static final int MAX_MEMORY_FIELDS = 32;

    /** The {@code horus:type} of an agent step. */
    static final String AGENT_TASK = "agentTask";
    private static final Set<String> IGNORED_CHILDREN = Set.of("incoming", "outgoing", "documentation");
    /** Where a group carries the label of the category value it refers to; the process model has no root elements. */
    static final QName CATEGORY_VALUE_LABEL = new QName(BpmnNamespaces.BPMN, "categoryValue");
    /** Where a group carries the id of the category that holds its category value. */
    static final QName CATEGORY_ID = new QName(BpmnNamespaces.BPMN, "category");
    /** Every CMMN version's model namespace starts with this. */
    private static final String CMMN_NAMESPACE_PREFIX = "http://www.omg.org/spec/CMMN/";
    /** Namespaces whose attributes are part of BPMN or its diagram interchange. */
    private static final Set<String> KNOWN_ATTRIBUTE_NAMESPACES = Set.of(BpmnNamespaces.BPMN, BpmnNamespaces.HORUS,
            BpmnNamespaces.XSI, BpmnNamespaces.BPMNDI, BpmnNamespaces.DC, BpmnNamespaces.DI,
            "http://www.w3.org/XML/1998/namespace");
    private static final int MAX_WARNINGS = 100;

    private final Limits limits;

    /** @param limits size, depth and element limits */
    public SubsetBpmnReader(Limits limits) {
        this.limits = limits;
    }

    /**
     * @param group a group read by this reader
     * @return the label of the category value the group refers to, or {@code null} when the document has none
     */
    public static String categoryValueLabel(Group group) {
        return group.info().otherAttributes().get(CATEGORY_VALUE_LABEL);
    }

    /**
     * @param group a group read by this reader
     * @return the id of the category that holds the group's category value, or {@code null} when the document has none
     */
    public static String categoryId(Group group) {
        return group.info().otherAttributes().get(CATEGORY_ID);
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
        private boolean unsupportedTruncated;
        private final List<DefinitionProblem> warnings = new ArrayList<>();
        private final List<DefinitionProblem> invalid = new ArrayList<>();
        private final Set<String> ids = new HashSet<>();
        private int depth;
        private int elements;
        private int previousLine;
        private int startLine;
        private String documentLanguage;
        private final Set<String> sharedDefinitionIds = new HashSet<>();
        private final List<DefinitionProblem> unresolvedRefs = new ArrayList<>();
        /** Category value id to its label and its category id, from the document-level categories. */
        private final Map<String, String[]> categoryValues = new HashMap<>();

        Pass(XMLStreamReader xml) {
            this.xml = xml;
        }

        Process document() throws XMLStreamException {
            nextStart();
            // CMMN is refused at the root, before any other check (PRE-107): the XML hardening has already run.
            if (xml.getNamespaceURI() != null && xml.getNamespaceURI().startsWith(CMMN_NAMESPACE_PREFIX)) {
                throw new CmmnUnsupportedException(safe(xml.getNamespaceURI()), line());
            }
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
                        unsupported(local, attr("id"), BpmnStages.S1);
                        skip();
                    } else {
                        process = process();
                    }
                } else if (BPMN.equals(ns) && "category".equals(local)) {
                    category();
                } else if (BPMN.equals(ns)) {
                    if (local.endsWith("EventDefinition") && attr("id") != null) {
                        sharedDefinitionIds.add(attr("id"));
                    }
                    unsupported(local, attr("id"), BpmnStages.of(local));
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
                throw new BpmnUnsupportedElementException(unsupported, warnings, unsupportedTruncated);
            }
            if (process == null) {
                invalid.add(DefinitionProblem.of("NO_PROCESS", "The document has no process", null));
            }
            if (!invalid.isEmpty()) {
                throw new ValidationFailedException(invalid);
            }
            return withCategoryLabels(process);
        }

        /** A document-level category: only its values are kept, as labels of the groups that refer to them. */
        private void category() throws XMLStreamException {
            String categoryId = id();
            while (nextChild()) {
                String local = xml.getLocalName();
                if (BPMN.equals(xml.getNamespaceURI()) && "categoryValue".equals(local)) {
                    String valueId = id();
                    categoryValues.put(valueId, new String[] {xml.getAttributeValue(null, "value"), categoryId});
                    children("categoryValue", valueId, false);
                } else if (BPMN.equals(xml.getNamespaceURI()) && !"documentation".equals(local)
                        && !"extensionElements".equals(local)) {
                    unsupported("category child " + local, categoryId, BpmnStages.of(local));
                    skip();
                } else {
                    skip();
                }
            }
        }

        private Process withCategoryLabels(Process p) {
            if (categoryValues.isEmpty()) {
                return p;
            }
            List<Artifact> artifacts = p.artifacts().stream().map(a -> {
                if (a instanceof Group g && g.categoryValueRef() != null && categoryValues.containsKey(g.categoryValueRef())) {
                    String[] value = categoryValues.get(g.categoryValueRef());
                    Map<QName, String> labels = new HashMap<>();
                    if (value[0] != null) {
                        labels.put(CATEGORY_VALUE_LABEL, value[0]);
                    }
                    labels.put(CATEGORY_ID, value[1]);
                    return (Artifact) new Group(g.id(), g.categoryValueRef(), new ElementInfo(List.of(),
                            g.info().extensions(), labels));
                }
                return a;
            }).toList();
            return new Process(p.id(), p.name(), p.processType(), p.isExecutable(), p.isClosed(), p.flowElements(),
                    artifacts, p.laneSets(), p.info());
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
            List<Artifact> artifacts = new ArrayList<>();
            container(elements, artifacts, false);
            return withRefs(new Process(key, name, ProcessType.NONE, true, false, elements, artifacts, List.of(), null));
        }


        /**
         * The flow elements and artifacts of a process or of a {@code saga} block ({@code transaction}), read into
         * the given lists.
         *
         * @param inSaga whether the container is a {@code saga}, where {@code throwCompensation} may stand
         */
        private void container(List<FlowElement> elements, List<Artifact> artifacts, boolean inSaga)
                throws XMLStreamException {
            while (nextChild()) {
                String local = xml.getLocalName();
                if (!BPMN.equals(xml.getNamespaceURI())) {
                    skip();
                    continue;
                }
                behaviourAttributes();
                switch (local) {
                    case "startEvent" -> elements.add(event(true));
                    case "endEvent" -> elements.add(event(false));
                    case "serviceTask" -> elements.add(serviceTask());
                    case "businessRuleTask" -> elements.add(businessRuleTask());
                    case "userTask" -> elements.add(userTask());
                    case "intermediateCatchEvent" -> elements.add(catchEvent());
                    case "intermediateThrowEvent" -> elements.add(throwEvent(inSaga));
                    case "transaction" -> elements.add(saga());
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
                    case "textAnnotation" -> artifacts.add(textAnnotation());
                    case "association" -> artifacts.add(association());
                    case "group" -> {
                        String id = id();
                        String ref = attr("categoryValueRef");
                        children(local, id, false);
                        artifacts.add(new Group(id, ref, null));
                    }
                    case "documentation", "extensionElements" -> skip();
                    default -> {
                        unsupported(local, attr("id"), BpmnStages.of(local));
                        skip();
                    }
                }
            }
        }

        /**
         * A {@code saga} block (#67, ADR-0007): a BPMN {@code transaction} whose steps run under their own scope
         * token. It has a none start event, steps and at least one end event of its own; its {@code incoming} and
         * {@code outgoing} flows are in the enclosing container. {@code method} other than the default is refused.
         */
        private FlowElement saga() throws XMLStreamException {
            String id = id();
            String name = attr("name");
            String method = attr("method");
            if (method != null && !"##Compensate".equals(method)) {
                unsupported("transaction method", id, BpmnStages.NOT_PLANNED, "is not supported; a saga is undone by"
                        + " compensation (method ##Compensate)");
            }
            List<FlowElement> elements = new ArrayList<>();
            List<Artifact> artifacts = new ArrayList<>();
            container(elements, artifacts, true);
            return new Transaction(id, name, List.of(), List.of(), ActivityAttributes.defaults(), null,
                    withRefs(elements), artifacts, List.of(), null);
        }

        /**
         * {@code throwCompensation} (#67, PRE-076): an {@code intermediateThrowEvent} with one
         * {@code compensateEventDefinition} and no {@code activityRef}, inside a {@code saga} block. It undoes the
         * completed steps of its {@code saga} newest first. Every other throw event is named as unsupported.
         */
        private FlowElement throwEvent(boolean inSaga) throws XMLStreamException {
            String element = "intermediateThrowEvent";
            String id = id();
            String name = attr("name");
            int line = line();
            CompensateEventDefinition compensate = null;
            int definitions = 0;
            while (nextChild()) {
                String local = xml.getLocalName();
                if (BPMN.equals(xml.getNamespaceURI()) && "compensateEventDefinition".equals(local)) {
                    definitions++;
                    if (attr("activityRef") != null) {
                        unsupported(element + " with compensateEventDefinition activityRef", id, BpmnStages.S2);
                    }
                    compensate = new CompensateEventDefinition(attr("id"), null, true, null);
                    children(local, id, false);
                } else if (BPMN.equals(xml.getNamespaceURI()) && local.endsWith("EventDefinition")) {
                    definitions++;
                    unsupported(element + " with " + local, id, BpmnStages.of(local));
                    skip();
                } else if (BPMN.equals(xml.getNamespaceURI()) && !IGNORED_CHILDREN.contains(local)
                        && !"extensionElements".equals(local)) {
                    unsupported(element + " child " + local, id, BpmnStages.of(local));
                    skip();
                } else {
                    skip();
                }
            }
            if (definitions == 0) {
                unsupported(element, id, BpmnStages.S0, BpmnStages.message(BpmnStages.S0));
            } else if (definitions > 1) {
                unsupported(element, id, BpmnStages.S3, "has several event definitions; multiple events are not supported");
            } else if (compensate != null && !inSaga) {
                invalid.add(new DefinitionProblem("THROW_COMPENSATION_OUTSIDE_SAGA", "throwCompensation undoes the"
                        + " steps of its saga block; put it inside a transaction", element, safe(id), line));
            }
            return new IntermediateThrowEvent(id, name, List.of(), List.of(),
                    compensate == null ? List.of() : List.of(compensate), List.of(), null, null);
        }

        /**
         * {@code horus:compensate} (a handler name, or {@code manual}) and {@code horus:onFailure}
         * ({@code compensate}) of a service or agent step (#67).
         */
        private void compensation(String id, int line, List<HorusExtension> ext) {
            String compensate = horus("compensate");
            if (compensate != null) {
                if (!COMPENSATE_HANDLER.matcher(compensate).matches()) {
                    invalid.add(new DefinitionProblem("COMPENSATE_INVALID", "horus:compensate names a registered"
                            + " handler or adapter, or is manual", "serviceTask", safe(id), line));
                }
                ext.add(new CompensateHandlerRef(compensate));
            }
            String onFailure = horus("onFailure");
            if (onFailure != null) {
                if (!OnFailure.COMPENSATE.equals(onFailure)) {
                    invalid.add(new DefinitionProblem("ON_FAILURE_INVALID", "horus:onFailure is compensate",
                            "serviceTask", safe(id), line));
                }
                ext.add(new OnFailure(onFailure));
            }
        }

        /**
         * Names the attributes of the current element that change how it runs and that this release does not honour:
         * a compensation activity, a quantity other than one, a non-interrupting start, a parallel multiple event or
         * an implementation other than a registered handler. Ignoring them would run the element differently from
         * what the model says.
         */
        private void behaviourAttributes() {
            String id = attr("id");
            if ("true".equals(attr("isForCompensation"))) {
                unsupported("isForCompensation", id, BpmnStages.S2);
            }
            for (String quantity : List.of("startQuantity", "completionQuantity")) {
                String value = attr(quantity);
                if (value != null && !"1".equals(value)) {
                    unsupported(quantity, id, BpmnStages.NOT_PLANNED);
                }
            }
            if ("false".equals(attr("isInterrupting"))) {
                unsupported("isInterrupting", id, BpmnStages.S1);
            }
            if ("true".equals(attr("parallelMultiple"))) {
                unsupported("parallelMultiple", id, BpmnStages.S3);
            }
            String implementation = attr("implementation");
            if (implementation != null && !"##unspecified".equals(implementation)) {
                unsupported("implementation", id, BpmnStages.NOT_PLANNED,
                        "is not supported; bind the step with horus:handler instead of a service implementation");
            }
        }

        /** A note on the diagram: its {@code text} is kept exactly, nothing is executed. */
        private Artifact textAnnotation() throws XMLStreamException {
            String id = id();
            String format = attr("textFormat");
            String text = null;
            while (nextChild()) {
                String local = xml.getLocalName();
                if (BPMN.equals(xml.getNamespaceURI()) && "text".equals(local)) {
                    text = text();
                } else if (BPMN.equals(xml.getNamespaceURI()) && !"documentation".equals(local)
                        && !"extensionElements".equals(local)) {
                    unsupported("textAnnotation child " + local, id, BpmnStages.of(local));
                    skip();
                } else {
                    skip();
                }
            }
            return new TextAnnotation(id, text, format, null);
        }

        /** A line from an element to a note or another artifact; it carries no behaviour. */
        private Artifact association() throws XMLStreamException {
            String id = id();
            int line = line();
            String direction = attr("associationDirection");
            AssociationDirection parsed = switch (direction == null ? "None" : direction) {
                case "None" -> AssociationDirection.NONE;
                case "One" -> AssociationDirection.ONE;
                case "Both" -> AssociationDirection.BOTH;
                default -> {
                    invalid.add(new DefinitionProblem("ASSOCIATION_DIRECTION_INVALID",
                            "associationDirection must be None, One or Both", "association", safe(id), line));
                    yield AssociationDirection.NONE;
                }
            };
            Association association = new Association(id, attr("sourceRef"), attr("targetRef"), parsed, null);
            children("association", id, false);
            return association;
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
                    unsupported(element + " with " + local, id, BpmnStages.of(local));
                    skip();
                } else if (BPMN.equals(xml.getNamespaceURI()) && !IGNORED_CHILDREN.contains(local)
                        && !"extensionElements".equals(local)) {
                    unsupported(element + " child " + local, id, BpmnStages.of(local));
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
                    unsupported(element + " with " + local, id, BpmnStages.of(local));
                    skip();
                } else if (BPMN.equals(xml.getNamespaceURI()) && "eventDefinitionRef".equals(local)) {
                    definitions++;
                    unsupported(element + " with eventDefinitionRef", id, BpmnStages.S1);
                    skip();
                } else if (BPMN.equals(xml.getNamespaceURI()) && !IGNORED_CHILDREN.contains(local)
                        && !"extensionElements".equals(local)) {
                    unsupported(element + " child " + local, id, BpmnStages.of(local));
                    skip();
                } else {
                    skip();
                }
            }
            if (definitions == 0) {
                unsupported(element, id, BpmnStages.S1, "has no event definition; link and none catch events are not supported");
            } else if (definitions > 1) {
                unsupported(element, id, BpmnStages.S3, "has several event definitions; multiple events are not supported");
            }
            if (timer != null && definitions == 1) {
                TimerSchedule.problem(timer).ifPresent(problem -> {
                    if (problem.startsWith("timeCycle")) {
                        addProblem(new DefinitionProblem("BPMN_UNSUPPORTED_ELEMENT",
                                "is not supported in this release; stage S1 adds it", "timeCycle",
                                safe(id), line, BpmnStages.S1));
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
                        unsupported("timerEventDefinition child " + local, eventId, BpmnStages.of(local));
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
            if (AGENT_TASK.equals(type)) {
                return agentTask(id, line, name, result, handler, mode);
            }
            if (type != null) {
                unsupported("horus:type", id, BpmnStages.NOT_PLANNED, "\"" + safe(type) + "\" is not a service task type; only agentTask is");
            }
            String timeoutText = horus("timeout");
            if (mode != null && !"local".equals(mode) && !"remote".equals(mode)) {
                invalid.add(new DefinitionProblem("MODE_INVALID", "horus:mode is local or remote", "serviceTask",
                        safe(id), line));
            }
            java.time.Duration timeout = null;
            if (timeoutText != null) {
                if (!"remote".equals(mode)) {
                    invalid.add(new DefinitionProblem("TIMEOUT_INVALID", "horus:timeout applies to horus:mode=\"remote\""
                            + " only", "serviceTask", safe(id), line));
                } else {
                    try {
                        timeout = java.time.Duration.parse(timeoutText.strip());
                    } catch (java.time.format.DateTimeParseException e) {
                        timeout = null;
                    }
                    if (timeout == null || timeout.isNegative() || timeout.isZero()) {
                        invalid.add(new DefinitionProblem("TIMEOUT_INVALID", "horus:timeout must be a positive ISO-8601"
                                + " duration, for example PT30S", "serviceTask", safe(id), line));
                        timeout = null;
                    }
                }
            }
            if (handler == null) {
                invalid.add(new DefinitionProblem("HANDLER_MISSING", "A serviceTask needs horus:handler", "serviceTask",
                        id, line));
            }
            List<HorusExtension> ext = new ArrayList<>();
            ext.add(new HandlerRef(handler, mode, timeout));
            if (result != null) {
                ext.add(new ResultVariable(result));
            }
            compensation(id, line, ext);
            Node children = children("serviceTask", id, true);
            ext.addAll(children.horus());
            return new ServiceTask(id, name, List.of(), List.of(), ActivityAttributes.defaults(), null, null,
                    info(ext, children.foreign()));
        }

        /**
         * A {@code serviceTask} with {@code horus:type="agentTask"} (#52): the agent adapter, an optional timeout, the
         * bounded inputs, the output schema as a JSON object and the guardrail. The guard spec is checked here, so an
         * invalid one never becomes a draft; each fault is one {@code GUARD_SPEC_INVALID} problem naming the step.
         */
        private FlowElement agentTask(String id, int line, String name, String result, String handler, String mode)
                throws XMLStreamException {
            String agent = horus("agent");
            String timeoutText = horus("timeout");
            // Read while the reader is on the start tag; added after the agent parts (#67).
            List<HorusExtension> undo = new ArrayList<>();
            compensation(id, line, undo);
            if (handler != null || mode != null) {
                invalid.add(new DefinitionProblem("AGENT_TASK_INVALID", "An agentTask names its adapter with "
                        + "horus:agent; horus:handler and horus:mode do not apply", "serviceTask", safe(id), line));
            }
            if (agent == null) {
                invalid.add(new DefinitionProblem("AGENT_MISSING", "An agentTask needs horus:agent", "serviceTask",
                        safe(id), line));
            }
            java.time.Duration timeout = timeoutText == null ? null : positiveDuration(timeoutText);
            if (timeoutText != null && timeout == null) {
                invalid.add(new DefinitionProblem("TIMEOUT_INVALID", "horus:timeout must be a positive ISO-8601"
                        + " duration, for example PT30S", "serviceTask", safe(id), line));
            }
            AgentParts parts = new AgentParts();
            List<HorusExtension> horusParts = new ArrayList<>();
            List<ForeignFragment> foreign = new ArrayList<>();
            while (nextChild()) {
                String ns = xml.getNamespaceURI();
                String local = xml.getLocalName();
                if (BPMN.equals(ns) && IGNORED_CHILDREN.contains(local)) {
                    skip();
                } else if (BPMN.equals(ns) && "extensionElements".equals(local)) {
                    extensions("serviceTask", id, true, horusParts, foreign, parts);
                } else if (BPMN.equals(ns)) {
                    unsupported("serviceTask child " + local, id, BpmnStages.of(local));
                    skip();
                } else {
                    skip();
                }
            }
            String schema = parts.schema(id, line);
            GuardrailSpec guardrail = parts.guardrail(id, line);
            RetryPolicy retry = parts.retry(id);
            List<MemoryEntry> memory = parts.memory(id);
            List<HorusExtension> ext = new ArrayList<>();
            ext.add(new AgentTaskSpec(agent, schema, timeout));
            if (guardrail != null) {
                ext.add(guardrail);
            }
            if (retry != null) {
                ext.add(retry);
            }
            ext.addAll(memory);
            if (result != null) {
                ext.add(new ResultVariable(result));
            }
            ext.addAll(undo);
            ext.addAll(horusParts);
            return new ServiceTask(id, name, List.of(), List.of(), ActivityAttributes.defaults(), null, null,
                    info(ext, foreign));
        }

        /** The agent parts of one step's {@code extensionElements}, checked once the step has been read. */
        private final class AgentParts {
            private String schemaText;
            private int schemaCount;
            private Map<String, String> guardrail;
            private int guardrailCount;
            private int guardrailLine;

            void schemaText(String text) {
                schemaCount++;
                schemaText = text;
            }

            private String retryMax;
            private String retryBackoff;
            private int retryCount;
            private int retryLine;

            void retry(String max, String backoff, int at) {
                retryCount++;
                retryMax = max;
                retryBackoff = backoff;
                retryLine = at;
            }

            /**
             * {@code <horus:retry max="2" backoff="PT1S"/>} (#58): retries after the first attempt, 0 to 20, and the
             * delay before each retry, a positive ISO-8601 duration; optional.
             *
             * @return the policy, or null when the step declares none or it is invalid
             */
            RetryPolicy retry(String id) {
                if (retryCount == 0) {
                    return null;
                }
                int at = retryLine;
                if (retryCount > 1) {
                    retryProblem("An agentTask has one <horus:retry>", id, at);
                    return null;
                }
                Integer max = null;
                if (retryMax != null && retryMax.strip().matches("^[0-9]{1,2}$")) {
                    max = Integer.parseInt(retryMax.strip());
                }
                if (max == null || max > RetryPolicy.MAX_RETRIES) {
                    retryProblem("horus:retry needs max, the retries after the first attempt, 0 to "
                            + RetryPolicy.MAX_RETRIES, id, at);
                    return null;
                }
                java.time.Duration backoff = retryBackoff == null ? null : positiveDuration(retryBackoff);
                if (retryBackoff != null && backoff == null) {
                    retryProblem("horus:retry backoff must be a positive ISO-8601 duration, for example PT1S", id, at);
                    return null;
                }
                return new RetryPolicy(max, backoff);
            }

            private final List<Map<String, String>> memoryEntries = new ArrayList<>();
            private final List<Integer> memoryLines = new ArrayList<>();

            void memory(Map<String, String> attributes, int at) {
                memoryEntries.add(attributes);
                memoryLines.add(at);
            }

            /**
             * {@code <horus:memory from="extract-id" fields="fullName,dob" as="applicant" required="true"
             * includeMasked="false"/>} (#101, PRE-101, PRE-102): the shape of each entry. Whether {@code from} is an
             * upstream agent step, whether its output schema declares the fields, which of them are masked and how
             * many entries the host allows are checked by the engine, which sees the whole process and the host's
             * settings. Each fault is one {@code AGENT_MEMORY_INVALID} problem naming the step and the entry.
             *
             * @return the valid entries, in document order
             */
            List<MemoryEntry> memory(String id) {
                List<MemoryEntry> entries = new ArrayList<>();
                Set<String> names = new HashSet<>();
                for (int i = 0; i < memoryEntries.size(); i++) {
                    Map<String, String> a = memoryEntries.get(i);
                    int at = memoryLines.get(i);
                    String entry = "horus:memory entry " + (i + 1);
                    boolean ok = true;
                    String from = a.get("from");
                    if (from == null || !MEMORY_STEP.matcher(from).matches()) {
                        memoryProblem(entry + " needs from, the id of an earlier step of this process; memory reads "
                                + "only the recorded results of steps in the same instance", id, at);
                        ok = false;
                    }
                    String as = a.get("as");
                    if (as == null || !MEMORY_NAME.matcher(as).matches()) {
                        memoryProblem(entry + " needs as, the name the fields are passed under (an identifier)", id,
                                at);
                        ok = false;
                    } else if (!names.add(as)) {
                        memoryProblem(entry + " repeats as " + as, id, at);
                        ok = false;
                    }
                    List<String> fields = new ArrayList<>();
                    String list = a.get("fields");
                    if (list == null || list.isBlank()) {
                        memoryProblem(entry + " needs fields, a comma-separated list of field paths", id, at);
                        ok = false;
                    } else {
                        for (String raw : list.split(",", -1)) {
                            String field = raw.strip();
                            if (field.contains("*")) {
                                memoryProblem(entry + " names a wildcard; wildcards are never allowed, list each field",
                                        id, at);
                                ok = false;
                            } else if (!MEMORY_PATH.matcher(field).matches()) {
                                memoryProblem(entry + " has a field that is not a path of identifiers (a.b, at most "
                                        + "4 levels)", id, at);
                                ok = false;
                            } else if (fields.contains(field)) {
                                memoryProblem(entry + " repeats field " + field, id, at);
                                ok = false;
                            } else {
                                fields.add(field);
                            }
                        }
                        if (fields.size() > MAX_MEMORY_FIELDS) {
                            memoryProblem(entry + " lists more than " + MAX_MEMORY_FIELDS + " fields", id, at);
                            ok = false;
                        }
                    }
                    Boolean required = flag(a.get("required"), true);
                    Boolean includeMasked = flag(a.get("includeMasked"), false);
                    if (required == null || includeMasked == null) {
                        memoryProblem(entry + ": required and includeMasked are true or false", id, at);
                        ok = false;
                    }
                    if (ok) {
                        entries.add(new MemoryEntry(from, fields, as, required, includeMasked));
                    }
                }
                return entries;
            }

            private static Boolean flag(String text, boolean absent) {
                if (text == null) {
                    return absent;
                }
                return "true".equals(text) ? Boolean.TRUE : "false".equals(text) ? Boolean.FALSE : null;
            }

            private void memoryProblem(String message, String id, int at) {
                invalid.add(new DefinitionProblem("AGENT_MEMORY_INVALID", message, "serviceTask", safe(id), at));
            }

            private void retryProblem(String message, String id, int at) {
                invalid.add(new DefinitionProblem("RETRY_INVALID", message, "serviceTask", safe(id), at));
            }

            void guardrail(Map<String, String> attributes, int at) {
                guardrailCount++;
                guardrail = attributes;
                guardrailLine = at;
            }

            /** @return the schema as canonical JSON text, or null when it is missing or not a JSON object */
            String schema(String id, int line) {
                if (schemaCount == 0 || schemaText == null || schemaText.isBlank()) {
                    problem("An agentTask needs a <horus:outputSchema> element holding a JSON object", id, line);
                    return null;
                }
                if (schemaCount > 1) {
                    problem("An agentTask has one <horus:outputSchema>", id, line);
                }
                tools.jackson.databind.JsonNode node;
                try {
                    node = EngineJson.JSON.readTree(schemaText.strip());
                } catch (RuntimeException e) {
                    // Never echo the text: the problem names the step and the rule only.
                    problem("horus:outputSchema is not valid JSON", id, line);
                    return null;
                }
                if (node == null || !node.isObject()) {
                    problem("horus:outputSchema must be a JSON object", id, line);
                    return null;
                }
                return CanonicalJson.write(node);
            }

            /** @return the guardrail, or null when it is missing or invalid */
            GuardrailSpec guardrail(String id, int line) {
                if (guardrailCount == 0) {
                    problem("An agentTask needs a <horus:guardrail decision=\"...\"> element", id, line);
                    return null;
                }
                int at = guardrailLine;
                if (guardrailCount > 1) {
                    problem("An agentTask has one <horus:guardrail>", id, at);
                }
                boolean ok = true;
                String decision = guardrail.get("decision");
                if (decision == null || !RULE_SET.matcher(decision).matches()) {
                    problem("horus:guardrail needs decision, the guardrail rule set's name (kebab-case)", id, at);
                    ok = false;
                }
                String onViolation = guardrail.getOrDefault("onViolation", GuardrailSpec.ROUTE_TO_HUMAN);
                String reviewer = guardrail.get("reviewer");
                if (!GuardrailSpec.ROUTE_TO_HUMAN.equals(onViolation) && !GuardrailSpec.FAIL.equals(onViolation)) {
                    problem("horus:guardrail onViolation is route-to-human or fail", id, at);
                    ok = false;
                } else if (GuardrailSpec.ROUTE_TO_HUMAN.equals(onViolation)
                        && (reviewer == null || !ASSIGNEE.matcher(reviewer).matches())) {
                    problem("horus:guardrail with onViolation route-to-human needs reviewer as user:<id>, role:<role>"
                            + " or group:<group>", id, at);
                    ok = false;
                } else if (GuardrailSpec.FAIL.equals(onViolation) && reviewer != null) {
                    problem("horus:guardrail reviewer applies to onViolation route-to-human only", id, at);
                    ok = false;
                }
                String fourEyes = guardrail.get("fourEyes");
                if (fourEyes != null && !"true".equals(fourEyes)) {
                    problem("horus:guardrail fourEyes cannot be switched off: a review is always four-eyes", id, at);
                    ok = false;
                }
                return ok ? new GuardrailSpec(decision, onViolation, reviewer) : null;
            }

            private void problem(String message, String id, int at) {
                invalid.add(new DefinitionProblem("GUARD_SPEC_INVALID", message, "serviceTask", safe(id), at));
            }
        }

        /**
         * {@code horus:sla} (a positive ISO 8601 duration of working time, at most {@link #MAX_SLA}) and
         * {@code horus:calendar} (a calendar key) of a user task (PRE-008). A calendar without an SLA is refused,
         * because it would set nothing.
         */
        private java.util.Optional<SlaSpec> sla(String id, int line) {
            String text = horus("sla");
            String calendar = horus("calendar");
            if (text == null) {
                if (calendar != null) {
                    invalid.add(new DefinitionProblem("SLA_INVALID", "horus:calendar needs horus:sla, the working "
                            + "time until the task is due", "userTask", safe(id), line));
                }
                return java.util.Optional.empty();
            }
            java.time.Duration duration = positiveDuration(text);
            boolean ok = true;
            if (duration == null || duration.compareTo(MAX_SLA) > 0) {
                invalid.add(new DefinitionProblem("SLA_INVALID", "horus:sla is a positive ISO 8601 duration of "
                        + "working time up to " + MAX_SLA + ", for example PT4H", "userTask", safe(id), line));
                ok = false;
            }
            if (calendar != null && (calendar.length() > MAX_CALENDAR_KEY || !KEY.matcher(calendar).matches())) {
                invalid.add(new DefinitionProblem("SLA_INVALID", "horus:calendar is a calendar key: lower-case "
                        + "words joined by hyphens, at most " + MAX_CALENDAR_KEY + " characters", "userTask",
                        safe(id), line));
                ok = false;
            }
            return ok ? java.util.Optional.of(new SlaSpec(duration, calendar, null)) : java.util.Optional.empty();
        }

        /**
         * {@code horus:escalation}: one to {@link EscalationSpec#MAX_LEVELS} levels, each {@code <target>} or
         * {@code <target>|<duration>}, separated by semicolons, and {@code horus:onClaimed} ({@code release}, the
         * default, or {@code keep}). A level targets {@code user:}, {@code role:} or {@code group:}, never the
         * assignment it replaces (PRE-179). The task needs {@code horus:sla}: the first level fires at its due time.
         */
        private java.util.Optional<EscalationSpec> escalation(String id, int line, String assignee, boolean hasSla) {
            String text = horus("escalation");
            String onClaimed = horus("onClaimed");
            if (text == null) {
                if (onClaimed != null) {
                    escalationProblem("horus:onClaimed needs horus:escalation", id, line);
                }
                return java.util.Optional.empty();
            }
            boolean ok = true;
            if (!hasSla) {
                escalationProblem("horus:escalation needs horus:sla: the first level fires at the due time", id, line);
                ok = false;
            }
            if (onClaimed != null && !"release".equals(onClaimed) && !"keep".equals(onClaimed)) {
                escalationProblem("horus:onClaimed is release or keep", id, line);
                ok = false;
            }
            String[] parts = text.split(";", -1);
            if (parts.length > EscalationSpec.MAX_LEVELS) {
                escalationProblem("A task declares at most " + EscalationSpec.MAX_LEVELS + " escalation levels", id,
                        line);
                return java.util.Optional.empty();
            }
            java.util.List<EscalationSpec.Level> levels = new ArrayList<>();
            String current = assignee;
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i].strip();
                int split = part.indexOf('|');
                String target = split < 0 ? part : part.substring(0, split).strip();
                java.time.Duration after = java.time.Duration.ZERO;
                if (split >= 0) {
                    try {
                        after = java.time.Duration.parse(part.substring(split + 1).strip());
                    } catch (java.time.format.DateTimeParseException e) {
                        after = null;
                    }
                }
                if (!ASSIGNEE.matcher(target).matches()) {
                    escalationProblem("Level " + (i + 1) + " of horus:escalation is user:<id>, role:<role> or "
                            + "group:<group>, optionally followed by |<duration>", id, line);
                    ok = false;
                } else if (after == null || after.isNegative() || after.compareTo(MAX_SLA) > 0
                        || (i > 0 && after.isZero())) {
                    escalationProblem("Level " + (i + 1) + " of horus:escalation waits a positive ISO 8601 duration"
                            + " of working time up to " + MAX_SLA + " (the first level may wait PT0S)", id, line);
                    ok = false;
                } else if (target.equals(current)) {
                    escalationProblem("Level " + (i + 1) + " of horus:escalation is the assignment it replaces", id,
                            line);
                    ok = false;
                } else {
                    levels.add(new EscalationSpec.Level(after, target));
                    current = target;
                }
            }
            return ok ? java.util.Optional.of(new EscalationSpec(levels, !"keep".equals(onClaimed)))
                    : java.util.Optional.empty();
        }

        private void escalationProblem(String message, String id, int line) {
            invalid.add(new DefinitionProblem("ESCALATION_INVALID", message, "userTask", safe(id), line));
        }

        private java.time.Duration positiveDuration(String text) {
            try {
                java.time.Duration value = java.time.Duration.parse(text.strip());
                return value.isNegative() || value.isZero() ? null : value;
            } catch (java.time.format.DateTimeParseException e) {
                return null;
            }
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
            java.util.Optional<SlaSpec> sla = sla(id, line);
            sla.ifPresent(ext::add);
            escalation(id, line, assignee, sla.isPresent()).ifPresent(ext::add);
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
                        // FEEL is stage S1; XPath, JavaScript, Groovy and the rest are deviations (principle 4).
                        String stage = BpmnNamespaces.FEEL.equals(language) ? BpmnStages.S1 : BpmnStages.NOT_PLANNED;
                        addProblem(new DefinitionProblem("BPMN_EXPRESSION_LANGUAGE_UNSUPPORTED", "language "
                                + safe(language) + " is not supported; use " + BpmnNamespaces.EXPR_V1,
                                "conditionExpression", safe(id), line, stage));
                    }
                    condition = new Expression(text.strip(), language == null ? BpmnNamespaces.EXPR_V1 : language, null);
                } else if (BPMN.equals(xml.getNamespaceURI()) && !"documentation".equals(local)
                        && !"extensionElements".equals(local)) {
                    unsupported("sequenceFlow child " + local, id, BpmnStages.of(local));
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
                    unsupported(element + " child " + local, id, BpmnStages.of(local));
                    skip();
                } else {
                    skip();
                }
            }
            return new Node(horus, foreign);
        }

        private void extensions(String element, String id, boolean inputs, List<HorusExtension> horus,
                List<ForeignFragment> foreign) throws XMLStreamException {
            extensions(element, id, inputs, horus, foreign, null);
        }

        private void extensions(String element, String id, boolean inputs, List<HorusExtension> horus,
                List<ForeignFragment> foreign, AgentParts agent) throws XMLStreamException {
            Set<String> names = new HashSet<>();
            while (nextChild()) {
                String ns = xml.getNamespaceURI();
                if (agent != null && HORUS.equals(ns) && "outputSchema".equals(xml.getLocalName())) {
                    agent.schemaText(text());
                    continue;
                }
                if (agent != null && HORUS.equals(ns) && "guardrail".equals(xml.getLocalName())) {
                    Map<String, String> attributes = new HashMap<>();
                    for (String a : List.of("decision", "onViolation", "reviewer", "fourEyes")) {
                        if (attr(a) != null) {
                            attributes.put(a, attr(a));
                        }
                    }
                    agent.guardrail(attributes, line());
                    skip();
                    continue;
                }
                if (agent != null && HORUS.equals(ns) && "memory".equals(xml.getLocalName())) {
                    Map<String, String> attributes = new HashMap<>();
                    for (String a : List.of("from", "fields", "as", "required", "includeMasked")) {
                        if (attr(a) != null) {
                            attributes.put(a, attr(a));
                        }
                    }
                    agent.memory(attributes, line());
                    skip();
                    continue;
                }
                if (agent != null && HORUS.equals(ns) && "retry".equals(xml.getLocalName())) {
                    agent.retry(attr("max"), attr("backoff"), line());
                    skip();
                    continue;
                }
                if (HORUS.equals(ns) && "input".equals(xml.getLocalName()) && inputs) {
                    String inputName = attr("name");
                    String value = xml.getAttributeValue(null, "value");
                    String mask = attr("mask");
                    if (mask != null && (agent == null || !("true".equals(mask) || "false".equals(mask)))) {
                        invalid.add(new DefinitionProblem("INPUT_INVALID", agent == null
                                ? "horus:input mask applies to an agentTask only"
                                : "horus:input mask is true or false", element, safe(id), line()));
                    }
                    if (inputName == null || value == null || !inputName.matches("^[A-Za-z_][A-Za-z0-9_]{0,63}$")) {
                        invalid.add(new DefinitionProblem("INPUT_INVALID",
                                "horus:input needs a name (identifier) and a value", element, safe(id), line()));
                    } else if (!names.add(inputName)) {
                        invalid.add(new DefinitionProblem("INPUT_DUPLICATE", "Duplicate horus:input " + inputName,
                                element, safe(id), line()));
                    } else {
                        horus.add(new InputMapping(inputName, value, "true".equals(mask), mask != null));
                    }
                    skip();
                } else if (HORUS.equals(ns)) {
                    unsupported("horus:" + xml.getLocalName(), id, BpmnStages.NOT_PLANNED);
                    skip();
                } else {
                    // Vendor extensions stay opaque and are never executed; the stored source keeps them.
                    foreign.add(new ForeignFragment(ns == null ? "" : ns, xml.getLocalName(), ""));
                    foreignIgnored(xml.getLocalName(), ns, id);
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
            if (BPMN.equals(xml.getNamespaceURI())) {
                for (int i = 0; i < xml.getAttributeCount(); i++) {
                    String ns = xml.getAttributeNamespace(i);
                    if (ns != null && !ns.isEmpty() && !KNOWN_ATTRIBUTE_NAMESPACES.contains(ns)) {
                        String prefix = xml.getAttributePrefix(i);
                        foreignIgnored((prefix == null || prefix.isEmpty() ? "" : prefix + ":")
                                + xml.getAttributeLocalName(i), ns, xml.getAttributeValue(null, "id"));
                    }
                }
            }
        }

        /** A vendor attribute or extension element: never read or executed, kept only in the stored source. */
        private void foreignIgnored(String name, String namespace, String id) {
            if (warnings.size() < MAX_WARNINGS) {
                warnings.add(new DefinitionProblem("FOREIGN_EXTENSION_IGNORED", "is in namespace " + safe(namespace)
                        + "; it is ignored and the stored source keeps it", safe(name), safe(id), line()));
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
            unsupported(element, id, stage, BpmnStages.message(stage));
        }

        private void unsupported(String element, String id, String stage, String message) {
            addProblem(new DefinitionProblem("BPMN_UNSUPPORTED_ELEMENT", message, element, safe(id), line(), stage));
        }

        /** Keeps the first {@link BpmnUnsupportedElementException#MAX_PROBLEMS} problems; counts the rest. */
        private void addProblem(DefinitionProblem problem) {
            if (unsupported.size() < BpmnUnsupportedElementException.MAX_PROBLEMS) {
                unsupported.add(problem);
            } else {
                unsupportedTruncated = true;
            }
        }
    }

    /** Longest id echoed in a problem. */
    private static final int MAX_ECHO = 200;

    /** An id echoed in a problem: control characters removed, at most 200 characters. */
    static String safe(String id) {
        if (id == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(Math.min(id.length(), MAX_ECHO));
        for (int i = 0; i < id.length() && out.length() < MAX_ECHO; i++) {
            char c = id.charAt(i);
            if (!Character.isISOControl(c) && c != '\u2028' && c != '\u2029') {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static ElementInfo info(List<HorusExtension> horus, List<ForeignFragment> foreign) {
        return new ElementInfo(List.of(), new ExtensionBag(horus, foreign), Map.of());
    }

    /** Fills {@code incoming} and {@code outgoing} of every node from the sequence flows. */
    private static Process withRefs(Process p) {
        return new Process(p.id(), p.name(), p.processType(), p.isExecutable(), p.isClosed(),
                withRefs(p.flowElements()), p.artifacts(), p.laneSets(), p.info());
    }

    /** Fills incoming and outgoing of the elements of one container from its own sequence flows. */
    private static List<FlowElement> withRefs(List<FlowElement> elements) {
        List<SequenceFlow> flows = elements.stream().filter(SequenceFlow.class::isInstance)
                .map(SequenceFlow.class::cast).toList();
        List<FlowElement> out = new ArrayList<>();
        for (FlowElement e : elements) {
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
                case IntermediateThrowEvent t -> new IntermediateThrowEvent(t.id(), t.name(), in, o,
                        t.eventDefinitions(), t.eventDefinitionRefs(), t.data(), t.info());
                case ParallelGateway g -> new ParallelGateway(g.id(), g.name(), in, o, g.gatewayDirection(), g.info());
                case Transaction t -> new Transaction(t.id(), t.name(), in, o, t.activity(), t.method(),
                        t.flowElements(), t.artifacts(), t.laneSets(), t.info());
                default -> e;
            });
        }
        return out;
    }
}
