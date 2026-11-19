package io.github.dhluong90.bpm.rules.internal.json;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Writes a JSON tree in the JSON Canonicalization Scheme of RFC 8785: object members sorted by their
 * UTF-16 code units, no whitespace, minimal string escaping and numbers in the ECMAScript form of
 * their IEEE 754 double value. The output does not depend on any Jackson setting, so a later change
 * of JSON library cannot change a hash (ADR-0021).
 */
public final class CanonicalJson {

    private CanonicalJson() {
    }

    /**
     * Returns the RFC 8785 form of a JSON tree.
     *
     * @param node the tree
     * @return the canonical text
     * @throws IllegalArgumentException for values JSON cannot hold (binary, NaN, infinity)
     */
    public static String write(JsonNode node) {
        StringBuilder out = new StringBuilder(256);
        write(node, out);
        return out.toString();
    }

    private static void write(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            out.append("null");
        } else if (node.isBoolean()) {
            out.append(node.booleanValue() ? "true" : "false");
        } else if (node.isNumber()) {
            out.append(number(node.decimalValue()));
        } else if (node.isString()) {
            string(node.stringValue(), out);
        } else if (node.isArray()) {
            out.append('[');
            boolean first = true;
            for (JsonNode element : node) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                write(element, out);
            }
            out.append(']');
        } else if (node.isObject()) {
            List<Map.Entry<String, JsonNode>> members = new ArrayList<>(node.properties());
            // String.compareTo orders by UTF-16 code units, which is what RFC 8785 section 3.2.3 requires.
            members.sort(Map.Entry.comparingByKey());
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonNode> member : members) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                string(member.getKey(), out);
                out.append(':');
                write(member.getValue(), out);
            }
            out.append('}');
        } else {
            throw new IllegalArgumentException("Not representable in JSON: " + node.getNodeType());
        }
    }

    /**
     * Formats a number as ECMAScript {@code Number.prototype.toString} formats its double value
     * (RFC 8785 section 3.2.2.3).
     *
     * @param value the number
     * @return the canonical number text
     */
    public static String number(BigDecimal value) {
        double d = value.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new IllegalArgumentException("Number out of the IEEE 754 double range: " + value);
        }
        if (d == 0) {
            return "0";
        }
        String sign = d < 0 ? "-" : "";
        BigDecimal shortest = shortest(Math.abs(d));
        String digits = shortest.unscaledValue().toString();
        int k = digits.length();
        int n = shortest.precision() - shortest.scale();
        StringBuilder out = new StringBuilder(sign);
        if (k <= n && n <= 21) {
            out.append(digits).append("0".repeat(n - k));
        } else if (0 < n && n <= 21) {
            out.append(digits, 0, n).append('.').append(digits, n, k);
        } else if (-6 < n && n <= 0) {
            out.append("0.").append("0".repeat(-n)).append(digits);
        } else {
            int e = n - 1;
            out.append(digits.charAt(0));
            if (k > 1) {
                out.append('.').append(digits, 1, k);
            }
            out.append('e').append(e < 0 ? '-' : '+').append(Math.abs(e));
        }
        return out.toString();
    }

    /** The decimal with the fewest significant digits that reads back as {@code d}, closest on ties. */
    private static BigDecimal shortest(double d) {
        BigDecimal exact = new BigDecimal(d);
        for (int digits = 1; digits <= 17; digits++) {
            BigDecimal candidate = exact.round(new MathContext(digits, RoundingMode.HALF_EVEN));
            if (candidate.doubleValue() == d) {
                return candidate.stripTrailingZeros();
            }
        }
        return exact.round(MathContext.DECIMAL64).stripTrailingZeros();
    }

    private static void string(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
