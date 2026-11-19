package io.github.dhluong90.bpm.rules.internal.dmn.ruleset;

import com.horus.bpm.rules.api.ValidationFailedException;
import com.horus.bpm.rules.internal.json.EngineJson;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads a DMN 1.2 to 1.5 document holding one decision table into a schema version 1 rule set document, the
 * lowest schema version that can express it (TR-STD-03). The result goes through the same reader, checks and
 * hash as a YAML import. Every element, attribute value or FEEL construct the release cannot execute is refused
 * by name with its line and, where known, the stage that adds it (TR-STD-04); nothing is dropped silently. DMN
 * diagram interchange (DMNDI), elements in foreign namespaces and DRD wiring ({@code inputData},
 * {@code informationRequirement}) carry no decision logic and are kept only in the stored source document.
 */
public final class DmnReader {

    private static final Pattern KEBAB = Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*$");
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Pattern RULE_ID = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,63}$");

    /** Elements of {@code definitions} refused with the stage that adds them (standards-compliance.md 6.2). */
    private static final Map<String, String> LATER = Map.ofEntries(
            Map.entry("itemDefinition", "S1 (release 1.1)"),
            Map.entry("knowledgeSource", "S1 (release 1.1)"),
            Map.entry("textAnnotation", "S1 (release 1.1)"),
            Map.entry("association", "S1 (release 1.1)"),
            Map.entry("group", "S1 (release 1.1)"),
            Map.entry("businessKnowledgeModel", "S2 (release 1.2)"),
            Map.entry("decisionService", "S2 (release 1.2)"),
            Map.entry("import", "S3 (release 1.3)"),
            Map.entry("knowledgeRequirement", "S2 (release 1.2)"),
            Map.entry("authorityRequirement", "S1 (release 1.1)"),
            Map.entry("literalExpression", "S1 (release 1.1)"),
            Map.entry("context", "S2 (release 1.2)"),
            Map.entry("invocation", "S2 (release 1.2)"),
            Map.entry("relation", "S2 (release 1.2)"),
            Map.entry("list", "S2 (release 1.2)"),
            Map.entry("functionDefinition", "S2 (release 1.2)"),
            Map.entry("conditional", "S2 (release 1.2)"),
            Map.entry("for", "S2 (release 1.2)"),
            Map.entry("every", "S2 (release 1.2)"),
            Map.entry("some", "S2 (release 1.2)"),
            Map.entry("filter", "S2 (release 1.2)"),
            Map.entry("outputValues", "S1 (release 1.1)"),
            Map.entry("defaultOutputEntry", "S1 (release 1.1)"));

    private DmnReader() {
    }

    /**
     * Reads a DMN document.
     *
     * @param source the XML bytes, already bounded in size by the caller
     * @return the schema version 1 rule set document
     * @throws ValidationFailedException naming the element, cell or construct that cannot be imported
     */
    public static ObjectNode toRuleSet(byte[] source) {
        XmlTree root = XmlTree.parse(source);
        String ns = root.namespace();
        if (!Dmn.MODEL_NAMESPACES.contains(ns) || !root.name().equals("definitions")) {
            throw invalid("The root element must be a DMN 1.2 to 1.5 <definitions>; found <" + Quote.text(root.name())
                    + "> in namespace " + Quote.of(ns), root);
        }
        String feel = Dmn.feelNamespaceOf(ns);
        requireFeel(root, "expressionLanguage", feel);
        requireFeel(root, "typeLanguage", feel);

        XmlTree decision = null;
        for (XmlTree child : root.children()) {
            if (!child.namespace().equals(ns)) {
                continue; // DMNDI and vendor elements: no decision logic, kept in the source document
            }
            switch (child.name()) {
                case "decision" -> {
                    if (decision != null) {
                        throw invalid("More than one <decision>: decision requirement graphs arrive in stage"
                                + " S1 (release 1.1); import one decision table per file", child);
                    }
                    decision = child;
                }
                case "inputData", "description" -> {
                    // DRD wiring and documentation; the decision table names its inputs itself
                }
                case "extensionElements" -> {
                    // vendor extensions: never read or executed, kept in the source document
                }
                default -> throw refused(child);
            }
        }
        if (decision == null) {
            throw invalid("The document has no <decision>", root);
        }

        ObjectNode doc = EngineJson.NODES.objectNode();
        doc.put("schemaVersion", 1);
        doc.put("ruleSet", ruleSetName(root, decision));
        Optional<String> description = description(decision).or(() -> description(root));
        description.ifPresent(d -> doc.put("description", d));
        root.attribute(Dmn.HORUS_NAMESPACE, "timeZone").ifPresent(zone -> doc.put("timeZone", zone));
        root.attribute(Dmn.HORUS_NAMESPACE, "moneyRounding").ifPresent(r -> doc.put("moneyRounding", r));

        XmlTree table = null;
        for (XmlTree child : decision.children()) {
            if (!child.namespace().equals(ns)) {
                continue;
            }
            switch (child.name()) {
                case "decisionTable" -> table = child;
                case "description", "variable", "informationRequirement", "question", "allowedAnswers" -> {
                    // documentation and DRD wiring
                }
                case "extensionElements" -> {
                    // vendor extensions: never read or executed, kept in the source document
                }
                default -> throw refused(child);
            }
        }
        if (table == null) {
            throw invalid("Decision " + Quote.of(decision.attribute("id").orElse("")) + " has no <decisionTable>", decision);
        }
        readTable(table, feel, doc);
        return doc;
    }

    private static void readTable(XmlTree table, String feel, ObjectNode doc) {
        String hitPolicy = table.attribute("hitPolicy").orElse("UNIQUE");
        if (table.attribute("aggregation").isPresent()) {
            throw invalid("Hit policy COLLECT with aggregation " + Quote.text(table.attribute("aggregation").get())
                    + " arrives in stage S1 (release 1.1)", table);
        }
        doc.put("hitPolicy", switch (hitPolicy) {
            case "FIRST" -> "first";
            case "UNIQUE" -> "unique";
            case "COLLECT" -> "collect";
            default -> throw invalid("Hit policy " + Quote.text(hitPolicy) + " arrives in stage S1 (release 1.1);"
                    + " this release reads FIRST, UNIQUE and COLLECT", table);
        });
        if ("CrossTable".equals(table.attribute("preferredOrientation").orElse(""))) {
            throw invalid("Cross-table decision tables are not supported; use Rule-as-Row", table);
        }

        ArrayNode inputs = doc.putArray("inputs");
        ArrayNode outputs = doc.putArray("outputs");
        ArrayNode rules = doc.putArray("rules");
        List<String> inputNames = new java.util.ArrayList<>();
        List<String> outputNames = new java.util.ArrayList<>();
        int annotations = 0;
        for (XmlTree child : table.children()) {
            if (!child.namespace().equals(table.namespace())) {
                continue;
            }
            switch (child.name()) {
                case "input" -> inputNames.add(readInput(child, feel, inputs));
                case "output" -> outputNames.add(readOutput(child, outputs));
                case "annotation" -> annotations++;
                case "rule", "description" -> {
                    // rules below, once every column is known
                }
                case "extensionElements" -> {
                    // vendor extensions: never read or executed, kept in the source document
                }
                default -> throw refused(child);
            }
        }
        if (outputNames.isEmpty()) {
            throw invalid("The decision table has no <output>", table);
        }
        Set<String> ruleIds = new HashSet<>();
        int position = 0;
        for (XmlTree rule : table.children("rule")) {
            position++;
            rules.add(readRule(rule, position, feel, inputNames, outputNames, annotations, ruleIds));
        }
        if (rules.isEmpty()) {
            throw invalid("The decision table has no <rule>", table);
        }
        // A DMN COLLECT table declares the type of one rule's output; the v1 output is the list of them.
        if ("collect".equals(doc.get("hitPolicy").stringValue())) {
            for (tools.jackson.databind.JsonNode output : outputs) {
                String type = output.get("type").stringValue();
                if (!type.startsWith("list<")) {
                    ((ObjectNode) output).put("type", "list<" + type + ">");
                }
            }
        }
    }

    private static String readInput(XmlTree input, String feel, ArrayNode inputs) {
        XmlTree expression = input.child("inputExpression")
                .orElseThrow(() -> invalid("Input column has no <inputExpression>", input));
        requireFeel(expression, "expressionLanguage", feel);
        String name = text(expression).strip();
        if (!IDENTIFIER.matcher(name).matches() || name.length() > 64) {
            throw invalid("Input expression " + Quote.of(name) + " must be a plain input name such as txCount;"
                    + " paths and calculations arrive with full FEEL in stage S2 (release 1.2)", expression);
        }
        ObjectNode node = inputs.addObject();
        node.put("name", name);
        node.put("type", type(expression.attribute("typeRef"), input.attribute(Dmn.HORUS_NAMESPACE, "type"),
                "input '" + name + "'", expression));
        columnDescription(input, name).ifPresent(d -> node.put("description", d));
        Optional<XmlTree> values = input.child("inputValues");
        if (values.isPresent()) {
            ArrayNode allowed = node.putArray("allowed");
            Feel.UnaryTests tests;
            try {
                tests = Feel.parseUnaryTests(text(values.get()));
            } catch (Feel.UnsupportedException e) {
                throw invalid("Allowed values of input '" + name + "': " + e.getMessage(), values.get());
            }
            for (Feel.Test test : tests.tests()) {
                if (tests.negated() || !(test instanceof Feel.Test.Compare(Feel.Op op, Feel.Value value))
                        || op != Feel.Op.EQ) {
                    throw invalid("Allowed values of input '" + name + "' must be a list of strings or integers",
                            values.get());
                }
                switch (value) {
                    case Feel.Value.Str(String s) -> allowed.add(s);
                    case Feel.Value.Num(BigDecimal n) when n.stripTrailingZeros().scale() <= 0 -> allowed.add(n);
                    default -> throw invalid("Allowed values of input '" + name
                            + "' must be a list of strings or integers", values.get());
                }
            }
        }
        input.attribute(Dmn.HORUS_NAMESPACE, "mask").ifPresent(mask -> {
            if (!mask.equals("true") && !mask.equals("false")) {
                throw invalid("horus:mask of input '" + name + "' must be true or false", input);
            }
            node.put("mask", Boolean.parseBoolean(mask));
        });
        return name;
    }

    private static String readOutput(XmlTree output, ArrayNode outputs) {
        String name = output.attribute("name")
                .orElseThrow(() -> invalid("Every output column needs a name attribute, such as name=\"fee\"", output));
        if (!IDENTIFIER.matcher(name).matches() || name.length() > 64) {
            throw invalid("Output name " + Quote.of(name) + " must be an identifier such as fee", output);
        }
        for (XmlTree child : output.children()) {
            if (child.namespace().equals(output.namespace()) && !child.name().equals("description")
                    && !child.name().equals("extensionElements")) {
                throw refused(child);
            }
        }
        ObjectNode node = outputs.addObject();
        node.put("name", name);
        node.put("type", type(output.attribute("typeRef"), output.attribute(Dmn.HORUS_NAMESPACE, "type"),
                "output '" + name + "'", output));
        columnDescription(output, name).ifPresent(d -> node.put("description", d));
        return name;
    }

    private static ObjectNode readRule(XmlTree rule, int position, String feel, List<String> inputNames,
            List<String> outputNames, int annotations, Set<String> ruleIds) {
        String id = rule.attribute("id").orElse("R" + position);
        if (!RULE_ID.matcher(id).matches()) {
            throw invalid("Rule id " + Quote.of(id) + " must start with a letter and use letters, digits, '-' or '_'"
                    + " (at most 64 characters)", rule);
        }
        if (!ruleIds.add(id)) {
            throw invalid("Duplicate rule id '" + id + "'", rule);
        }
        List<XmlTree> inputEntries = rule.children("inputEntry");
        List<XmlTree> outputEntries = rule.children("outputEntry");
        if (inputEntries.size() != inputNames.size() || outputEntries.size() != outputNames.size()) {
            throw invalid("Rule " + id + " has " + inputEntries.size() + " input and " + outputEntries.size()
                    + " output entries; the table has " + inputNames.size() + " inputs and " + outputNames.size()
                    + " outputs", rule);
        }
        ObjectNode node = EngineJson.NODES.objectNode();
        node.put("id", id);
        Optional<String> description = description(rule);
        List<XmlTree> annotationEntries = rule.children("annotationEntry");
        if (annotationEntries.size() > annotations) {
            throw invalid("Rule " + id + " has more annotation entries than the table has annotation columns", rule);
        }
        for (XmlTree entry : annotationEntries) {
            String text = entry.child("text").map(XmlTree::text).orElse("").strip();
            if (text.isEmpty()) {
                continue;
            }
            if (description.isPresent()) {
                throw invalid("Rule " + id + " has a description and an annotation, or several annotations;"
                        + " keep one, it becomes the rule description", entry);
            }
            description = Optional.of(text);
        }
        description.ifPresent(d -> node.put("description", d));

        ObjectNode when = node.putObject("when");
        for (int i = 0; i < inputEntries.size(); i++) {
            XmlTree entry = inputEntries.get(i);
            requireFeel(entry, "expressionLanguage", feel);
            String input = inputNames.get(i);
            String text = text(entry);
            refuseBidi(text, "Rule " + id + ", input " + input, entry);
            Feel.UnaryTests tests;
            try {
                tests = Feel.parseUnaryTests(text.isBlank() ? "-" : text);
            } catch (Feel.UnsupportedException e) {
                throw invalid("Rule " + id + ", input " + input + ": " + Quote.of(text.strip()) + ": " + e.getMessage(), entry);
            }
            if (!tests.any()) {
                when.put(input, V1Forms.condition(input, tests));
            }
        }
        ObjectNode then = node.putObject("then");
        for (int i = 0; i < outputEntries.size(); i++) {
            XmlTree entry = outputEntries.get(i);
            requireFeel(entry, "expressionLanguage", feel);
            String output = outputNames.get(i);
            String text = text(entry).strip();
            refuseBidi(text, "Rule " + id + ", output " + output, entry);
            if (text.isEmpty()) {
                throw invalid("Rule " + id + ", output " + output + ": the output entry is empty; write null"
                        + " for no value", entry);
            }
            Feel.Value value;
            try {
                value = Feel.parseLiteral(text);
            } catch (Feel.UnsupportedException e) {
                throw invalid("Rule " + id + ", output " + output + ": " + Quote.of(text) + ": " + e.getMessage()
                        + "; output entries hold literal values before full FEEL", entry);
            }
            switch (value) {
                case Feel.Value.Null() -> then.putNull(output);
                case Feel.Value.Bool(boolean b) -> then.put(output, b);
                case Feel.Value.Num(BigDecimal n) when CanonicalCells.isJsonInteger(n) -> then.put(output, n.setScale(0));
                case Feel.Value.Num(BigDecimal n) -> then.put(output, n.toPlainString());
                default -> then.put(output, V1Forms.v1(value));
            }
        }
        return node;
    }

    private static String ruleSetName(XmlTree definitions, XmlTree decision) {
        for (Optional<String> candidate : List.of(decision.attribute("id"), decision.attribute("name"),
                definitions.attribute("name"), definitions.attribute("id"))) {
            if (candidate.isPresent() && KEBAB.matcher(candidate.get()).matches() && candidate.get().length() <= 100) {
                return candidate.get();
            }
        }
        throw invalid("The rule set name is taken from the decision id; give the decision a kebab-case id such as"
                + " card-fee (found id " + Quote.of(decision.attribute("id").orElse("")) + ", name "
                + Quote.of(decision.attribute("name").orElse("")) + ")", decision);
    }

    /**
     * Maps a DMN type reference to a v1 type. {@code horus:type} carries the v1 type that FEEL has no name for
     * (integer, money, lists) and must agree with {@code typeRef}.
     */
    private static String type(Optional<String> typeRef, Optional<String> horusType, String where, XmlTree element) {
        String ref = typeRef.map(t -> t.strip().replaceFirst("^feel:", "")).orElseThrow(() ->
                invalid("The " + where + " needs a typeRef such as string, number, boolean or date", element));
        if (horusType.isPresent()) {
            String declared = horusType.get();
            if (!Dmn.typeRefOf(declared).map(ref::equals).orElse(false)) {
                throw invalid("horus:type " + Quote.of(declared) + " of " + where + " does not match typeRef " + Quote.of(ref),
                        element);
            }
            return declared;
        }
        return switch (ref.toLowerCase(Locale.ROOT)) {
            case "string" -> "string";
            case "number", "double", "decimal" -> "decimal";
            case "integer", "long", "int" -> "integer";
            case "boolean" -> "boolean";
            case "date" -> "date";
            case "date and time", "datetime" -> "datetime";
            default -> throw invalid("typeRef " + Quote.of(ref) + " of " + where + " is not supported; item definitions"
                    + " arrive in stage S1 (release 1.1)", element);
        };
    }

    private static Optional<String> columnDescription(XmlTree column, String name) {
        Optional<String> description = description(column);
        if (description.isPresent()) {
            return description;
        }
        // A label other than the name is kept as the description, so no author text is lost.
        return column.attribute("label").map(String::strip).filter(l -> !l.isEmpty() && !l.equals(name));
    }

    private static Optional<String> description(XmlTree element) {
        return element.child("description").map(d -> d.text().strip()).filter(d -> !d.isEmpty());
    }

    private static String text(XmlTree element) {
        return element.child("text").map(XmlTree::text).orElse("");
    }

    private static void requireFeel(XmlTree element, String attribute, String feel) {
        element.attribute(attribute).ifPresent(language -> {
            if (!language.equals(feel) && !Dmn.FEEL_NAMESPACES.contains(language)) {
                throw invalid("<" + Quote.text(element.name()) + "> " + attribute + " " + Quote.of(language) + " is not FEEL;"
                        + " only FEEL is supported", element);
            }
        });
    }

    private static void refuseBidi(String text, String where, XmlTree element) {
        try {
            CanonicalCells.refuseBidi(text, where);
        } catch (ValidationFailedException e) {
            throw invalid(e.getMessage(), element);
        }
    }

    private static ValidationFailedException refused(XmlTree element) {
        String id = element.attribute("id").map(i -> " id=\"" + Quote.text(i) + "\"").orElse("");
        String stage = LATER.get(element.name());
        return invalid("<" + Quote.text(element.name()) + id + "> is not supported in this release"
                + (stage == null ? "" : "; it arrives in stage " + stage), element);
    }

    private static ValidationFailedException invalid(String detail, XmlTree element) {
        return new ValidationFailedException(detail + " (line " + element.line() + ")");
    }
}
