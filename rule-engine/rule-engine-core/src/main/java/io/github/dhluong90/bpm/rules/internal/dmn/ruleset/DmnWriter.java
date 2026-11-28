package io.github.dhluong90.bpm.rules.internal.dmn.ruleset;

import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Writes a stored schema version 1 rule set as a DMN 1.5 document: one {@code definitions} named after the rule set,
 * one {@code inputData} per input, one {@code decision} with a {@code decisionTable} (standards-compliance.md 3.6).
 * The output is deterministic, so exporting the same version twice gives the same bytes, and reading it back with
 * {@link DmnReader} gives the same canonical document whenever the version was itself imported from DMN or written
 * in the normal forms of {@link V1Forms}. What DMN cannot express in a decision table ({@code any} and {@code all}
 * groups, output defaults, collect aggregates, calculated outputs) is refused with the rule and cell it is in.
 */
public final class DmnWriter {

    private DmnWriter() {
    }

    /**
     * Writes the DMN document of a canonical rule set document.
     *
     * @param canonicalJson the stored canonical JSON
     * @return the DMN XML text, UTF-8
     * @throws ValidationFailedException naming the part that has no DMN decision table form
     */
    public static String write(String canonicalJson) {
        JsonNode doc = EngineJson.JSON.readTree(canonicalJson);
        String name = doc.get("ruleSet").stringValue();
        List<JsonNode> inputs = list(doc.get("inputs"));
        List<JsonNode> outputs = list(doc.get("outputs"));
        Map<String, Integer> slots = new HashMap<>();
        for (int i = 0; i < inputs.size(); i++) {
            slots.put(inputs.get(i).get("name").stringValue(), i);
        }

        Xml out = new Xml();
        out.line(0, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        StringBuilder root = new StringBuilder("<definitions xmlns=\"" + Dmn.MODEL_15 + "\" xmlns:horus=\""
                + Dmn.HORUS_NAMESPACE + "\" id=\"definitions_" + name + "\" name=\"" + name
                + "\" namespace=\"urn:horus-bpm:rule-set:" + name + "\"");
        if (doc.has("timeZone")) {
            root.append(" horus:timeZone=\"").append(Xml.escape(doc.get("timeZone").stringValue())).append('"');
        }
        if (doc.has("moneyRounding")) {
            root.append(" horus:moneyRounding=\"").append(Xml.escape(doc.get("moneyRounding").stringValue())).append('"');
        }
        out.line(0, root + ">");

        out.line(1, "<decision id=\"" + name + "\" name=\"" + name + "\">");
        if (doc.has("description")) {
            out.element(2, "description", doc.get("description").stringValue());
        }
        writeExplain(out, 2, "noMatch", doc.path("explanations").path("noMatch"));
        for (JsonNode input : inputs) {
            String inputName = input.get("name").stringValue();
            out.line(2, "<informationRequirement id=\"requirement_" + inputName + "\">");
            out.line(3, "<requiredInput href=\"#inputData_" + inputName + "\"/>");
            out.line(2, "</informationRequirement>");
        }
        String hitPolicy = switch (doc.get("hitPolicy").stringValue()) {
            case "first" -> "FIRST";
            case "unique" -> "UNIQUE";
            default -> "COLLECT";
        };
        String aggregation = aggregation(outputs, hitPolicy);
        out.line(2, "<decisionTable id=\"decisionTable_" + name + "\" hitPolicy=\"" + hitPolicy + "\""
                + (aggregation == null ? "" : " aggregation=\"" + aggregation + "\"") + ">");
        for (JsonNode input : inputs) {
            writeInput(out, input);
        }
        for (JsonNode output : outputs) {
            writeOutput(out, output, hitPolicy.equals("COLLECT"));
        }
        for (JsonNode rule : list(doc.get("rules"))) {
            writeRule(out, rule, inputs, outputs, slots);
        }
        out.line(2, "</decisionTable>");
        out.line(1, "</decision>");
        for (JsonNode input : inputs) {
            String inputName = input.get("name").stringValue();
            String type = input.get("type").stringValue();
            out.line(1, "<inputData id=\"inputData_" + inputName + "\" name=\"" + inputName + "\">");
            out.line(2, "<variable id=\"variable_" + inputName + "\" name=\"" + inputName + "\" typeRef=\""
                    + Xml.escape(typeRef(type, "input " + inputName)) + "\"/>");
            out.line(1, "</inputData>");
        }
        out.line(0, "</definitions>");
        return out.toString();
    }

    private static void writeInput(Xml out, JsonNode input) {
        String name = input.get("name").stringValue();
        String type = input.get("type").stringValue();
        StringBuilder start = new StringBuilder("<input id=\"input_" + name + "\" label=\""
                + Xml.escape(label(input, name)) + "\"");
        horusType(start, type);
        if (input.has("mask")) {
            start.append(" horus:mask=\"").append(input.get("mask").booleanValue()).append('"');
        }
        out.line(3, start + ">");
        if (input.has("description")) {
            out.element(4, "description", input.get("description").stringValue());
        }
        out.line(4, "<inputExpression id=\"inputExpression_" + name + "\" typeRef=\""
                + Xml.escape(typeRef(type, "input " + name)) + "\">");
        out.element(5, "text", name);
        out.line(4, "</inputExpression>");
        if (input.has("allowed")) {
            List<String> values = new ArrayList<>();
            for (JsonNode value : input.get("allowed")) {
                values.add(value.isString() ? Feel.print(new Feel.Value.Str(value.stringValue()))
                        : value.decimalValue().toPlainString());
            }
            out.line(4, "<inputValues id=\"inputValues_" + name + "\">");
            out.element(5, "text", String.join(", ", values));
            out.line(4, "</inputValues>");
        }
        out.line(3, "</input>");
    }

    /**
     * The DMN aggregation of a COLLECT table (RLE-227). DMN has one per table and only with one output, so a table
     * with an aggregate on one of several outputs, or on more than one, has no DMN form and is refused.
     */
    private static String aggregation(List<JsonNode> outputs, String hitPolicy) {
        List<JsonNode> aggregated = outputs.stream().filter(o -> o.has("collect")).toList();
        if (aggregated.isEmpty()) {
            return null;
        }
        if (outputs.size() != 1) {
            throw unsupported("Output " + aggregated.get(0).get("name").stringValue() + " has collect: "
                    + aggregated.get(0).get("collect").stringValue() + " in a table of " + outputs.size()
                    + " outputs; DMN has one aggregation per table and only for one output");
        }
        return aggregated.get(0).get("collect").stringValue().toUpperCase(java.util.Locale.ROOT);
    }

    private static void writeOutput(Xml out, JsonNode output, boolean collect) {
        String name = output.get("name").stringValue();
        String type = output.get("type").stringValue();
        if (collect && type.startsWith("list<") && type.endsWith(">")) {
            type = type.substring(5, type.length() - 1); // DMN declares the type of one rule's output
        }
        if (output.has("default")) {
            throw unsupported("Output " + name + " has a default; DMN has no per-rule output default."
                    + " Write the value in every rule, then export");
        }
        StringBuilder start = new StringBuilder("<output id=\"output_" + name + "\" name=\"" + name + "\" label=\""
                + Xml.escape(label(output, name)) + "\" typeRef=\"" + Xml.escape(typeRef(type, "output " + name)) + "\"");
        horusType(start, type);
        if (output.has("description")) {
            out.line(3, start + ">");
            out.element(4, "description", output.get("description").stringValue());
            out.line(3, "</output>");
        } else {
            out.line(3, start + "/>");
        }
    }

    private static void writeRule(Xml out, JsonNode rule, List<JsonNode> inputs, List<JsonNode> outputs,
            Map<String, Integer> slots) {
        String id = rule.get("id").stringValue();
        JsonNode when = rule.get("when");
        if (when.has("any") || when.has("all")) {
            throw unsupported("Rule " + id + " uses " + (when.has("any") ? "any" : "all")
                    + ", which has no decision table form; split it into rows, then export");
        }
        String description = rule.has("description") ? rule.get("description").stringValue() : null;
        // a rule id mapped at import (RLE-224) goes back out as the id of the file
        if (description != null) {
            var marker = java.util.regex.Pattern.compile("(?:^|\\s)\\(DMN id ([A-Za-z0-9_.-]{1,200})\\)$").matcher(description);
            if (marker.find() && DmnReader.mapRuleId(marker.group(1)).filter(rule.get("id").stringValue()::equals).isPresent()) {
                id = marker.group(1);
                description = description.substring(0, marker.start()).strip();
                description = description.isEmpty() ? null : description;
            }
        }
        out.line(3, "<rule id=\"" + Xml.escape(id) + "\">");
        if (description != null) {
            out.element(4, "description", description);
        }
        writeExplain(out, 4, "explain", rule.path("explain"));
        for (JsonNode input : inputs) {
            String inputName = input.get("name").stringValue();
            String feel;
            if (when.has(inputName)) {
                String text = when.get(inputName).stringValue();
                try {
                    feel = Feel.print(V1Forms.fromCondition(inputName, text, n -> slots.getOrDefault(n, -1)));
                } catch (Feel.UnsupportedException e) {
                    throw unsupported("Rule " + id + ", input " + inputName + ": " + e.getMessage());
                }
            } else {
                feel = "-";
            }
            out.line(4, "<inputEntry id=\"" + Xml.escape(id) + "_" + inputName + "\">");
            out.element(5, "text", feel);
            out.line(4, "</inputEntry>");
        }
        JsonNode then = rule.get("then");
        for (JsonNode output : outputs) {
            String outputName = output.get("name").stringValue();
            JsonNode cell = then.get(outputName);
            String feel;
            if (cell == null || cell.isNull()) {
                feel = "null";
            } else if (cell.isBoolean()) {
                feel = Boolean.toString(cell.booleanValue());
            } else if (cell.isNumber()) {
                feel = cell.decimalValue().toPlainString();
            } else {
                try {
                    feel = Feel.print(V1Forms.literal(cell.stringValue()));
                } catch (Feel.UnsupportedException e) {
                    throw unsupported("Rule " + id + ", output " + outputName + ": " + e.getMessage()
                            + "; calculated outputs are exported with full FEEL in stage S2 (release 1.2)");
                }
            }
            out.line(4, "<outputEntry id=\"" + Xml.escape(id) + "_" + outputName + "\">");
            out.element(5, "text", feel);
            out.line(4, "</outputEntry>");
        }
        out.line(3, "</rule>");
    }

    private static String label(JsonNode column, String name) {
        return column.has("label") ? column.get("label").stringValue() : name;
    }

    /** One {@code <horus:...>} element per language, in the extension namespace, inside {@code extensionElements}. */
    private static void writeExplain(Xml out, int depth, String element, JsonNode texts) {
        if (!texts.isObject() || texts.isEmpty()) {
            return;
        }
        out.line(depth, "<extensionElements>");
        for (var entry : texts.properties()) {
            out.line(depth + 1, "<horus:" + element + " horus:lang=\"" + Xml.escape(entry.getKey()) + "\">"
                    + Xml.escapeText(entry.getValue().stringValue()) + "</horus:" + element + ">");
        }
        out.line(depth, "</extensionElements>");
    }

    private static void horusType(StringBuilder start, String type) {
        if (!Dmn.typeRefIsEnough(type)) {
            start.append(" horus:type=\"").append(Xml.escape(type)).append('"');
        }
    }

    private static String typeRef(String type, String where) {
        return Dmn.typeRefOf(type).orElseThrow(() -> unsupported("The " + where + " has type " + type
                + ", which has no DMN form"));
    }

    private static List<JsonNode> list(JsonNode array) {
        List<JsonNode> items = new ArrayList<>();
        array.forEach(items::add);
        return items;
    }

    private static ValidationFailedException unsupported(String detail) {
        return new ValidationFailedException("Cannot export as DMN: " + detail);
    }

    /** A tiny indented XML printer; attribute and text values are escaped by the callers through {@link #escape}. */
    private static final class Xml {
        private final StringBuilder out = new StringBuilder();

        void line(int depth, String text) {
            out.append("  ".repeat(depth)).append(text).append('\n');
        }

        void element(int depth, String name, String text) {
            line(depth, "<" + name + ">" + escapeText(text) + "</" + name + ">");
        }

        static String escapeText(String text) {
            return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\r", "&#13;");
        }

        static String escape(String text) {
            StringBuilder escaped = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case '&' -> escaped.append("&amp;");
                    case '<' -> escaped.append("&lt;");
                    case '>' -> escaped.append("&gt;");
                    case '"' -> escaped.append("&quot;");
                    case '\r' -> escaped.append("&#13;");
                    default -> escaped.append(c);
                }
            }
            return escaped.toString();
        }

        @Override
        public String toString() {
            return out.toString();
        }
    }
}
