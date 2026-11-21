package io.github.dhluong90.bpm.process.bpmn.subset;

import io.github.dhluong90.bpm.process.bpmn.xml.BpmnNamespaces;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Artifact;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Association;
import io.github.dhluong90.bpm.process.internal.model.bpmn.BusinessRuleTask;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EndEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ExclusiveGateway;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowElement;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Group;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SequenceFlow;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ServiceTask;
import io.github.dhluong90.bpm.process.internal.model.bpmn.StartEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.TextAnnotation;
import io.github.dhluong90.bpm.process.internal.model.bpmn.UserTask;
import io.github.dhluong90.bpm.process.internal.model.horus.DecisionRef;
import io.github.dhluong90.bpm.process.internal.model.horus.AgentTaskSpec;
import io.github.dhluong90.bpm.process.internal.model.horus.GuardrailSpec;
import io.github.dhluong90.bpm.process.internal.model.horus.HandlerRef;
import io.github.dhluong90.bpm.process.internal.model.horus.InputMapping;
import io.github.dhluong90.bpm.process.internal.model.horus.ResultVariable;
import io.github.dhluong90.bpm.process.internal.model.horus.MemoryEntry;
import io.github.dhluong90.bpm.process.internal.model.horus.RetryPolicy;
import io.github.dhluong90.bpm.process.internal.model.horus.TaskAssignment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes the executable BPMN subset that {@link SubsetBpmnReader} reads (PRE-034): none {@code startEvent} and
 * {@code endEvent}, {@code serviceTask}, {@code businessRuleTask}, {@code userTask}, {@code exclusiveGateway} with
 * {@code default}, and {@code sequenceFlow} with its {@code conditionExpression}, with the {@code horus} attributes
 * and {@code horus:input} elements. Reading the output gives a process with the same definition hash as the one
 * written.
 *
 * <p>The document carries {@code incoming} and {@code outgoing} references and a generated left-to-right BPMNDI
 * layout, so it opens in BPMN modellers. Layout and foreign extensions are not part of the definition, so they are not
 * carried over from the source document.
 *
 * <p>Text annotations, associations and groups are written after the flow elements, with the categories that hold the
 * groups' labels after the process. They are not part of the definition either, but they are what a reader of the
 * diagram sees, so they are kept; the generated layout puts annotations and groups in a row under the flow.
 *
 * <p>The output is deterministic: the same process gives the same bytes. Every attribute and text value is escaped,
 * including tab, line feed and carriage return, so a value reads back exactly. Stateless and thread-safe.
 */
public final class SubsetBpmnWriter {

    private static final String INDENT = "  ";
    private static final int COLUMN_WIDTH = 170;
    private static final int ROW_HEIGHT = 130;
    private static final int LEFT = 150;
    private static final int TOP = 80;
    private static final int BACK_EDGE_MARGIN = 50;
    private static final int ARTIFACT_WIDTH = 150;
    private static final int ANNOTATION_HEIGHT = 60;
    private static final int GROUP_HEIGHT = 80;
    private static final int ARTIFACT_GAP = 50;

    /**
     * @param process a process in the executable subset
     * @return the BPMN 2.0 XML document, UTF-8
     */
    public byte[] write(Process process) {
        Xml xml = new Xml();
        xml.line(0, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        xml.line(0, "<bpmn:definitions xmlns:bpmn=\"" + BpmnNamespaces.BPMN + "\"");
        xml.line(0, "                  xmlns:bpmndi=\"" + BpmnNamespaces.BPMNDI + "\"");
        xml.line(0, "                  xmlns:dc=\"" + BpmnNamespaces.DC + "\"");
        xml.line(0, "                  xmlns:di=\"" + BpmnNamespaces.DI + "\"");
        xml.line(0, "                  xmlns:xsi=\"" + BpmnNamespaces.XSI + "\"");
        xml.line(0, "                  xmlns:horus=\"" + BpmnNamespaces.HORUS + "\"");
        xml.line(0, "                  id=" + attr("Definitions_" + process.id()));
        xml.line(0, "                  targetNamespace=\"urn:horus-bpm:definitions\"");
        xml.line(0, "                  expressionLanguage=\"" + BpmnNamespaces.EXPR_V1 + "\"");
        xml.line(0, "                  exporter=\"HORUS Process Engine\">");
        xml.line(1, "<bpmn:process id=" + attr(process.id()) + optional("name", process.name())
                + " isExecutable=\"true\">");
        for (FlowElement element : process.flowElements()) {
            switch (element) {
                case SequenceFlow flow -> sequenceFlow(xml, flow);
                case FlowNode node -> node(xml, node);
                default -> throw new IllegalArgumentException("Element " + element.id() + " ("
                        + element.getClass().getSimpleName() + ") is outside the exportable subset");
            }
        }
        process.artifacts().forEach(artifact -> artifact(xml, artifact));
        xml.line(1, "</bpmn:process>");
        Set<String> ids = new HashSet<>();
        process.flowElements().forEach(e -> ids.add(e.id()));
        process.artifacts().forEach(a -> ids.add(a.id()));
        ids.add(process.id());
        ids.add("Definitions_" + process.id());
        categories(xml, process, ids);
        diagram(xml, process, ids);
        xml.line(0, "</bpmn:definitions>");
        return xml.out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void node(Xml xml, FlowNode node) {
        String tag;
        StringBuilder attributes = new StringBuilder(" id=" + attr(node.id()) + optional("name", node.name()));
        List<InputMapping> inputs = List.of();
        AgentTaskSpec agent = node.extensions().first(AgentTaskSpec.class).orElse(null);
        GuardrailSpec guardrail = node.extensions().first(GuardrailSpec.class).orElse(null);
        switch (node) {
            case StartEvent s -> tag = "bpmn:startEvent";
            case EndEvent e -> tag = "bpmn:endEvent";
            case ServiceTask t when agent != null -> {
                tag = "bpmn:serviceTask";
                attributes.append(" horus:type=\"agentTask\"").append(optional("horus:agent", agent.agent()))
                        .append(optional("horus:timeout", agent.timeout() == null ? null : agent.timeout().toString()));
                inputs = t.extensions().all(InputMapping.class);
            }
            case ServiceTask t -> {
                tag = "bpmn:serviceTask";
                t.extensions().first(HandlerRef.class).ifPresent(h -> attributes.append(optional("horus:handler",
                        h.handler())).append(optional("horus:mode", h.mode()))
                        .append(optional("horus:timeout", h.timeout() == null ? null : h.timeout().toString())));
                inputs = t.extensions().all(InputMapping.class);
            }
            case BusinessRuleTask t -> {
                tag = "bpmn:businessRuleTask";
                t.extensions().first(DecisionRef.class).ifPresent(d -> attributes.append(optional("horus:ruleSet",
                        d.ruleSet())));
                inputs = t.extensions().all(InputMapping.class);
            }
            case UserTask t -> {
                tag = "bpmn:userTask";
                t.extensions().first(TaskAssignment.class).ifPresent(a -> attributes.append(optional("horus:assignee",
                        a.assignee())).append(a.fourEyes() ? " horus:fourEyes=\"true\"" : ""));
            }
            case ExclusiveGateway g -> {
                tag = "bpmn:exclusiveGateway";
                attributes.append(optional("default", g.defaultFlow()));
            }
            default -> throw new IllegalArgumentException("Element " + node.id() + " ("
                    + node.getClass().getSimpleName() + ") is outside the exportable subset");
        }
        node.extensions().first(ResultVariable.class).ifPresent(r -> attributes.append(optional("horus:resultVariable",
                r.name())));
        if (inputs.isEmpty() && agent == null && node.incoming().isEmpty() && node.outgoing().isEmpty()) {
            xml.line(2, "<" + tag + attributes + "/>");
            return;
        }
        xml.line(2, "<" + tag + attributes + ">");
        if (!inputs.isEmpty() || agent != null) {
            xml.line(3, "<bpmn:extensionElements>");
            for (InputMapping input : inputs) {
                xml.line(4, "<horus:input name=" + attr(input.name()) + " value=" + attr(input.value())
                        + (input.mask() ? " mask=\"true\"" : "") + "/>");
            }
            if (agent != null && agent.outputSchema() != null) {
                xml.line(4, "<horus:outputSchema>" + text(agent.outputSchema()) + "</horus:outputSchema>");
            }
            if (guardrail != null) {
                xml.line(4, "<horus:guardrail" + optional("decision", guardrail.decision())
                        + optional("onViolation", guardrail.onViolation()) + optional("reviewer", guardrail.reviewer())
                        + "/>");
            }
            RetryPolicy retry = agent == null ? null : node.extensions().first(RetryPolicy.class).orElse(null);
            if (retry != null) {
                xml.line(4, "<horus:retry" + optional("max", String.valueOf(retry.max()))
                        + optional("backoff", retry.backoff() == null ? null : retry.backoff().toString()) + "/>");
            }
            if (agent != null) {
                // #101: memory is part of the step, so the export reads back to the same definition hash.
                for (MemoryEntry m : node.extensions().all(MemoryEntry.class)) {
                    xml.line(4, "<horus:memory" + optional("from", m.from()) + optional("fields",
                            String.join(",", m.fields())) + optional("as", m.as()) + " required=\"" + m.required()
                            + "\"" + (m.includeMasked() ? " includeMasked=\"true\"" : "") + "/>");
                }
            }
            xml.line(3, "</bpmn:extensionElements>");
        }
        node.incoming().forEach(id -> xml.line(3, "<bpmn:incoming>" + text(id) + "</bpmn:incoming>"));
        node.outgoing().forEach(id -> xml.line(3, "<bpmn:outgoing>" + text(id) + "</bpmn:outgoing>"));
        xml.line(2, "</" + tag + ">");
    }

    private static void artifact(Xml xml, Artifact artifact) {
        switch (artifact) {
            case TextAnnotation note -> {
                String start = "<bpmn:textAnnotation id=" + attr(note.id()) + optional("textFormat", note.textFormat());
                if (note.text() == null) {
                    xml.line(2, start + "/>");
                } else {
                    xml.line(2, start + ">");
                    xml.line(3, "<bpmn:text>" + text(note.text()) + "</bpmn:text>");
                    xml.line(2, "</bpmn:textAnnotation>");
                }
            }
            case Association a -> xml.line(2, "<bpmn:association id=" + attr(a.id()) + " associationDirection=\""
                    + switch (a.associationDirection()) {
                        case NONE -> "None";
                        case ONE -> "One";
                        case BOTH -> "Both";
                    } + "\"" + optional("sourceRef", a.sourceRef()) + optional("targetRef", a.targetRef()) + "/>");
            case Group g -> xml.line(2, "<bpmn:group id=" + attr(g.id()) + optional("categoryValueRef",
                    g.categoryValueRef()) + "/>");
        }
    }

    /**
     * One category per category id the groups name, holding each referenced value once, in document order. A group
     * whose reference did not resolve in the source keeps the reference and gets no category, as in the source.
     */
    private static void categories(Xml xml, Process process, Set<String> ids) {
        Map<String, Map<String, String>> categories = new LinkedHashMap<>();
        Set<String> values = new HashSet<>();
        for (Artifact artifact : process.artifacts()) {
            String category = artifact instanceof Group g ? SubsetBpmnReader.categoryId(g) : null;
            if (category != null && values.add(((Group) artifact).categoryValueRef())) {
                Group g = (Group) artifact;
                ids.add(g.categoryValueRef());
                categories.computeIfAbsent(category, k -> new LinkedHashMap<>()).put(g.categoryValueRef(),
                        SubsetBpmnReader.categoryValueLabel(g));
            }
        }
        for (Map.Entry<String, Map<String, String>> category : categories.entrySet()) {
            xml.line(1, "<bpmn:category id=" + attr(unique(ids, category.getKey())) + ">");
            category.getValue().forEach((id, label) -> xml.line(2, "<bpmn:categoryValue id=" + attr(id)
                    + optional("value", label) + "/>"));
            xml.line(1, "</bpmn:category>");
        }
    }

    private static void sequenceFlow(Xml xml, SequenceFlow flow) {
        String start = "<bpmn:sequenceFlow id=" + attr(flow.id()) + optional("name", flow.name())
                + optional("sourceRef", flow.sourceRef()) + optional("targetRef", flow.targetRef());
        if (flow.conditionExpression() == null) {
            xml.line(2, start + "/>");
            return;
        }
        String language = flow.conditionExpression().language() == null ? BpmnNamespaces.EXPR_V1
                : flow.conditionExpression().language();
        xml.line(2, start + ">");
        xml.line(3, "<bpmn:conditionExpression xsi:type=\"bpmn:tFormalExpression\" language=" + attr(language) + ">"
                + text(flow.conditionExpression().text()) + "</bpmn:conditionExpression>");
        xml.line(2, "</bpmn:sequenceFlow>");
    }

    // ---- generated layout ---------------------------------------------------------------------------------

    private record Box(int x, int y, int width, int height) {
        int centreX() {
            return x + width / 2;
        }

        int centreY() {
            return y + height / 2;
        }
    }

    /**
     * Columns are the shortest distance from a start event along the sequence flows; rows follow document order
     * within a column. Nodes no start event reaches go in the column after the last one.
     */
    private static void diagram(Xml xml, Process process, Set<String> ids) {
        List<FlowNode> nodes = process.flowElements().stream().filter(FlowNode.class::isInstance)
                .map(FlowNode.class::cast).toList();
        List<SequenceFlow> flows = process.flowElements().stream().filter(SequenceFlow.class::isInstance)
                .map(SequenceFlow.class::cast).toList();
        Map<String, Integer> column = columns(nodes, flows);
        Map<Integer, Integer> rowsUsed = new HashMap<>();
        Map<String, Box> boxes = new LinkedHashMap<>();
        for (FlowNode node : nodes) {
            int col = column.get(node.id());
            int row = rowsUsed.merge(col, 1, Integer::sum) - 1;
            int[] size = size(node);
            int cellX = LEFT + col * COLUMN_WIDTH;
            int cellY = TOP + row * ROW_HEIGHT;
            boxes.put(node.id(), new Box(cellX + (100 - size[0]) / 2, cellY + (80 - size[1]) / 2, size[0], size[1]));
        }
        int bottom = boxes.values().stream().mapToInt(b -> b.y() + b.height()).max().orElse(TOP);
        Map<String, Box> artifactBoxes = new LinkedHashMap<>();
        int artifactX = LEFT;
        for (Artifact artifact : process.artifacts()) {
            int height = switch (artifact) {
                case TextAnnotation t -> ANNOTATION_HEIGHT;
                case Group g -> GROUP_HEIGHT;
                case Association a -> 0;
            };
            if (height > 0) {
                artifactBoxes.put(artifact.id(), new Box(artifactX, bottom + 2 * BACK_EDGE_MARGIN, ARTIFACT_WIDTH,
                        height));
                artifactX += ARTIFACT_WIDTH + ARTIFACT_GAP;
            }
        }
        xml.line(1, "<bpmndi:BPMNDiagram id=" + attr(unique(ids, "Diagram_" + process.id())) + ">");
        xml.line(2, "<bpmndi:BPMNPlane id=" + attr(unique(ids, "Plane_" + process.id())) + " bpmnElement="
                + attr(process.id()) + ">");
        for (FlowNode node : nodes) {
            Box b = boxes.get(node.id());
            String marker = node instanceof ExclusiveGateway ? " isMarkerVisible=\"true\"" : "";
            xml.line(3, "<bpmndi:BPMNShape id=" + attr(unique(ids, node.id() + "_di")) + " bpmnElement="
                    + attr(node.id()) + marker + ">");
            xml.line(4, "<dc:Bounds x=\"" + b.x() + "\" y=\"" + b.y() + "\" width=\"" + b.width() + "\" height=\""
                    + b.height() + "\"/>");
            xml.line(3, "</bpmndi:BPMNShape>");
        }
        for (Map.Entry<String, Box> shape : artifactBoxes.entrySet()) {
            Box b = shape.getValue();
            xml.line(3, "<bpmndi:BPMNShape id=" + attr(unique(ids, shape.getKey() + "_di")) + " bpmnElement="
                    + attr(shape.getKey()) + ">");
            xml.line(4, "<dc:Bounds x=\"" + b.x() + "\" y=\"" + b.y() + "\" width=\"" + b.width() + "\" height=\""
                    + b.height() + "\"/>");
            xml.line(3, "</bpmndi:BPMNShape>");
        }
        for (SequenceFlow flow : flows) {
            Box source = boxes.get(flow.sourceRef());
            Box target = boxes.get(flow.targetRef());
            if (source == null || target == null) {
                continue;
            }
            xml.line(3, "<bpmndi:BPMNEdge id=" + attr(unique(ids, flow.id() + "_di")) + " bpmnElement="
                    + attr(flow.id()) + ">");
            for (int[] point : waypoints(source, target, bottom + BACK_EDGE_MARGIN)) {
                xml.line(4, "<di:waypoint x=\"" + point[0] + "\" y=\"" + point[1] + "\"/>");
            }
            xml.line(3, "</bpmndi:BPMNEdge>");
        }
        // An association is drawn between the centres of its ends; one whose end has no shape (a sequence flow, for
        // example) is kept in the process but not drawn.
        for (Artifact artifact : process.artifacts()) {
            if (!(artifact instanceof Association a)) {
                continue;
            }
            Box source = a.sourceRef() == null ? null : boxes.getOrDefault(a.sourceRef(), artifactBoxes.get(a.sourceRef()));
            Box target = a.targetRef() == null ? null : boxes.getOrDefault(a.targetRef(), artifactBoxes.get(a.targetRef()));
            if (source == null || target == null) {
                continue;
            }
            xml.line(3, "<bpmndi:BPMNEdge id=" + attr(unique(ids, a.id() + "_di")) + " bpmnElement=" + attr(a.id())
                    + ">");
            xml.line(4, "<di:waypoint x=\"" + source.centreX() + "\" y=\"" + source.centreY() + "\"/>");
            xml.line(4, "<di:waypoint x=\"" + target.centreX() + "\" y=\"" + target.centreY() + "\"/>");
            xml.line(3, "</bpmndi:BPMNEdge>");
        }
        xml.line(2, "</bpmndi:BPMNPlane>");
        xml.line(1, "</bpmndi:BPMNDiagram>");
    }

    private static Map<String, Integer> columns(List<FlowNode> nodes, List<SequenceFlow> flows) {
        Map<String, List<String>> next = new HashMap<>();
        for (SequenceFlow f : flows) {
            next.computeIfAbsent(f.sourceRef(), k -> new ArrayList<>()).add(f.targetRef());
        }
        Map<String, Integer> column = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        for (FlowNode node : nodes) {
            if (node instanceof StartEvent) {
                column.put(node.id(), 0);
                queue.add(node.id());
            }
        }
        while (!queue.isEmpty()) {
            String id = queue.poll();
            for (String target : next.getOrDefault(id, List.of())) {
                if (target != null && !column.containsKey(target)) {
                    column.put(target, column.get(id) + 1);
                    queue.add(target);
                }
            }
        }
        int unreached = column.values().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
        for (FlowNode node : nodes) {
            column.putIfAbsent(node.id(), unreached);
        }
        return column;
    }

    private static int[] size(FlowNode node) {
        return switch (node) {
            case StartEvent s -> new int[] {36, 36};
            case EndEvent e -> new int[] {36, 36};
            case ExclusiveGateway g -> new int[] {50, 50};
            default -> new int[] {100, 80};
        };
    }

    /** Forward edges leave right and enter left with one vertical leg; other edges run below the diagram. */
    private static List<int[]> waypoints(Box source, Box target, int below) {
        int sx = source.x() + source.width();
        int tx = target.x();
        if (tx > sx) {
            if (source.centreY() == target.centreY()) {
                return List.of(new int[] {sx, source.centreY()}, new int[] {tx, target.centreY()});
            }
            int mid = sx + (tx - sx) / 2;
            return List.of(new int[] {sx, source.centreY()}, new int[] {mid, source.centreY()},
                    new int[] {mid, target.centreY()}, new int[] {tx, target.centreY()});
        }
        return List.of(new int[] {source.centreX(), source.y() + source.height()}, new int[] {source.centreX(), below},
                new int[] {target.centreX(), below}, new int[] {target.centreX(), target.y() + target.height()});
    }

    private static String unique(Set<String> ids, String wanted) {
        String id = wanted;
        for (int n = 2; !ids.add(id); n++) {
            id = wanted + "_" + n;
        }
        return id;
    }

    // ---- escaping -----------------------------------------------------------------------------------------

    private static String optional(String name, String value) {
        return value == null ? "" : " " + name + "=" + attr(value);
    }

    private static String attr(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\t' -> out.append("&#9;");
                case '\n' -> out.append("&#10;");
                case '\r' -> out.append("&#13;");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static String text(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '\r' -> out.append("&#13;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static final class Xml {
        private final StringBuilder out = new StringBuilder(4096);

        void line(int depth, String text) {
            out.append(INDENT.repeat(depth)).append(text).append('\n');
        }
    }
}
