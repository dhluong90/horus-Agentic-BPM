package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.spi.OutputSchemaValidator;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The engine's default {@link OutputSchemaValidator}: a small, dependency-free subset of JSON Schema (2020-12) for
 * agent and service outputs (DESIGN section 4.1). Supported: {@code type} (one name or a list of names),
 * {@code properties}, {@code required}, {@code additionalProperties} (boolean), {@code enum}, {@code const},
 * {@code minimum}, {@code maximum}, {@code exclusiveMinimum}, {@code exclusiveMaximum}, {@code minLength},
 * {@code maxLength}, {@code items}, {@code minItems} and {@code maxItems}; {@code $schema}, {@code $id},
 * {@code title}, {@code description}, {@code default} and {@code examples} are annotations, and so is the engine's
 * {@code mask} ({@code true} masks the field's value in the history of a refused result). Any other keyword, for
 * example {@code pattern} or {@code $ref}, is a violation: the check fails closed rather than skip a rule it cannot
 * evaluate. Nesting deeper than {@value #MAX_DEPTH} levels is a violation. Thread-safe and stateless.
 */
public final class JsonSchemaSubsetValidator implements OutputSchemaValidator {

    /** Deepest schema nesting checked. */
    public static final int MAX_DEPTH = 32;
    /** Most violations reported for one result. */
    public static final int MAX_VIOLATIONS = 20;

    private static final Set<String> KEYWORDS = Set.of("type", "properties", "required", "additionalProperties", "enum",
            "const", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "minLength", "maxLength", "items",
            "minItems", "maxItems");
    private static final Set<String> ANNOTATIONS = Set.of("$schema", "$id", "title", "description", "default",
            "examples", "mask");
    private static final Set<String> TYPES = Set.of("object", "array", "string", "number", "integer", "boolean",
            "null");

    @Override
    public List<String> validate(Map<String, Object> schema, Map<String, Object> output) {
        List<String> violations = new ArrayList<>();
        if (schema == null) {
            violations.add("$: no output schema");
            return violations;
        }
        check(schema, output, "$", 0, violations);
        return violations.size() > MAX_VIOLATIONS ? List.copyOf(violations.subList(0, MAX_VIOLATIONS))
                : List.copyOf(violations);
    }

    /**
     * Walks the schema alone: every keyword is in the subset, {@code type} names known types, {@code properties} is
     * an object of schemas, {@code items} a schema, {@code required} and {@code enum} lists,
     * {@code additionalProperties} a boolean, every bound a number, and nesting at most {@value #MAX_DEPTH} levels.
     */
    @Override
    public List<String> checkSchema(Map<String, Object> schema) {
        List<String> problems = new ArrayList<>();
        if (schema == null) {
            problems.add("$: no output schema");
        } else {
            checkNode(schema, "$", 0, problems);
        }
        return problems.size() > MAX_VIOLATIONS ? List.copyOf(problems.subList(0, MAX_VIOLATIONS))
                : List.copyOf(problems);
    }

    private static final Set<String> BOUNDS = Set.of("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum",
            "minLength", "maxLength", "minItems", "maxItems");

    private static void checkNode(Object node, String path, int depth, List<String> out) {
        if (out.size() > MAX_VIOLATIONS || node instanceof Boolean) {
            return;
        }
        if (depth > MAX_DEPTH) {
            out.add(path + ": schema nested deeper than " + MAX_DEPTH);
            return;
        }
        if (!(node instanceof Map<?, ?> schema)) {
            out.add(path + ": schema is not an object");
            return;
        }
        for (Map.Entry<?, ?> e : schema.entrySet()) {
            String key = String.valueOf(e.getKey());
            Object value = e.getValue();
            if (!KEYWORDS.contains(key) && !ANNOTATIONS.contains(key)) {
                out.add(path + ": unsupported schema keyword " + key);
            } else if (key.equals("type")) {
                List<?> names = value instanceof Collection<?> c ? List.copyOf(c) : List.of(String.valueOf(value));
                names.stream().filter(n -> !TYPES.contains(String.valueOf(n)))
                        .forEach(n -> out.add(path + ": unsupported type " + n));
            } else if (key.equals("properties")) {
                if (value instanceof Map<?, ?> properties) {
                    properties.forEach((name, child) -> checkNode(child, path + "." + name, depth + 1, out));
                } else {
                    out.add(path + ": properties is not an object");
                }
            } else if (key.equals("items")) {
                checkNode(value, path + "[]", depth + 1, out);
            } else if ((key.equals("required") || key.equals("enum")) && !(value instanceof Collection<?>)) {
                out.add(path + ": " + key + " is not a list");
            } else if (key.equals("additionalProperties") && !(value instanceof Boolean)) {
                out.add(path + ": additionalProperties must be true or false in this subset");
            } else if (BOUNDS.contains(key) && !(value instanceof Number)) {
                out.add(path + ": " + key + " is not a number");
            }
        }
    }

    private void check(Object schemaNode, Object value, String path, int depth, List<String> out) {
        if (out.size() > MAX_VIOLATIONS) {
            return;
        }
        if (depth > MAX_DEPTH) {
            out.add(path + ": schema nested deeper than " + MAX_DEPTH);
            return;
        }
        if (Boolean.TRUE.equals(schemaNode)) {
            return;
        }
        if (Boolean.FALSE.equals(schemaNode)) {
            out.add(path + ": not allowed");
            return;
        }
        if (!(schemaNode instanceof Map<?, ?> schema)) {
            out.add(path + ": schema is not an object");
            return;
        }
        boolean unsupported = false;
        for (Object key : schema.keySet()) {
            if (!KEYWORDS.contains(String.valueOf(key)) && !ANNOTATIONS.contains(String.valueOf(key))) {
                out.add(path + ": unsupported schema keyword " + key);
                unsupported = true;
            }
        }
        if (unsupported) {
            return;
        }
        if (schema.containsKey("type") && !typeMatches(schema.get("type"), value, path, out)) {
            return;
        }
        if (schema.containsKey("enum")) {
            Object allowed = schema.get("enum");
            if (!(allowed instanceof Collection<?> list) || list.stream().noneMatch(a -> same(a, value))) {
                out.add(path + ": not one of the allowed values");
            }
        }
        if (schema.containsKey("const") && !same(schema.get("const"), value)) {
            out.add(path + ": not the required value");
        }
        if (value instanceof Number n && !(value instanceof Boolean)) {
            BigDecimal v = decimal(n);
            bound(schema, "minimum", path, out, b -> v.compareTo(b) >= 0, "below the minimum");
            bound(schema, "maximum", path, out, b -> v.compareTo(b) <= 0, "above the maximum");
            bound(schema, "exclusiveMinimum", path, out, b -> v.compareTo(b) > 0, "not above the exclusive minimum");
            bound(schema, "exclusiveMaximum", path, out, b -> v.compareTo(b) < 0, "not below the exclusive maximum");
        }
        if (value instanceof String s) {
            int length = s.codePointCount(0, s.length());
            bound(schema, "minLength", path, out, b -> length >= b.intValue(), "shorter than the minimum length");
            bound(schema, "maxLength", path, out, b -> length <= b.intValue(), "longer than the maximum length");
        }
        if (value instanceof List<?> list) {
            bound(schema, "minItems", path, out, b -> list.size() >= b.intValue(), "fewer items than the minimum");
            bound(schema, "maxItems", path, out, b -> list.size() <= b.intValue(), "more items than the maximum");
            if (schema.containsKey("items")) {
                for (int i = 0; i < list.size(); i++) {
                    check(schema.get("items"), list.get(i), path + "[" + i + "]", depth + 1, out);
                }
            }
        }
        if (value instanceof Map<?, ?> object) {
            object(schema, object, path, depth, out);
        }
    }

    private void object(Map<?, ?> schema, Map<?, ?> object, String path, int depth, List<String> out) {
        Map<?, ?> properties = schema.get("properties") instanceof Map<?, ?> p ? p : Map.of();
        if (schema.containsKey("properties") && !(schema.get("properties") instanceof Map<?, ?>)) {
            out.add(path + ": properties is not an object");
        }
        if (schema.get("required") instanceof Collection<?> required) {
            for (Object name : required) {
                if (!object.containsKey(String.valueOf(name))) {
                    out.add(path + "." + name + ": required");
                }
            }
        } else if (schema.containsKey("required")) {
            out.add(path + ": required is not a list");
        }
        Object additional = schema.get("additionalProperties");
        if (additional != null && !(additional instanceof Boolean)) {
            out.add(path + ": additionalProperties must be true or false in this subset");
        }
        for (Map.Entry<?, ?> field : object.entrySet()) {
            String name = String.valueOf(field.getKey());
            if (properties.containsKey(name)) {
                check(properties.get(name), field.getValue(), path + "." + name, depth + 1, out);
            } else if (Boolean.FALSE.equals(additional)) {
                out.add(path + "." + name + ": not allowed");
            }
        }
    }

    private static boolean typeMatches(Object type, Object value, String path, List<String> out) {
        List<?> names = type instanceof Collection<?> c ? List.copyOf(c) : List.of(type);
        for (Object name : names) {
            if (!TYPES.contains(String.valueOf(name))) {
                out.add(path + ": unsupported type " + name);
                return false;
            }
        }
        if (names.stream().anyMatch(n -> is(String.valueOf(n), value))) {
            return true;
        }
        out.add(path + ": expected " + (names.size() == 1 ? names.get(0) : names));
        return false;
    }

    private static boolean is(String type, Object value) {
        return switch (type) {
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof List<?>;
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "null" -> value == null;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Number n && isInteger(n);
            default -> false;
        };
    }

    private static boolean isInteger(Number n) {
        BigDecimal v = decimal(n);
        return v.signum() == 0 || v.stripTrailingZeros().scale() <= 0;
    }

    private static BigDecimal decimal(Number n) {
        return n instanceof BigDecimal b ? b : new BigDecimal(n.toString());
    }

    private static boolean same(Object expected, Object value) {
        if (expected instanceof Number a && value instanceof Number b) {
            return decimal(a).compareTo(decimal(b)) == 0;
        }
        return expected == null ? value == null : expected.equals(value);
    }

    private static void bound(Map<?, ?> schema, String keyword, String path, List<String> out,
            java.util.function.Predicate<BigDecimal> holds, String message) {
        if (!schema.containsKey(keyword)) {
            return;
        }
        if (!(schema.get(keyword) instanceof Number limit)) {
            out.add(path + ": " + keyword + " is not a number");
            return;
        }
        if (!holds.test(decimal(limit))) {
            out.add(path + ": " + message);
        }
    }
}
