package io.github.dhluong90.bpm.rules.internal.dmn.ruleset;

import com.horus.bpm.rules.api.ValidationFailedException;
import com.horus.bpm.rules.internal.json.CanonicalJson;
import com.horus.bpm.rules.internal.json.EngineJson;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Prints every cell and value of a schema version 1 rule set in its normal form before the canonical JSON is
 * written, so the behaviour hash covers the model as the engine prints it (ADR-0026 decision 2). One printer for
 * every source format (YAML, JSON, DMN XML): the one {@link V1Forms} uses for DMN. It merges only texts that are the
 * same behaviour.
 *
 * <ul>
 *   <li>A {@code when} cell drops the column name only when it is a comparison whose left operand is the column's
 *       own input: {@code x >= 3} is {@code >= 3}; {@code in ['VN','SG']} is {@code in ['VN', 'SG']}. A cell that
 *       tests another input or two inputs ({@code y >= 3}, {@code x > y}) keeps its text, stripped.</li>
 *   <li>Numbers: a JSON number only for an integer within plus or minus 2^53 - 1; every other number becomes a
 *       string in plain form with its scale kept ({@code "0.10"} is not {@code "0.1"}).</li>
 *   <li>Strings single-quoted; control and format characters as {@code \}{@code uXXXX}; no Unicode
 *       normalisation. Bidirectional controls are refused.</li>
 *   <li>Instants in UTC with {@code Z}; money at the currency scale.</li>
 *   <li>A {@code null} output cell for an output without a {@code default} is omitted: both give null.</li>
 *   <li>A cell that does not parse is kept as written; the compiler refuses it with the rule and column.</li>
 * </ul>
 *
 * The output is frozen for schema version 1 by the golden file {@code rules/normal-form.v1.txt} in the tests, whose
 * SHA-256 the test pins; a change to it is a new schema version. Normalising twice gives the same document.
 */
public final class CanonicalCells {

    private CanonicalCells() {
    }

    /**
     * Rewrites the cells of a structurally valid schema version 1 document in place.
     *
     * @param document the document, already checked by the reader and with exact numbers only
     * @throws ValidationFailedException when a cell or value holds a bidirectional control character
     */
    public static void normalize(ObjectNode document) {
        List<String> inputs = new ArrayList<>();
        document.get("inputs").forEach(input -> inputs.add(input.get("name").stringValue()));
        ToIntFunction<String> slots = inputs::indexOf;
        for (JsonNode input : document.get("inputs")) {
            JsonNode allowed = input.get("allowed");
            if (allowed != null) {
                allowed.forEach(value -> refuseBidi(value.isString() ? value.stringValue() : "",
                        "Input " + input.get("name").stringValue() + ", allowed values"));
            }
        }
        for (JsonNode output : document.get("outputs")) {
            if (output.has("default")) {
                normalizeOutput((ObjectNode) output, "default", "Output " + output.get("name").stringValue() + ", default");
            }
        }
        java.util.Set<String> withDefault = new java.util.HashSet<>();
        document.get("outputs").forEach(o -> {
            if (o.has("default")) {
                withDefault.add(o.get("name").stringValue());
            }
        });
        for (JsonNode rule : document.get("rules")) {
            String id = rule.get("id").stringValue();
            normalizeWhen((ObjectNode) rule.get("when"), slots, id);
            ObjectNode then = (ObjectNode) rule.get("then");
            for (String output : new ArrayList<>(then.propertyNames())) {
                normalizeOutput(then, output, "Rule " + id + ", output " + output);
                // null for an output without a default is what an omitted cell gives: one form, the omitted one
                if (then.get(output).isNull() && !withDefault.contains(output)) {
                    then.remove(output);
                }
            }
        }
    }

    /**
     * Returns the normal form of one {@code when} cell.
     *
     * @param input the column
     * @param text the cell text
     * @param slots maps input names to slot indexes
     * @return the normal form, or the stripped text when the cell is not a unary test of its column
     */
    public static String condition(String input, String text, ToIntFunction<String> slots) {
        try {
            return V1Forms.condition(input, V1Forms.fromCondition(input, text, slots));
        } catch (Feel.UnsupportedException e) {
            return text.strip();
        }
    }

    /**
     * Refuses bidirectional control characters (U+202A to U+202E, U+2066 to U+2069), which make a literal read
     * differently from what it is (ADR-0026 decision 2).
     *
     * @param text the cell or value text
     * @param where the rule and column, for the message
     * @throws ValidationFailedException naming the character and where it is
     */
    public static void refuseBidi(String text, String where) {
        for (int i = 0; i < text.length(); i++) {
            if (isBidiControl(text.charAt(i))) {
                throw new ValidationFailedException(where + ": the text contains the bidirectional control "
                        + String.format("U+%04X", (int) text.charAt(i)) + ", which is not allowed in rules");
            }
        }
    }

    static boolean isBidiControl(char c) {
        return (c >= '‪' && c <= '‮') || (c >= '⁦' && c <= '⁩');
    }

    /** An integer a JSON number may carry: written without fraction digits and within plus or minus 2^53 - 1. */
    static boolean isJsonInteger(BigDecimal value) {
        return value.scale() <= 0 && CanonicalJson.isSafeInteger(value);
    }

    private static void normalizeWhen(ObjectNode when, ToIntFunction<String> slots, String ruleId) {
        for (Map.Entry<String, JsonNode> cell : new ArrayList<>(when.properties())) {
            String key = cell.getKey();
            if (key.equals("any") || key.equals("all")) {
                cell.getValue().forEach(nested -> normalizeWhen((ObjectNode) nested, slots, ruleId));
            } else {
                String text = cell.getValue().stringValue();
                refuseBidi(text, "Rule " + ruleId + ", input " + key);
                when.put(key, condition(key, text, slots));
            }
        }
    }

    private static void normalizeOutput(ObjectNode holder, String key, String where) {
        JsonNode cell = holder.get(key);
        if (cell.isNumber()) {
            BigDecimal number = cell.decimalValue();
            if (isJsonInteger(number)) {
                holder.put(key, number.setScale(0)); // 1e2 is 100, so the printed form parses back equal
            } else {
                holder.put(key, number.toPlainString());
            }
            return;
        }
        if (!cell.isString()) {
            return;
        }
        refuseBidi(cell.stringValue(), where);
        Feel.Value value;
        try {
            value = V1Forms.literal(cell.stringValue());
        } catch (Feel.UnsupportedException e) {
            holder.put(key, cell.stringValue().strip());
            return;
        }
        switch (value) {
            case Feel.Value.Null() -> holder.set(key, EngineJson.NODES.nullNode());
            case Feel.Value.Bool(boolean b) -> holder.put(key, b);
            case Feel.Value.Num(BigDecimal n) when isJsonInteger(n) -> holder.put(key, n.setScale(0));
            case Feel.Value.Num(BigDecimal n) -> holder.put(key, n.toPlainString());
            default -> holder.put(key, V1Forms.v1(value));
        }
    }
}
