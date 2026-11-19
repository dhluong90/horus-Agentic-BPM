package io.github.dhluong90.bpm.rules.internal.dmn.ruleset;

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
 * Prints every cell of a schema version 1 rule set in its normal form before the canonical JSON is written, so the
 * behaviour hash covers the model as the engine prints it (ADR-0026). One printer for every source format (YAML,
 * JSON, DMN XML): the one {@link V1Forms} uses for DMN.
 *
 * <ul>
 *   <li>A {@code when} cell that is a unary test of its own column is printed without the column name:
 *       {@code x >= 3} is {@code >= 3}, {@code in ['VN','SG']} is {@code in ['VN', 'SG']}.</li>
 *   <li>Literals: strings single-quoted, instants in UTC with {@code Z}, money at the currency scale
 *       ({@code 12.5 USD} is {@code 12.50 USD}), numbers plain; a literal output written as text becomes a JSON
 *       number, boolean or null.</li>
 *   <li>A cell with no unary-test form (it reads another input, or calculates) keeps its text, stripped.</li>
 *   <li>A cell that does not parse is kept as written; the compiler refuses it with the rule and column.</li>
 * </ul>
 *
 * The output is frozen for schema version 1 by the golden file {@code rules/normal-form.v1.txt} in the tests; a
 * change to it is a new schema version. Normalising twice gives the same document.
 */
public final class CanonicalCells {

    private CanonicalCells() {
    }

    /**
     * Rewrites the cells of a structurally valid schema version 1 document in place.
     *
     * @param document the document, already checked by the reader
     */
    public static void normalize(ObjectNode document) {
        List<String> inputs = new ArrayList<>();
        document.get("inputs").forEach(input -> inputs.add(input.get("name").stringValue()));
        ToIntFunction<String> slots = inputs::indexOf;
        for (JsonNode output : document.get("outputs")) {
            if (output.has("default")) {
                normalizeOutput((ObjectNode) output, "default");
            }
        }
        for (JsonNode rule : document.get("rules")) {
            normalizeWhen((ObjectNode) rule.get("when"), slots);
            ObjectNode then = (ObjectNode) rule.get("then");
            for (String output : new ArrayList<>(then.propertyNames())) {
                normalizeOutput(then, output);
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

    private static void normalizeWhen(ObjectNode when, ToIntFunction<String> slots) {
        for (Map.Entry<String, JsonNode> cell : new ArrayList<>(when.properties())) {
            String key = cell.getKey();
            if (key.equals("any") || key.equals("all")) {
                cell.getValue().forEach(nested -> normalizeWhen((ObjectNode) nested, slots));
            } else {
                when.put(key, condition(key, cell.getValue().stringValue(), slots));
            }
        }
    }

    /** A number the RFC 8785 form keeps exactly; a longer one stays text so the document is still accepted. */
    private static boolean exact(BigDecimal n) {
        try {
            return new BigDecimal(CanonicalJson.number(n)).compareTo(n) == 0;
        } catch (IllegalArgumentException | ArithmeticException e) {
            return false;
        }
    }

    private static void normalizeOutput(ObjectNode holder, String key) {
        JsonNode cell = holder.get(key);
        if (!cell.isString()) {
            return;
        }
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
            case Feel.Value.Num(BigDecimal n) when exact(n) -> holder.put(key, n);
            case Feel.Value.Num(BigDecimal n) -> holder.put(key, cell.stringValue().strip());
            default -> holder.put(key, V1Forms.v1(value));
        }
    }
}
