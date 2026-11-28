package io.github.dhluong90.bpm.rules.internal.dmn.ruleset;

import io.github.dhluong90.bpm.rules.api.UnsupportedElement;
import io.github.dhluong90.bpm.rules.api.UnsupportedStandardException;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.api.Violation;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import java.math.BigDecimal;
import java.util.HashMap;
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
 * diagram interchange (DMNDI), elements in foreign namespaces and DRD wiring to input data ({@code inputData},
 * an {@code informationRequirement} that names {@code requiredInput}) carry no decision logic and are kept only in
 * the stored source document. A CMMN root is refused before anything else is read (RLE-060).
 */
public final class DmnReader {

    private static final Pattern KEBAB = Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*$");
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Pattern RULE_ID = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,63}$");

    private static final String CMMN_NAMESPACE_PREFIX = "http://www.omg.org/spec/CMMN/";

    /**
     * Elements refused with the stage of the coverage table (standards-compliance.md 6.2) that adds them. An
     * element not listed is {@code not planned}. A refusal names the stage and never a release or a date.
     */
    private static final Map<String, String> STAGES = Map.ofEntries(
            Map.entry("itemDefinition", "S1"),
            Map.entry("knowledgeSource", "S1"),
            Map.entry("textAnnotation", "S1"),
            Map.entry("association", "S1"),
            Map.entry("group", "S1"),
            Map.entry("authorityRequirement", "S1"),
            Map.entry("literalExpression", "S1"),
            Map.entry("outputValues", "S1"),
            Map.entry("defaultOutputEntry", "S1"),
            Map.entry("businessKnowledgeModel", "S2"),
            Map.entry("decisionService", "S2"),
            Map.entry("knowledgeRequirement", "S2"),
            Map.entry("context", "S2"),
            Map.entry("invocation", "S2"),
            Map.entry("relation", "S2"),
            Map.entry("list", "S2"),
            Map.entry("functionDefinition", "S2"),
            Map.entry("conditional", "S2"),
            Map.entry("for", "S2"),
            Map.entry("every", "S2"),
            Map.entry("some", "S2"),
            Map.entry("filter", "S2"),
            Map.entry("import", "S3"));

    /** Children of a {@code decision} that carry no logic or are read. */
    private static final Set<String> DECISION_CHILDREN = Set.of("decisionTable", "description", "variable",
            "informationRequirement", "question", "allowedAnswers", "extensionElements");

    /** Children of a {@code decisionTable} that are read or are documentation. */
    private static final Set<String> TABLE_CHILDREN = Set.of("input", "output", "annotation", "rule", "description",
            "extensionElements");

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
        // CMMN is refused at the root, before the rest of the document is read (RLE-060). A DTD or an entity is
        // not a root: the full parse below refuses it first.
        XmlTree.peekRoot(source).filter(r -> r.namespace().startsWith(CMMN_NAMESPACE_PREFIX)).ifPresent(r -> {
            throw UnsupportedStandardException.cmmn(Quote.text(r.namespace()), r.line());
        });
        XmlTree root = XmlTree.parse(source);
        String ns = root.namespace();
        if (!Dmn.MODEL_NAMESPACES.contains(ns) || !root.name().equals("definitions")) {
            throw invalid("The root element must be a DMN 1.2 to 1.5 <definitions>; found <" + Quote.text(root.name())
                    + "> in namespace " + Quote.of(ns), root);
        }
        refuseDevelopmentNamespace(root);
        String feel = Dmn.feelNamespaceOf(ns);
        requireFeel(root, "expressionLanguage", feel);
        requireFeel(root, "typeLanguage", feel);
        refuseUnsupportedElements(root, ns);

        XmlTree decision = null;
        for (XmlTree child : root.children()) {
            if (!child.namespace().equals(ns)) {
                continue; // DMNDI and vendor elements: no decision logic, kept in the source document
            }
            switch (child.name()) {
                case "decision" -> {
                    if (decision != null) {
                        throw invalid("More than one <decision>: decision requirement graphs arrive in stage"
                                + " S1; import one decision table per file", child);
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
        horus(root, "timeZone").ifPresent(zone -> doc.put("timeZone", zone));
        horus(root, "moneyRounding").ifPresent(r -> doc.put("moneyRounding", r));
        refuseErrorModeSpec(root);

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
        ObjectNode noMatch = explainTexts(decision, "noMatch");
        if (!noMatch.isEmpty()) {
            doc.putObject("explanations").set("noMatch", noMatch);
        }
        return doc;
    }

    private static void readTable(XmlTree table, String feel, ObjectNode doc) {
        String hitPolicy = table.attribute("hitPolicy").orElse("UNIQUE");
        if (table.attribute("aggregation").isPresent()) {
            throw invalid("Hit policy COLLECT with aggregation " + Quote.text(table.attribute("aggregation").get())
                    + " arrives in stage S1", table);
        }
        doc.put("hitPolicy", switch (hitPolicy) {
            case "FIRST" -> "first";
            case "UNIQUE" -> "unique";
            case "COLLECT" -> "collect";
            default -> throw invalid("Hit policy " + Quote.text(hitPolicy) + " arrives in stage S1;"
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
        Map<String, String> ruleIds = new HashMap<>();
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
                    + " paths and calculations arrive with full FEEL in stage S2", expression);
        }
        ObjectNode node = inputs.addObject();
        node.put("name", name);
        node.put("type", type(expression.attribute("typeRef"), horus(input, "type"),
                "input '" + name + "'", expression));
        description(input).ifPresent(d -> node.put("description", d));
        columnLabel(input, name).ifPresent(l -> node.put("label", l));
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
        horus(input, "mask").ifPresent(mask -> {
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
        node.put("type", type(output.attribute("typeRef"), horus(output, "type"),
                "output '" + name + "'", output));
        description(output).ifPresent(d -> node.put("description", d));
        columnLabel(output, name).ifPresent(l -> node.put("label", l));
        return name;
    }

    /** The note an import appends to the description of a rule whose DMN id had to be mapped; export reads it back. */
    static final String ID_MARKER_OPEN = "(DMN id ";

    /** A DMN id a tool may write: letters, digits, underscore, hyphen and dot, up to 200 characters. */
    private static final Pattern DMN_ID = Pattern.compile("^[A-Za-z0-9_.-]{1,200}$");

    /**
     * Maps a DMN rule id to a v1 rule id, deterministically (RLE-082). A valid id is unchanged. Otherwise characters
     * outside letters, digits, underscore and hyphen are removed, {@code r} is put in front when the result does not
     * start with a letter ({@code _5f3a} becomes {@code r_5f3a}) and the result is cut at 64 characters.
     *
     * @param id the id in the file
     * @return the v1 id, or empty when the id is not one a tool writes
     */
    static Optional<String> mapRuleId(String id) {
        if (RULE_ID.matcher(id).matches()) {
            return Optional.of(id);
        }
        if (!DMN_ID.matcher(id).matches()) {
            return Optional.empty();
        }
        String kept = id.replaceAll("[^A-Za-z0-9_-]", "");
        String mapped = Character.isLetter(kept.isEmpty() ? '0' : kept.charAt(0)) ? kept : "r" + kept;
        return Optional.of(mapped.length() > 64 ? mapped.substring(0, 64) : mapped);
    }

    private static ObjectNode readRule(XmlTree rule, int position, String feel, List<String> inputNames,
            List<String> outputNames, int annotations, Map<String, String> ruleIds) {
        String original = rule.attribute("id").orElse("R" + position);
        String id = mapRuleId(original).orElseThrow(() -> invalid("Rule id " + Quote.of(original)
                + " must start with a letter and use letters, digits, '-' or '_' (at most 64 characters)", rule));
        String earlier = ruleIds.put(id, original);
        if (earlier != null && earlier.equals(original)) {
            throw invalid("Duplicate rule id '" + id + "'", rule);
        }
        if (earlier != null) {
            throw new ValidationFailedException(List.of(new Violation(id, null, "Rule ids " + Quote.of(earlier)
                    + " and " + Quote.of(original) + " both become " + Quote.of(id) + " (line " + rule.line()
                    + "); give one of them another id in the modeller", "DMN_RULE_ID_COLLISION", rule.line(), null)));
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
        if (!original.equals(id)) {
            description = Optional.of(description.map(d -> d + " ").orElse("") + ID_MARKER_OPEN + original + ")");
        }
        description.ifPresent(d -> node.put("description", d));
        ObjectNode explain = explainTexts(rule, "explain");
        if (!explain.isEmpty()) {
            node.set("explain", explain);
        }

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
                    + " arrive in stage S1", element);
        };
    }

    /** A label other than the name is the column's display label (#392); one equal to the name says nothing more. */
    private static Optional<String> columnLabel(XmlTree column, String name) {
        return column.attribute("label").map(String::strip).filter(l -> !l.isEmpty() && !l.equals(name));
    }

    /** The {@code <horus:explain horus:lang="en">} sentences in the {@code extensionElements} of an element. */
    private static ObjectNode explainTexts(XmlTree element, String elementName) {
        ObjectNode texts = EngineJson.NODES.objectNode();
        for (XmlTree extension : element.children("extensionElements")) {
            for (XmlTree child : extension.children()) {
                if (child.namespace().equals(Dmn.HORUS_NAMESPACE) && child.name().equals(elementName)) {
                    String lang = child.attribute(Dmn.HORUS_NAMESPACE, "lang").orElseThrow(() ->
                            invalid("<horus:" + elementName + "> needs a horus:lang attribute such as en", child));
                    texts.put(lang, child.text());
                }
            }
        }
        return texts;
    }

    /**
     * {@code horus:errorMode} on the root: {@code strict} is what every imported table evaluates in and is not
     * stored; {@code spec} is refused with the stage that adds it, never read as if absent (RLE-081, PD-125).
     */
    private static void refuseErrorModeSpec(XmlTree root) {
        horus(root, "errorMode").ifPresent(mode -> {
            if ("spec".equals(mode)) {
                throw invalid("horus:errorMode spec is added in stage S1; this release evaluates every imported table"
                        + " in errorMode strict", root);
            }
            if (!"strict".equals(mode)) {
                throw invalid("horus:errorMode must be strict or spec", root);
            }
        });
    }

    /** A {@code horus:} extension attribute in {@code urn:horus-bpm:dmn:extensions:v1}. */
    private static Optional<String> horus(XmlTree element, String local) {
        return element.attribute(Dmn.HORUS_NAMESPACE, local);
    }

    /**
     * Refuses the development spelling of the extension namespace instead of ignoring its attributes, which would
     * silently drop horus:type, horus:mask and the rest (TR-STD-04).
     */
    private static void refuseDevelopmentNamespace(XmlTree element) {
        boolean used = element.namespace().equals(Dmn.HORUS_NAMESPACE_DEV)
                || element.attributes().keySet().stream().anyMatch(k -> k.startsWith("{" + Dmn.HORUS_NAMESPACE_DEV + "}"));
        if (used) {
            throw invalid("The extension namespace " + Dmn.HORUS_NAMESPACE_DEV + " is not supported; use "
                    + Dmn.HORUS_NAMESPACE, element);
        }
        element.children().forEach(DmnReader::refuseDevelopmentNamespace);
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
                throw UnsupportedStandardException.expressionLanguage(Quote.text(language),
                        new UnsupportedElement(Quote.text(element.name()), element.attribute("id").orElse(null),
                                element.line(), UnsupportedElement.NOT_PLANNED));
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

    private static UnsupportedStandardException refused(XmlTree element) {
        return UnsupportedStandardException.dmnElements(List.of(entry(element)));
    }

    private static UnsupportedElement entry(XmlTree element) {
        return new UnsupportedElement(Quote.text(element.name()), element.attribute("id").orElse(null), element.line(),
                STAGES.getOrDefault(element.name(), UnsupportedElement.NOT_PLANNED));
    }

    /**
     * Lists every element the release cannot execute, in document order, and refuses the document with all of them
     * at once (RLE-061): a second {@code decision}'s wiring to another decision, models, services, boxed
     * expressions and elements the standard does not define. DRD wiring to input data, documentation, vendor
     * extensions and elements in other namespaces carry no logic and stay in the source document.
     */
    private static void refuseUnsupportedElements(XmlTree root, String ns) {
        List<UnsupportedElement> found = new java.util.ArrayList<>();
        for (XmlTree child : root.children()) {
            if (!child.namespace().equals(ns)) {
                continue;
            }
            switch (child.name()) {
                case "decision" -> scanDecision(child, ns, found);
                case "inputData", "description", "extensionElements" -> {
                    // no decision logic
                }
                default -> found.add(entry(child));
            }
        }
        if (!found.isEmpty()) {
            throw UnsupportedStandardException.dmnElements(found);
        }
    }

    private static void scanDecision(XmlTree decision, String ns, List<UnsupportedElement> found) {
        for (XmlTree child : decision.children()) {
            if (!child.namespace().equals(ns)) {
                continue;
            }
            if (child.name().equals("decisionTable")) {
                for (XmlTree part : child.children()) {
                    if (part.namespace().equals(ns) && !TABLE_CHILDREN.contains(part.name())) {
                        found.add(entry(part));
                    }
                }
            } else if (child.name().equals("informationRequirement")) {
                // A wire to another decision is a decision requirement graph; a wire to input data is not.
                child.children().stream().filter(c -> c.namespace().equals(ns) && c.name().equals("requiredDecision"))
                        .findFirst().ifPresent(c -> found.add(new UnsupportedElement(Quote.text(child.name()),
                                child.attribute("id").or(() -> decision.attribute("id")).orElse(null), child.line(),
                                "S1")));
            } else if (!DECISION_CHILDREN.contains(child.name())) {
                // A boxed expression has no id of its own; it is named by the decision that holds it.
                found.add(new UnsupportedElement(Quote.text(child.name()),
                        child.attribute("id").or(() -> decision.attribute("id")).orElse(null), child.line(),
                        STAGES.getOrDefault(child.name(), UnsupportedElement.NOT_PLANNED)));
            }
        }
    }

    private static ValidationFailedException invalid(String detail, XmlTree element) {
        return new ValidationFailedException(detail + " (line " + element.line() + ")");
    }
}
