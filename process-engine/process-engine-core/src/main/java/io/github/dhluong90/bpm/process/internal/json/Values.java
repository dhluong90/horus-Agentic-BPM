package io.github.dhluong90.bpm.process.internal.json;

import com.horus.bpm.rules.api.Money;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Instance variables to and from JSON. Money is {@code {"currency": "VND", "amount": 50000}} with the amount in
 * minor units (process-definition schema {@code inputValue}); an object with exactly those two members reads back
 * as {@link Money}. Integers read back as {@code Long} (or {@code BigInteger}), decimals as {@code BigDecimal}; no
 * value passes through {@code double}.
 */
public final class Values {

    private Values() {
    }

    /**
     * @param value a variable value
     * @return its JSON tree
     * @throws IllegalArgumentException for a type that has no JSON form, or a floating-point number
     */
    public static JsonNode toJson(Object value) {
        return switch (value) {
            case null -> EngineJson.NODES.nullNode();
            case Money money -> EngineJson.NODES.objectNode().put("currency", money.currency())
                    .put("amount", money.minorUnits());
            case BigDecimal d -> EngineJson.NODES.numberNode(d);
            case BigInteger i -> EngineJson.NODES.numberNode(i);
            case Long l -> EngineJson.NODES.numberNode(l);
            case Integer i -> EngineJson.NODES.numberNode(i);
            case Short s -> EngineJson.NODES.numberNode(s);
            case String s -> EngineJson.NODES.stringNode(s);
            case Boolean b -> EngineJson.NODES.booleanNode(b);
            case LocalDate d -> EngineJson.NODES.stringNode(d.toString());
            case Instant i -> EngineJson.NODES.stringNode(i.toString());
            case List<?> list -> {
                ArrayNode array = EngineJson.NODES.arrayNode();
                list.forEach(v -> array.add(toJson(v)));
                yield array;
            }
            case Map<?, ?> map -> {
                ObjectNode object = EngineJson.NODES.objectNode();
                map.forEach((k, v) -> object.set(String.valueOf(k), toJson(v)));
                yield object;
            }
            case Double d -> throw new IllegalArgumentException("Floating-point values are not accepted; use BigDecimal");
            case Float f -> throw new IllegalArgumentException("Floating-point values are not accepted; use BigDecimal");
            default -> throw new IllegalArgumentException("A " + value.getClass().getSimpleName()
                    + " cannot be stored as a variable");
        };
    }

    /**
     * @param variables variables
     * @return JSON text
     */
    public static String write(Map<String, Object> variables) {
        return EngineJson.JSON.writeValueAsString(toJson(variables));
    }

    /**
     * @param json JSON text of an object
     * @return the variables
     */
    public static Map<String, Object> readMap(String json) {
        JsonNode node = EngineJson.JSON.readTree(json);
        Object value = fromJson(node);
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) map;
        return typed;
    }

    /**
     * @param node a JSON tree
     * @return the variable value
     */
    public static Object fromJson(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isObject()) {
            if (node.size() == 2 && node.has("currency") && node.has("amount") && node.get("currency").isString()
                    && node.get("amount").isIntegralNumber()) {
                return Money.ofMinor(node.get("currency").stringValue(), node.get("amount").longValue());
            }
            Map<String, Object> map = new LinkedHashMap<>();
            node.properties().forEach(e -> map.put(e.getKey(), fromJson(e.getValue())));
            return map;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(e -> list.add(fromJson(e)));
            return list;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber()) {
            BigInteger i = node.bigIntegerValue();
            return i.bitLength() < 64 ? (Object) i.longValue() : i;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        return node.stringValue();
    }
}
