package io.github.dhluong90.bpm.process.bpmn.subset;

import io.github.dhluong90.bpm.process.bpmn.xml.BpmnNamespaces;
import io.github.dhluong90.bpm.process.bpmn.xml.Limits;
import io.github.dhluong90.bpm.process.bpmn.xml.XmlHardening;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowElement;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SequenceFlow;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads the {@code BPMNDI} part of a BPMN file as a {@code definition-layout.v1} document (#445, DESIGN section 5.4):
 * the bounds of each shape of a step and the waypoints of each sequence flow. The document is stored as layout
 * revision 1 of the imported version, apart from the definition, so the definition hash never depends on it.
 *
 * <p>Only what the layout schema holds is kept. A shape of something that is not a step of the process (an annotation,
 * a group, a lane), a coordinate outside the schema's range and a waypoint list longer than 50 points are dropped, never
 * repaired. A file with no diagram, or none that is kept, gives no layout. The same hardened XML reader as the
 * definition reader is used, so a document that was accepted for import can be read here and nothing else is.
 * Thread-safe.
 */
public final class BpmnLayoutReader {

    private static final Pattern NODE_ID = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.-]*$");
    private static final BigDecimal COORDINATE_LIMIT = BigDecimal.valueOf(100_000);
    private static final BigDecimal SIZE_LIMIT = BigDecimal.valueOf(2_000);
    private static final int MAX_WAYPOINTS = 50;
    private static final int MAX_NODES = 500;
    private static final int MAX_EDGES = 2_000;

    private final Limits limits;

    /** @param limits the limits of the XML read, those of the definition reader */
    public BpmnLayoutReader(Limits limits) {
        this.limits = limits;
    }

    /**
     * @param source the BPMN XML that was imported
     * @param process the process read from it
     * @return the layout document as JSON, or null when the file has no usable diagram
     */
    public String read(byte[] source, Process process) {
        Map<String, String> nodeIds = new HashMap<>();
        Map<String, String[]> flows = new HashMap<>();
        for (FlowElement element : process.allFlowElements()) {
            if (element instanceof FlowNode node) {
                nodeIds.put(node.id(), node.id());
            } else if (element instanceof SequenceFlow flow) {
                flows.put(flow.id(), new String[] {flow.sourceRef(), flow.targetRef()});
            }
        }
        Map<String, BigDecimal[]> boxes = new LinkedHashMap<>();
        Map<String, List<BigDecimal[]>> edges = new LinkedHashMap<>();
        String text = XmlHardening.utf8(source);
        XMLInputFactory factory = XmlHardening.newInputFactory(limits);
        try {
            XMLStreamReader xml = factory.createXMLStreamReader(new StringReader(text));
            try {
                String element = null;
                boolean edge = false;
                BigDecimal[] bounds = null;
                List<BigDecimal[]> points = null;
                while (xml.hasNext()) {
                    int event = xml.next();
                    if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
                        return null;
                    }
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String ns = xml.getNamespaceURI();
                        String name = xml.getLocalName();
                        if (BpmnNamespaces.BPMNDI.equals(ns) && ("BPMNShape".equals(name) || "BPMNEdge".equals(name))) {
                            element = xml.getAttributeValue(null, "bpmnElement");
                            edge = "BPMNEdge".equals(name);
                            bounds = null;
                            points = new ArrayList<>();
                        } else if (element != null && !edge && "Bounds".equals(name)) {
                            bounds = new BigDecimal[] {number(xml, "x"), number(xml, "y"), number(xml, "width"),
                                    number(xml, "height")};
                        } else if (element != null && edge && "waypoint".equals(name)) {
                            points.add(new BigDecimal[] {number(xml, "x"), number(xml, "y")});
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT && element != null
                            && BpmnNamespaces.BPMNDI.equals(xml.getNamespaceURI())) {
                        String name = xml.getLocalName();
                        if ("BPMNShape".equals(name) && bounds != null) {
                            keepBox(boxes, nodeIds, element, bounds);
                        } else if ("BPMNEdge".equals(name)) {
                            keepEdge(edges, flows, nodeIds, element, points);
                        }
                        if ("BPMNShape".equals(name) || "BPMNEdge".equals(name)) {
                            element = null;
                        }
                    }
                }
            } finally {
                xml.close();
            }
        } catch (XMLStreamException e) {
            return null;
        }
        if (boxes.isEmpty()) {
            return null;
        }
        return json(boxes, edges);
    }

    private static void keepBox(Map<String, BigDecimal[]> boxes, Map<String, String> nodeIds, String element,
            BigDecimal[] b) {
        if (!nodeIds.containsKey(element) || !NODE_ID.matcher(element).matches() || boxes.size() >= MAX_NODES
                || b[0] == null || b[1] == null || b[0].abs().compareTo(COORDINATE_LIMIT) > 0
                || b[1].abs().compareTo(COORDINATE_LIMIT) > 0) {
            return;
        }
        BigDecimal width = b[2] != null && b[2].signum() > 0 && b[2].compareTo(SIZE_LIMIT) <= 0 ? b[2] : null;
        BigDecimal height = b[3] != null && b[3].signum() > 0 && b[3].compareTo(SIZE_LIMIT) <= 0 ? b[3] : null;
        boxes.put(element, new BigDecimal[] {b[0], b[1], width, height});
    }

    private static void keepEdge(Map<String, List<BigDecimal[]>> edges, Map<String, String[]> flows,
            Map<String, String> nodeIds, String element, List<BigDecimal[]> points) {
        String[] ends = flows.get(element);
        if (ends == null || !nodeIds.containsKey(ends[0]) || !nodeIds.containsKey(ends[1])
                || !NODE_ID.matcher(ends[0]).matches() || !NODE_ID.matcher(ends[1]).matches() || points.isEmpty()
                || points.size() > MAX_WAYPOINTS || edges.size() >= MAX_EDGES) {
            return;
        }
        for (BigDecimal[] p : points) {
            if (p[0] == null || p[1] == null || p[0].abs().compareTo(COORDINATE_LIMIT) > 0
                    || p[1].abs().compareTo(COORDINATE_LIMIT) > 0) {
                return;
            }
        }
        edges.putIfAbsent(ends[0] + "->" + ends[1], points);
    }

    private static BigDecimal number(XMLStreamReader xml, String attribute) {
        String value = xml.getAttributeValue(null, attribute);
        if (value == null || value.length() > 32) {
            return null;
        }
        try {
            return new BigDecimal(value.strip()).stripTrailingZeros();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String json(Map<String, BigDecimal[]> boxes, Map<String, List<BigDecimal[]>> edges) {
        StringBuilder out = new StringBuilder("{\"schemaVersion\":1,\"direction\":\"LR\",\"nodes\":{");
        boolean first = true;
        for (Map.Entry<String, BigDecimal[]> e : boxes.entrySet()) {
            BigDecimal[] b = e.getValue();
            out.append(first ? "" : ",").append('"').append(e.getKey()).append("\":{\"x\":").append(b[0].toPlainString())
                    .append(",\"y\":").append(b[1].toPlainString());
            if (b[2] != null) {
                out.append(",\"width\":").append(b[2].toPlainString());
            }
            if (b[3] != null) {
                out.append(",\"height\":").append(b[3].toPlainString());
            }
            out.append('}');
            first = false;
        }
        out.append("},\"edges\":{");
        first = true;
        for (Map.Entry<String, List<BigDecimal[]>> e : edges.entrySet()) {
            out.append(first ? "" : ",").append('"').append(e.getKey()).append("\":[");
            boolean firstPoint = true;
            for (BigDecimal[] p : e.getValue()) {
                out.append(firstPoint ? "" : ",").append("{\"x\":").append(p[0].toPlainString()).append(",\"y\":")
                        .append(p[1].toPlainString()).append('}');
                firstPoint = false;
            }
            out.append(']');
            first = false;
        }
        return out.append("}}").toString();
    }
}
