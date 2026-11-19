package com.horus.bpm.rules.internal.json;

import com.horus.bpm.rules.api.DecisionInput;
import com.horus.bpm.rules.api.DecisionResult;
import com.horus.bpm.rules.api.InputInvalidException;
import com.horus.bpm.rules.api.MaskingKey;
import com.horus.bpm.rules.api.Money;
import com.horus.bpm.rules.api.RuleSetVersion;
import com.horus.bpm.rules.api.SourceFormat;
import com.horus.bpm.rules.api.VersionStatus;
import com.horus.bpm.rules.internal.model.InputClause;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The engine's JSON form of its values and results: money is always {@code {currency, amount}} in
 * minor units (DESIGN section 3.4), times are ISO 8601 strings, keys are camelCase. Used by the REST
 * module, the decision log and idempotency records, so they never depend on host JSON settings.
 */
public final class EngineValues {

    private static final BigDecimal MAX_SAFE = BigDecimal.valueOf(Money.MAX_JSON_SAFE);

    private EngineValues() {
    }

    /**
     * Writes a tree as compact JSON.
     *
     * @param node the tree
     * @return the text
     */
    public static String write(JsonNode node) {
        return EngineJson.JSON.writeValueAsString(node);
    }

    /**
     * Parses JSON text.
     *
     * @param json the text
     * @return the tree
     */
    public static JsonNode read(String json) {
        return EngineJson.JSON.readTree(json);
    }

    /**
     * Converts one engine value to JSON.
     *
     * @param value {@code Money}, {@code BigDecimal}, {@code Long}, {@code Integer}, {@code String},
     *     {@code Boolean}, {@code LocalDate}, {@code Instant}, {@code List} or null
     * @return the tree
     */
    public static JsonNode value(Object value) {
        return switch (value) {
            case null -> EngineJson.NODES.nullNode();
            case Money money -> EngineJson.NODES.objectNode()
                    .put("currency", money.currency())
                    .put("amount", money.minorUnits());
            case BigDecimal number -> EngineJson.NODES.numberNode(number);
            case BigInteger number -> EngineJson.NODES.numberNode(number);
            case Long number -> EngineJson.NODES.numberNode(number);
            case Integer number -> EngineJson.NODES.numberNode(number);
            case Short number -> EngineJson.NODES.numberNode(number);
            case String text -> EngineJson.NODES.stringNode(text);
            case Boolean flag -> EngineJson.NODES.booleanNode(flag);
            case LocalDate date -> EngineJson.NODES.stringNode(date.toString());
            case Instant instant -> EngineJson.NODES.stringNode(instant.toString());
            case List<?> list -> {
                ArrayNode array = EngineJson.NODES.arrayNode();
                list.forEach(element -> array.add(value(element)));
                yield array;
            }
            default -> throw new InputInvalidException("Unsupported value type " + value.getClass().getSimpleName());
        };
    }

    /**
     * Converts the declared inputs to the JSON object written to the decision log. Undeclared keys are
     * dropped, never logged (data minimisation); a masked input with a value is replaced by its keyed
     * fingerprint (RLE-012). A null value stays null: there is nothing to hide, and an auditor sees it was missing.
     *
     * @param input the inputs as supplied
     * @param declared the rule set's input clauses
     * @param maskByDefault whether inputs that declare no {@code mask} are masked
     * @param key the tenant's masking key; required when an input with a value is masked
     * @return the object
     * @throws IllegalStateException when an input must be masked and there is no key; callers check first
     */
    public static ObjectNode loggedInputs(DecisionInput input, List<InputClause> declared, boolean maskByDefault,
            MaskingKey key) {
        ObjectNode node = EngineJson.NODES.objectNode();
        for (InputClause clause : declared) {
            if (input.values().containsKey(clause.name())) {
                Object v = input.get(clause.name());
                if (v != null && clause.masked(maskByDefault)) {
                    if (key == null) {
                        throw new IllegalStateException("No masking key for a masked input");
                    }
                    node.put(clause.name(), fingerprint(key, v));
                } else {
                    node.set(clause.name(), value(v));
                }
            }
        }
        return node;
    }

    /**
     * Returns the keyed fingerprint of one input value. The fingerprinted text is the value's plain form, so an
     * auditor can compute it outside the engine: a string as it is (the fingerprint of a national id is the
     * HMAC-SHA256 of its UTF-8 bytes); a number as its decimal digits without trailing zeros ({@code 5} and
     * {@code 5.00} are one value); money as currency, colon and minor units ({@code VND:50000000}); a boolean, date
     * or datetime as its ISO text; a list as its canonical JSON (RFC 8785).
     *
     * @param key the tenant's key
     * @param value the plain value, not null
     * @return {@code hmac:<hex>}
     */
    public static String fingerprint(MaskingKey key, Object value) {
        return key.fingerprint(plainForm(value));
    }

    private static String plainForm(Object value) {
        return switch (value) {
            case String text -> text;
            case BigDecimal number -> plainNumber(number);
            case BigInteger number -> number.toString();
            case Long number -> number.toString();
            case Integer number -> number.toString();
            case Short number -> number.toString();
            case Money money -> money.currency() + ":" + money.minorUnits();
            case Boolean flag -> flag.toString();
            case LocalDate date -> date.toString();
            case Instant instant -> instant.toString();
            default -> CanonicalJson.write(value(value));
        };
    }

    private static String plainNumber(BigDecimal number) {
        return number.signum() == 0 ? "0" : number.stripTrailingZeros().toPlainString();
    }

    /**
     * Reads the {@code inputs} object of a REST request into engine values: {@code {currency, amount}}
     * becomes {@link Money}; integers become {@code Long} or {@code BigInteger}; decimals become
     * {@code BigDecimal}.
     *
     * @param node the JSON object, or null for no inputs
     * @return the inputs
     * @throws InputInvalidException for a malformed money object or a non-object
     */
    public static DecisionInput inputsFromJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return DecisionInput.of(Map.of());
        }
        if (!node.isObject()) {
            throw new InputInvalidException("inputs must be a JSON object");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            values.put(entry.getKey(), fromJson(entry.getValue(), entry.getKey()));
        }
        return DecisionInput.of(values);
    }

    /** Output values as the evaluator returns them: every number is a {@code BigDecimal} (DESIGN section 3.5). */
    private static Object outputFromJson(JsonNode node, String name) {
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(element -> list.add(element.isNull() ? null : outputFromJson(element, name)));
            return java.util.Collections.unmodifiableList(list);
        }
        return fromJson(node, name);
    }

    private static Object fromJson(JsonNode node, String name) {
        if (node.isNull()) {
            return null;
        }
        if (node.isString()) {
            return node.stringValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber()) {
            BigInteger integer = node.bigIntegerValue();
            return integer.bitLength() < 64 ? (Object) integer.longValue() : integer;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(element -> list.add(fromJson(element, name)));
            return list;
        }
        if (node.isObject() && node.size() == 2 && node.has("currency") && node.has("amount")) {
            return money(node, name);
        }
        throw new InputInvalidException("Input " + name + " has an unsupported JSON shape");
    }

    private static Money money(JsonNode node, String name) {
        JsonNode currency = node.get("currency");
        JsonNode amount = node.get("amount");
        if (!currency.isString() || !amount.isIntegralNumber()
                || amount.decimalValue().abs().compareTo(MAX_SAFE) > 0) {
            throw new InputInvalidException("Input " + name
                    + " must be {currency, amount} with an integer amount in minor units within 2^53 - 1");
        }
        try {
            return Money.ofMinor(currency.stringValue(), amount.longValue());
        } catch (IllegalArgumentException e) {
            throw new InputInvalidException("Input " + name + " has an unknown currency");
        }
    }

    /**
     * Writes a version as JSON. The canonical definition is embedded as an object.
     *
     * @param version the version
     * @return the object
     */
    public static ObjectNode version(RuleSetVersion version) {
        ObjectNode node = EngineJson.NODES.objectNode();
        node.put("ruleSet", version.ruleSet());
        node.put("version", version.version());
        node.put("status", version.status().name());
        node.put("hitPolicy", version.hitPolicy());
        node.set("effectiveFrom", value(version.effectiveFrom()));
        node.put("lockVersion", version.lockVersion());
        node.put("canonicalHash", version.canonicalHash());
        node.put("sourceFormat", version.sourceFormat().name());
        node.put("sourceHash", version.sourceHash());
        if (version.sourceDocument() != null) {
            node.put("sourceDocument", version.sourceDocument());
        }
        node.set("requestedBy", value(version.requestedBy()));
        node.set("requestedAt", value(version.requestedAt()));
        node.set("approvedBy", value(version.approvedBy()));
        node.set("approvedAt", value(version.approvedAt()));
        node.set("createdBy", value(version.createdBy()));
        node.put("createdAt", version.createdAt().toString());
        node.set("definition", read(version.canonicalDocument()));
        return node;
    }

    /**
     * Reads a version written by {@link #version(RuleSetVersion)}.
     *
     * @param json the text
     * @return the version
     */
    public static RuleSetVersion versionFromJson(String json) {
        JsonNode node = read(json);
        return new RuleSetVersion(
                node.get("ruleSet").stringValue(),
                node.get("version").asInt(),
                VersionStatus.valueOf(node.get("status").stringValue()),
                node.get("hitPolicy").stringValue(),
                instant(node.get("effectiveFrom")),
                node.get("lockVersion").asLong(),
                CanonicalJson.write(node.get("definition")),
                node.get("canonicalHash").stringValue(),
                SourceFormat.valueOf(node.get("sourceFormat").stringValue()),
                node.get("sourceHash").stringValue(),
                node.has("sourceDocument") ? node.get("sourceDocument").stringValue() : null,
                text(node.get("requestedBy")),
                instant(node.get("requestedAt")),
                text(node.get("approvedBy")),
                instant(node.get("approvedAt")),
                text(node.get("createdBy")),
                Instant.parse(node.get("createdAt").stringValue()));
    }

    /**
     * Writes a decision result as JSON.
     *
     * @param result the result
     * @return the object
     */
    public static ObjectNode decision(DecisionResult result) {
        ObjectNode node = EngineJson.NODES.objectNode();
        node.put("decisionId", result.decisionId());
        node.put("ruleSet", result.ruleSet());
        node.put("version", result.version());
        node.put("evaluatedAt", result.evaluatedAt().toString());
        switch (result.outcome()) {
            case DecisionResult.Matched matched -> {
                node.put("outcome", "MATCHED");
                node.put("matched", true);
                node.set("matchedRuleIds", value(matched.ruleIds()));
                ObjectNode outputs = node.putObject("outputs");
                matched.outputs().forEach((name, v) -> outputs.set(name, value(v)));
            }
            case DecisionResult.NoMatch noMatch -> {
                node.put("outcome", "NO_MATCH");
                node.put("matched", false);
                node.set("matchedRuleIds", EngineJson.NODES.arrayNode());
                ObjectNode outputs = node.putObject("outputs");
                noMatch.outputs().forEach((name, v) -> outputs.set(name, value(v)));
            }
            case DecisionResult.Failed failed -> {
                node.put("outcome", "FAILED");
                node.set("matchedRuleIds", value(failed.matchedRuleIds()));
                node.putObject("outputs");
                node.putObject("error").put("code", failed.code()).put("detail", failed.detail());
            }
        }
        return node;
    }

    /**
     * Reads a decision result written by {@link #decision(DecisionResult)}.
     *
     * @param json the text
     * @return the result
     */
    public static DecisionResult decisionFromJson(String json) {
        JsonNode node = read(json);
        DecisionResult.Outcome outcome = switch (node.get("outcome").stringValue()) {
            case "MATCHED" -> {
                List<String> ids = new ArrayList<>();
                node.get("matchedRuleIds").forEach(id -> ids.add(id.stringValue()));
                Map<String, Object> outputs = new LinkedHashMap<>();
                node.get("outputs").properties().forEach(e -> outputs.put(e.getKey(), outputFromJson(e.getValue(), e.getKey())));
                yield new DecisionResult.Matched(ids, outputs);
            }
            case "NO_MATCH" -> {
                Map<String, Object> outputs = new LinkedHashMap<>();
                node.get("outputs").properties().forEach(e -> outputs.put(e.getKey(), outputFromJson(e.getValue(), e.getKey())));
                yield new DecisionResult.NoMatch(outputs);
            }
            default -> {
                List<String> ids = new ArrayList<>();
                node.get("matchedRuleIds").forEach(id -> ids.add(id.stringValue()));
                yield new DecisionResult.Failed(node.get("error").get("code").stringValue(),
                        node.get("error").get("detail").stringValue(), ids);
            }
        };
        return new DecisionResult(node.get("decisionId").stringValue(), node.get("ruleSet").stringValue(),
                node.get("version").asInt(), Instant.parse(node.get("evaluatedAt").stringValue()), outcome);
    }

    /**
     * Parses JSON text, reporting malformed input as {@code INPUT_INVALID}.
     *
     * @param bytes the request body
     * @return the tree
     */
    public static JsonNode readRequest(byte[] bytes) {
        try {
            return EngineJson.JSON.readTree(bytes);
        } catch (JacksonException e) {
            throw new InputInvalidException("Request body is not valid JSON");
        }
    }

    private static Instant instant(JsonNode node) {
        return node == null || node.isNull() ? null : Instant.parse(node.stringValue());
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.stringValue();
    }
}
