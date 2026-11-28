package io.github.dhluong90.bpm.rules.internal.service;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.SourceFormat;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Daily DMN TCK run (#203). Not a pass/fail test: it measures. For every test case of the DMN TCK
 * {@code compliance-level-2} and {@code compliance-level-3} folders it imports the model through the Rule Engine's
 * version service, publishes it with a second approver, evaluates the case's inputs and compares the result nodes
 * with the expected values. It writes one row per case to {@code dmn-tck-results.json} and a {@code tck_results.csv}
 * in the TCK results format, and always succeeds; {@code tools/conformance/ratchet.py} applies the ratchet.
 *
 * <p>Runs only when {@code -Dconformance.tck.dir=<unpacked TCK>} is set (tools/conformance/fetch.sh). The test cases
 * are build-time data: nothing here copies them, and the results hold only folder names, case ids and a reason.
 * Policy exclusions are read from {@code tools/conformance/dmn-tck-exclusions.txt} ({@code -Dconformance.exclusions}).
 */
class DmnTckConformanceRun {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-12-15T02:00:00Z"), ZoneOffset.UTC);
    private static final String XSI = "http://www.w3.org/2001/XMLSchema-instance";

    private record Row(String level, String folder, String file, String caseId, String status, String reason) { }

    @Test
    void measure_dmn_tck_levels_2_and_3() throws Exception {
        String dir = System.getProperty("conformance.tck.dir");
        assumeTrue(dir != null && !dir.isBlank(), "conformance.tck.dir is not set; run tools/conformance/fetch.sh");
        Path cases = Path.of(dir, "TestCases");
        Path out = Path.of(System.getProperty("conformance.out", "target/conformance")).toAbsolutePath();
        Files.createDirectories(out);
        Set<String> excluded = exclusions();

        List<Row> rows = new ArrayList<>();
        Map<String, Integer> models = new LinkedHashMap<>();
        Map<String, Integer> modelsRead = new LinkedHashMap<>();
        for (String level : List.of("compliance-level-2", "compliance-level-3")) {
            int read = 0;
            int total = 0;
            for (Path folder : folders(cases.resolve(level))) {
                total++;
                if (runFolder(level, folder, excluded, rows)) {
                    read++;
                }
            }
            models.put(level, total);
            modelsRead.put(level, read);
        }
        write(out, rows, models, modelsRead);
    }

    /** Control for the harness itself: a table the engine accepts passes a TCK-shaped case and fails a wrong one. */
    @Test
    void harness_passes_a_correct_case_and_fails_a_wrong_one() throws Exception {
        Evaluator evaluator = new Evaluator();
        org.junit.jupiter.api.Assertions.assertNull(evaluator.load(CardFee.resource("/rules/dmn/card-fee-modeller.dmn")));
        org.junit.jupiter.api.Assertions.assertNull(evaluator.check(testCase("0")));
        org.junit.jupiter.api.Assertions.assertTrue(evaluator.check(testCase("1")).startsWith("MISMATCH"));
    }

    private static Element testCase(String expectedFee) throws Exception {
        String xml = "<testCases xmlns=\"http://www.omg.org/spec/DMN/20160719/testcase\""
                + " xmlns:xsi=\"" + XSI + "\" xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"><testCase id=\"c\">"
                + "<inputNode name=\"tier\"><value xsi:type=\"xsd:string\">GOLD</value></inputNode>"
                + "<inputNode name=\"txCount\"><value xsi:type=\"xsd:decimal\">5</value></inputNode>"
                + "<resultNode name=\"fee\" type=\"decision\"><expected><value xsi:type=\"xsd:decimal\">" + expectedFee
                + "</value></expected></resultNode></testCase></testCases>";
        Path tmp = Files.createTempFile("tck-control", ".xml");
        try {
            Files.writeString(tmp, xml, StandardCharsets.UTF_8);
            return child(parse(tmp).getDocumentElement(), "testCase");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static boolean runFolder(String level, Path folder, Set<String> excluded, List<Row> rows) throws Exception {
        String name = folder.getFileName().toString();
        boolean anyImported = false;
        List<Path> testFiles;
        try (Stream<Path> s = Files.list(folder)) {
            testFiles = s.filter(p -> p.getFileName().toString().endsWith(".xml")).sorted().toList();
        }
        for (Path testFile : testFiles) {
            Document doc = parse(testFile);
            Element root = doc.getDocumentElement();
            String modelName = text(child(root, "modelName"));
            if (modelName == null) {
                continue;
            }
            String file = testFile.getFileName().toString().replaceFirst("\\.xml$", "");
            Path model = folder.resolve(modelName);
            Evaluator evaluator = new Evaluator();
            String importError = Files.exists(model) ? evaluator.load(Files.readAllBytes(model)) : "model file missing";
            anyImported |= importError == null;
            for (Element tc : children(root, "testCase")) {
                String id = tc.getAttribute("id");
                String testId = level + "/" + name + "/" + file + "/" + id;
                if (excluded.contains(testId)) {
                    rows.add(new Row(level, name, file, id, "EXCLUDED", "policy exclusion"));
                    continue;
                }
                String type = tc.getAttribute("type");
                if (!type.isEmpty() && !type.equals("decision")) {
                    rows.add(new Row(level, name, file, id, "FAIL", "UNSUPPORTED: test case type " + type));
                    continue;
                }
                if (importError != null) {
                    rows.add(new Row(level, name, file, id, "FAIL", "IMPORT: " + importError));
                    continue;
                }
                String failure = evaluator.check(tc);
                rows.add(failure == null ? new Row(level, name, file, id, "PASS", "")
                        : new Row(level, name, file, id, "FAIL", failure));
            }
        }
        return anyImported;
    }

    /** One model published in a fresh engine; evaluates the cases of its test file. */
    private static final class Evaluator {
        private RuleEngines engines;
        private String ruleSet;

        String load(byte[] dmn) {
            try {
                InMemoryStores stores = new InMemoryStores();
                TestCaller caller = new TestCaller("bank-a", "alice");
                engines = RuleEngines.create(new EngineContext(caller, caller, CLOCK, stores, stores,
                        EngineSettings.defaults().withRequireContentHash(false)), stores, stores, stores);
                RuleSetVersion draft = engines.versionService().importRuleSet(
                        new ImportRequest(SourceFormat.DMN_XML, dmn, "tck-" + System.nanoTime()));
                ruleSet = draft.ruleSet();
                engines.versionService().requestPublish(ruleSet, draft.version(), WriteOptions.none());
                caller.become("bank-a", "bob");
                engines.versionService().approve(ruleSet, draft.version(), new WriteOptions(null, 1L, null));
                caller.become("bank-a", "teller-01");
                return null;
            } catch (Throwable t) {
                return describe(t);
            }
        }

        String check(Element testCase) {
            try {
                Map<String, Object> inputs = new LinkedHashMap<>();
                for (Element in : children(testCase, "inputNode")) {
                    inputs.put(in.getAttribute("name"), value(child(in, null)));
                }
                DecisionResult result = engines.ruleEngine().evaluate(ruleSet, DecisionInput.of(inputs));
                Map<String, Object> outputs = result.outputs();
                for (Element node : children(testCase, "resultNode")) {
                    if ("true".equals(node.getAttribute("errorResult"))) {
                        return "UNSUPPORTED: expects a FEEL error result";
                    }
                    Element expected = child(node, "expected");
                    Object want = value(expected == null ? null : child(expected, null));
                    Object got = outputs.size() == 1 && !(want instanceof Map)
                            ? outputs.values().iterator().next() : outputs;
                    if (!same(want, got)) {
                        return "MISMATCH: " + node.getAttribute("name") + " expected " + want + " got " + got;
                    }
                }
                return null;
            } catch (Throwable t) {
                return "EVAL: " + describe(t);
            }
        }
    }

    private static boolean same(Object want, Object got) {
        if (want instanceof BigDecimal w && got instanceof Number g) {
            return w.compareTo(new BigDecimal(g.toString())) == 0;
        }
        if (want instanceof Map<?, ?> wm && got instanceof Map<?, ?> gm) {
            if (!wm.keySet().equals(gm.keySet())) {
                return false;
            }
            for (Object k : wm.keySet()) {
                if (!same(wm.get(k), gm.get(k))) {
                    return false;
                }
            }
            return true;
        }
        if (want == null || got == null) {
            return want == got;
        }
        return want.equals(got) || want.toString().equals(got.toString());
    }

    /** A TCK value element: scalar by xsi:type, {@code component}s as a map, {@code list} as a list. */
    private static Object value(Element v) {
        if (v == null || "true".equals(v.getAttributeNS(XSI, "nil"))) {
            return null;
        }
        List<Element> components = children(v, "component");
        if (!components.isEmpty()) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Element c : components) {
                Element inner = child(c, null);
                map.put(c.getAttribute("name"), value(inner != null ? inner : c));
            }
            return map;
        }
        if ("list".equals(v.getLocalName())) {
            List<Object> list = new ArrayList<>();
            for (Element item : children(v, "item")) {
                list.add(value(item));
            }
            return list;
        }
        String type = v.getAttributeNS(XSI, "type");
        String text = v.getTextContent() == null ? "" : v.getTextContent().trim();
        if (type.endsWith(":decimal") || type.endsWith(":double") || type.endsWith(":integer")
                || type.endsWith(":float") || type.endsWith(":int")) {
            return new BigDecimal(text);
        }
        if (type.endsWith(":boolean")) {
            return Boolean.valueOf(text);
        }
        return text;
    }

    private static String describe(Throwable t) {
        String message = t.getMessage() == null ? "" : t.getMessage().replaceAll("\\s+", " ");
        return t.getClass().getSimpleName()
                + (message.isEmpty() ? "" : ": " + message.substring(0, Math.min(message.length(), 160)));
    }

    private static List<Path> folders(Path level) throws IOException {
        try (Stream<Path> s = Files.list(level)) {
            return s.filter(Files::isDirectory).sorted().toList();
        }
    }

    private static Set<String> exclusions() throws IOException {
        String file = System.getProperty("conformance.exclusions");
        Set<String> ids = new TreeSet<>();
        if (file != null && Files.exists(Path.of(file))) {
            for (String line : Files.readAllLines(Path.of(file))) {
                String l = line.strip();
                if (!l.isEmpty() && !l.startsWith("#")) {
                    ids.add(l.split("\\s+\\|")[0].strip());
                }
            }
        }
        return ids;
    }

    private static Document parse(Path file) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return f.newDocumentBuilder().parse(file.toFile());
    }

    private static List<Element> children(Element parent, String localName) {
        List<Element> list = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element e && (localName == null || localName.equals(e.getLocalName()))) {
                list.add(e);
            }
        }
        return list;
    }

    private static Element child(Element parent, String localName) {
        List<Element> list = children(parent, localName);
        return list.isEmpty() ? null : list.get(0);
    }

    private static String text(Element e) {
        return e == null ? null : e.getTextContent().trim();
    }

    private static void write(Path out, List<Row> rows, Map<String, Integer> models, Map<String, Integer> modelsRead)
            throws IOException {
        StringBuilder csv = new StringBuilder();
        StringBuilder json = new StringBuilder("{\n  \"suite\": \"dmn-tck\",\n  \"levels\": {");
        boolean firstLevel = true;
        for (String level : models.keySet()) {
            long pass = rows.stream().filter(r -> r.level.equals(level) && r.status.equals("PASS")).count();
            long fail = rows.stream().filter(r -> r.level.equals(level) && r.status.equals("FAIL")).count();
            long excl = rows.stream().filter(r -> r.level.equals(level) && r.status.equals("EXCLUDED")).count();
            json.append(firstLevel ? "\n" : ",\n").append("    ").append(q(level)).append(": {\"models\": ")
                    .append(models.get(level)).append(", \"modelsRead\": ").append(modelsRead.get(level))
                    .append(", \"cases\": ").append(pass + fail).append(", \"passed\": ").append(pass)
                    .append(", \"excluded\": ").append(excl).append("}");
            firstLevel = false;
        }
        json.append("\n  },\n  \"results\": [");
        boolean first = true;
        for (Row r : rows) {
            json.append(first ? "\n" : ",\n").append("    {\"level\": ").append(q(r.level)).append(", \"folder\": ")
                    .append(q(r.folder)).append(", \"file\": ").append(q(r.file)).append(", \"case\": ")
                    .append(q(r.caseId)).append(", \"status\": ").append(q(r.status)).append(", \"reason\": ")
                    .append(q(r.reason)).append("}");
            first = false;
            if (!r.status.equals("EXCLUDED")) {
                csv.append('"').append(r.level).append('/').append(r.folder).append("\",\"").append(r.file)
                        .append("\",\"").append(r.caseId).append("\",\"")
                        .append(r.status.equals("PASS") ? "SUCCESS" : "ERROR").append("\",\"")
                        .append(r.status.equals("PASS") ? "" : r.reason.replace("\"", "'")).append("\"\n");
            }
        }
        json.append("\n  ]\n}\n");
        Files.writeString(out.resolve("dmn-tck-results.json"), json, StandardCharsets.UTF_8);
        Files.writeString(out.resolve("tck_results.csv"), csv, StandardCharsets.UTF_8);
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
