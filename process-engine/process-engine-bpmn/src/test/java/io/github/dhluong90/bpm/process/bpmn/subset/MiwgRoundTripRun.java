package io.github.dhluong90.bpm.process.bpmn.subset;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.dhluong90.bpm.process.bpmn.xml.Limits;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/**
 * Daily BPMN MIWG run (#204). Not a pass/fail test: it measures. Each reference model of the BPMN MIWG suite
 * ({@code Reference/*.bpmn}) is imported, exported and compared, with three separate checks after the import:
 * <ul>
 *   <li>semantic: the elements, attributes, references and text of the BPMN model namespace are the same in the
 *       source and in the export (prefixes, sibling order, whitespace, default-valued attributes, the derived
 *       {@code incoming} and {@code outgoing} lists and any vendor extension are ignored);</li>
 *   <li>diagram: every BPMNDI shape, edge and label has the same element reference, bounds and waypoints;</li>
 *   <li>schema: the export validates against the BPMN 2.0.2 XSD.</li>
 * </ul>
 * The comparator is this project's own; no third-party comparison tool is used. It writes
 * {@code bpmn-miwg-results.json} and always succeeds; {@code tools/conformance/ratchet.py} applies the ratchet.
 *
 * <p>Runs only when {@code -Dconformance.miwg.dir=<unpacked MIWG suite>} is set (tools/conformance/fetch.sh). The
 * models are build-time data and are never copied; the results hold only model names and the first differences.
 */
class MiwgRoundTripRun {

    private static final String BPMN = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final String BPMNDI = "http://www.omg.org/spec/BPMN/20100524/DI";
    private static final String DC = "http://www.omg.org/spec/DD/20100524/DC";
    private static final String DI = "http://www.omg.org/spec/DD/20100524/DI";
    private static final Set<String> DERIVED = Set.of("incoming", "outgoing");
    /** Attribute defaults of the BPMN 2.0.2 schema: an export may leave them out. */
    private static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry("completionQuantity", "1"), Map.entry("startQuantity", "1"),
            Map.entry("isForCompensation", "false"), Map.entry("triggeredByEvent", "false"),
            Map.entry("isInterrupting", "true"), Map.entry("cancelActivity", "true"),
            Map.entry("parallelMultiple", "false"), Map.entry("gatewayDirection", "Unspecified"),
            Map.entry("instantiate", "false"), Map.entry("isClosed", "false"), Map.entry("processType", "None"),
            Map.entry("eventGatewayType", "Exclusive"), Map.entry("isSequential", "false"),
            Map.entry("behavior", "All"), Map.entry("isImmediate", "false"));

    private record Facts(Set<String> model, Set<String> diagram) { }

    private record ModelResult(String name, boolean read, String readError, boolean semantic, boolean diagram,
            boolean schema, String schemaError, List<String> semanticDiffs, List<String> diagramDiffs) {
        boolean all() {
            return read && semantic && diagram && schema;
        }
    }

    @Test
    void measure_bpmn_miwg_round_trip() throws Exception {
        String dir = System.getProperty("conformance.miwg.dir");
        assumeTrue(dir != null && !dir.isBlank(), "conformance.miwg.dir is not set; run tools/conformance/fetch.sh");
        Path out = Path.of(System.getProperty("conformance.out", "target/conformance")).toAbsolutePath();
        Files.createDirectories(out);
        List<Path> models;
        try (Stream<Path> s = Files.list(Path.of(dir, "Reference"))) {
            models = s.filter(p -> p.getFileName().toString().endsWith(".bpmn")).sorted().toList();
        }
        SubsetBpmnReader reader = new SubsetBpmnReader(Limits.DEFAULTS);
        SubsetBpmnWriter writer = new SubsetBpmnWriter();
        Schema schema = bpmnSchema();
        List<ModelResult> results = new ArrayList<>();
        for (Path model : models) {
            results.add(run(model, reader, writer, schema));
        }
        write(out, results);
    }

    /** Controls for the comparator itself: it accepts a stable export and sees a changed name and a moved shape. */
    @Test
    void comparator_accepts_an_export_of_an_export_and_sees_a_change() throws Exception {
        SubsetBpmnReader reader = new SubsetBpmnReader(Limits.DEFAULTS);
        SubsetBpmnWriter writer = new SubsetBpmnWriter();
        byte[] source;
        try (var in = MiwgRoundTripRun.class.getResourceAsStream("/bpmn/kyc-onboarding.bpmn")) {
            source = in.readAllBytes();
        }
        byte[] first = writer.write(reader.read(source));
        byte[] second = writer.write(reader.read(first));
        Facts a = facts(first);
        Facts b = facts(second);
        org.junit.jupiter.api.Assertions.assertFalse(a.model.isEmpty());
        org.junit.jupiter.api.Assertions.assertFalse(a.diagram.isEmpty());
        org.junit.jupiter.api.Assertions.assertEquals(List.of(), diff(a.model, b.model));
        org.junit.jupiter.api.Assertions.assertEquals(List.of(), diff(a.diagram, b.diagram));

        String text = new String(first, StandardCharsets.UTF_8);
        byte[] renamed = text.replaceFirst("name=\"([^\"]+)\"", "name=\"changed\"").getBytes(StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertFalse(diff(a.model, facts(renamed).model).isEmpty());
        byte[] moved = text.replaceFirst("x=\"([0-9.]+)\"", "x=\"999999\"").getBytes(StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertFalse(diff(a.diagram, facts(moved).diagram).isEmpty());
    }

    private static ModelResult run(Path file, SubsetBpmnReader reader, SubsetBpmnWriter writer, Schema schema)
            throws IOException {
        String name = file.getFileName().toString().replaceFirst("\\.bpmn$", "");
        byte[] source = Files.readAllBytes(file);
        byte[] exported;
        try {
            Process process = reader.read(source);
            exported = writer.write(process);
        } catch (Throwable t) {
            return new ModelResult(name, false, describe(t), false, false, false, null, List.of(), List.of());
        }
        List<String> semanticDiffs = List.of();
        List<String> diagramDiffs = List.of();
        try {
            Facts before = facts(source);
            Facts after = facts(exported);
            semanticDiffs = diff(before.model, after.model);
            diagramDiffs = diff(before.diagram, after.diagram);
        } catch (Exception e) {
            semanticDiffs = List.of("comparison failed: " + describe(e));
            diagramDiffs = semanticDiffs;
        }
        String schemaError = null;
        try {
            Validator validator = schema.newValidator();
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            validator.validate(new StreamSource(new ByteArrayInputStream(exported)));
        } catch (Exception e) {
            schemaError = describe(e);
        }
        return new ModelResult(name, true, null, semanticDiffs.isEmpty(), diagramDiffs.isEmpty(), schemaError == null,
                schemaError, semanticDiffs, diagramDiffs);
    }

    // ---- the comparator -------------------------------------------------------------------------------------

    private static Facts facts(byte[] xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        Set<String> model = new TreeSet<>();
        Set<String> diagram = new TreeSet<>();
        walk(doc.getDocumentElement(), "", model, diagram);
        return new Facts(model, diagram);
    }

    private static void walk(Element e, String parentKey, Set<String> model, Set<String> diagram) {
        String ns = e.getNamespaceURI();
        String local = e.getLocalName();
        if (BPMN.equals(ns)) {
            if (DERIVED.contains(local) || local.equals("extensionElements")) {
                return;
            }
            // The key is the element and its parent only: ids of ancestors, and the definitions element's own id,
            // name and namespace, are not compared (an export may mint them).
            boolean root = local.equals("definitions");
            String self = root ? "definitions" : local + idPart(e, parentKey, model);
            if (!root) {
                model.add(parentKey + "/" + self + attributes(e) + leafText(e));
            }
            for (Element c : childElements(e)) {
                walk(c, self, model, diagram);
            }
        } else if (BPMNDI.equals(ns)) {
            String key = parentKey + "/di:" + local;
            String ref = e.getAttribute("bpmnElement");
            String id = ref.isEmpty() ? "" : "[" + ref + "]";
            if (local.equals("BPMNShape") || local.equals("BPMNEdge")) {
                StringBuilder fact = new StringBuilder(local + id + attributes(e));
                List<String> parts = new ArrayList<>();
                collectGeometry(e, parts);
                fact.append(" geometry=").append(parts);
                diagram.add(fact.toString()); // a label is folded into its shape or edge by collectGeometry
            }
            if (local.equals("BPMNDiagram") || local.equals("BPMNPlane")) {
                diagram.add(local + attributesOf(e, "bpmnElement"));
            }
            for (Element c : childElements(e)) {
                walk(c, key, model, diagram);
            }
        }
        // any other namespace is a vendor extension: not part of the BPMN semantics, not compared
    }

    private static void collectGeometry(Element e, List<String> parts) {
        for (Element c : childElements(e)) {
            if (DC.equals(c.getNamespaceURI()) && c.getLocalName().equals("Bounds")) {
                parts.add("bounds(" + num(c.getAttribute("x")) + "," + num(c.getAttribute("y")) + ","
                        + num(c.getAttribute("width")) + "," + num(c.getAttribute("height")) + ")");
            } else if (DI.equals(c.getNamespaceURI()) && c.getLocalName().equals("waypoint")) {
                parts.add("wp(" + num(c.getAttribute("x")) + "," + num(c.getAttribute("y")) + ")");
            } else if (BPMNDI.equals(c.getNamespaceURI()) && c.getLocalName().equals("BPMNLabel")) {
                List<String> inner = new ArrayList<>();
                collectGeometry(c, inner);
                parts.add("label" + inner);
            }
        }
    }

    private static String idPart(Element e, String parentKey, Set<String> model) {
        String id = e.getAttribute("id");
        if (!id.isEmpty()) {
            return "#" + id;
        }
        // no id: key by position among same-named siblings without an id
        int n = 0;
        for (Node s = e.getPreviousSibling(); s != null; s = s.getPreviousSibling()) {
            if (s instanceof Element se && e.getLocalName().equals(se.getLocalName())
                    && sameNs(e.getNamespaceURI(), se.getNamespaceURI()) && se.getAttribute("id").isEmpty()) {
                n++;
            }
        }
        return "#(" + n + ")";
    }

    private static boolean sameNs(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String attributes(Element e) {
        return attributesOf(e, null);
    }

    private static String attributesOf(Element e, String skip) {
        TreeMap<String, String> map = new TreeMap<>();
        NamedNodeMap attrs = e.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr a = (Attr) attrs.item(i);
            if (a.getNamespaceURI() != null || a.getName().startsWith("xmlns") || a.getName().equals("id")) {
                continue; // namespaced attributes are vendor or xsi; the id is in the key
            }
            String n = a.getLocalName() == null ? a.getName() : a.getLocalName();
            if (n.equals(skip) || (DEFAULTS.containsKey(n) && DEFAULTS.get(n).equals(a.getValue()))) {
                continue;
            }
            map.put(n, a.getValue());
        }
        return map.toString();
    }

    private static String leafText(Element e) {
        if (!childElements(e).isEmpty()) {
            return "";
        }
        String text = e.getTextContent() == null ? "" : e.getTextContent().strip().replaceAll("\\s+", " ");
        return text.isEmpty() ? "" : " text=" + text;
    }

    private static List<Element> childElements(Element e) {
        List<Element> list = new ArrayList<>();
        for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element ce) {
                list.add(ce);
            }
        }
        return list;
    }

    private static String num(String s) {
        try {
            return new java.math.BigDecimal(s.trim()).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ex) {
            return s;
        }
    }

    private static List<String> diff(Set<String> before, Set<String> after) {
        List<String> diffs = new ArrayList<>();
        for (String s : before) {
            if (!after.contains(s)) {
                diffs.add("missing in export: " + s);
            }
        }
        for (String s : after) {
            if (!before.contains(s)) {
                diffs.add("extra in export: " + s);
            }
        }
        return diffs;
    }

    // ---- plumbing -------------------------------------------------------------------------------------------

    private static Schema bpmnSchema() throws Exception {
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file,jar");
        URL xsd = MiwgRoundTripRun.class.getResource("/xsd/bpmn20/BPMN20.xsd");
        return factory.newSchema(xsd);
    }

    private static String describe(Throwable t) {
        String message = t.getMessage() == null ? "" : t.getMessage().replaceAll("\\s+", " ");
        return t.getClass().getSimpleName()
                + (message.isEmpty() ? "" : ": " + message.substring(0, Math.min(message.length(), 200)));
    }

    private static void write(Path out, List<ModelResult> results) throws IOException {
        long read = results.stream().filter(ModelResult::read).count();
        long semantic = results.stream().filter(r -> r.read && r.semantic).count();
        long diagram = results.stream().filter(r -> r.read && r.diagram).count();
        long schema = results.stream().filter(r -> r.read && r.schema).count();
        long all = results.stream().filter(ModelResult::all).count();
        StringBuilder json = new StringBuilder("{\n  \"suite\": \"bpmn-miwg\",\n  \"summary\": {\"models\": ")
                .append(results.size()).append(", \"read\": ").append(read).append(", \"semantic\": ").append(semantic)
                .append(", \"diagram\": ").append(diagram).append(", \"schema\": ").append(schema)
                .append(", \"all\": ").append(all).append("},\n  \"results\": [");
        boolean first = true;
        for (ModelResult r : results) {
            json.append(first ? "\n" : ",\n").append("    {\"model\": ").append(q(r.name)).append(", \"read\": ")
                    .append(r.read).append(", \"semantic\": ").append(r.read && r.semantic)
                    .append(", \"diagram\": ").append(r.read && r.diagram).append(", \"schema\": ")
                    .append(r.read && r.schema).append(", \"all\": ").append(r.all()).append(", \"readError\": ")
                    .append(q(r.readError == null ? "" : r.readError)).append(", \"schemaError\": ")
                    .append(q(r.schemaError == null ? "" : r.schemaError)).append(", \"semanticDiffs\": ")
                    .append(list(r.semanticDiffs)).append(", \"semanticDiffCount\": ").append(r.semanticDiffs.size())
                    .append(", \"diagramDiffs\": ").append(list(r.diagramDiffs)).append(", \"diagramDiffCount\": ")
                    .append(r.diagramDiffs.size()).append("}");
            first = false;
        }
        json.append("\n  ]\n}\n");
        Files.writeString(out.resolve("bpmn-miwg-results.json"), json, StandardCharsets.UTF_8);
    }

    /** The first five differences only: the full list is not needed to read a failure. */
    private static String list(List<String> items) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < Math.min(5, items.size()); i++) {
            b.append(i == 0 ? "" : ", ").append(q(items.get(i)));
        }
        return b.append("]").toString();
    }

    private static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                default -> b.append(c < 0x20 ? ' ' : c);
            }
        }
        return b.append('"').toString();
    }
}
