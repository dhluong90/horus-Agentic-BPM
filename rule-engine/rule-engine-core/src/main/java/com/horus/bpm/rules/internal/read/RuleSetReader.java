package com.horus.bpm.rules.internal.read;

import com.horus.bpm.rules.api.SourceFormat;
import com.horus.bpm.rules.api.ValidationFailedException;
import com.horus.bpm.rules.internal.json.CanonicalJson;
import com.horus.bpm.rules.internal.json.EngineJson;
import com.horus.bpm.rules.internal.json.Hashes;
import com.horus.bpm.rules.internal.model.Cell;
import com.horus.bpm.rules.internal.model.Decision;
import com.horus.bpm.rules.internal.model.DecisionTable;
import com.horus.bpm.rules.internal.model.Definitions;
import com.horus.bpm.rules.internal.model.HitPolicy;
import com.horus.bpm.rules.internal.model.InputClause;
import com.horus.bpm.rules.internal.model.OutputClause;
import com.horus.bpm.rules.internal.model.Rule;
import com.horus.bpm.rules.internal.model.ValueType;
import com.horus.bpm.rules.internal.model.When;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Parse;
import org.snakeyaml.engine.v2.events.AliasEvent;
import org.snakeyaml.engine.v2.events.CollectionStartEvent;
import org.snakeyaml.engine.v2.events.Event;
import org.snakeyaml.engine.v2.events.NodeEvent;
import org.snakeyaml.engine.v2.events.ScalarEvent;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Reads a schema version 1 rule set document (YAML or JSON) into the DMN-shaped model and its RFC 8785
 * canonical form. Structure only: nothing in the document is evaluated (TR-STD-01). Checks follow
 * {@code rule-engine/api/schema/rule-set.v1.schema.json}.
 */
public final class RuleSetReader {

    /** The only expression language of schema version 1. */
    public static final String EXPR_V1 = "urn:horus-bpm:expr:v1";

    /** Largest source document accepted, in bytes. */
    public static final int MAX_SOURCE_BYTES = 1_048_576;

    private static final Pattern RULE_SET_NAME = Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*$");
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Pattern RULE_ID = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]*$");
    private static final Set<String> ROOT_KEYS = Set.of("schemaVersion", "ruleSet", "description",
            "expressionLanguage", "timeZone", "hitPolicy", "effectiveFrom", "moneyRounding", "inputs", "outputs",
            "rules");

    private static final Set<String> INPUT_KEYS = Set.of("name", "type", "description", "allowed", "mask");
    private static final Set<String> OUTPUT_KEYS = Set.of("name", "type", "description", "default", "collect");
    private static final Set<String> RULE_KEYS = Set.of("id", "description", "when", "then");

    private RuleSetReader() {
    }

    /**
     * Reads a source document.
     *
     * @param format {@link SourceFormat#YAML} or {@link SourceFormat#JSON}
     * @param source the document bytes
     * @return the model, canonical JSON and canonical hash
     * @throws ValidationFailedException when the document is not a valid schema version 1 rule set
     */
    public static ParsedRuleSet read(SourceFormat format, byte[] source) {
        if (source.length > MAX_SOURCE_BYTES) {
            throw new ValidationFailedException("Document larger than " + MAX_SOURCE_BYTES + " bytes");
        }
        JsonNode tree;
        try {
            tree = switch (format) {
                case YAML -> {
                    refuseAnchorsAliasesAndTags(source);
                    yield EngineJson.YAML.readTree(source);
                }
                case JSON -> EngineJson.JSON.readTree(source);
                case DMN_XML -> throw new ValidationFailedException("DMN XML import is not available in this release");
            };
        } catch (JacksonException e) {
            throw new ValidationFailedException("Document is not well-formed " + format + ": " + e.getOriginalMessage());
        }
        if (tree == null || !tree.isObject()) {
            throw new ValidationFailedException("Document must be a mapping");
        }
        Definitions model = model(tree);
        requireExactNumbers(tree, "");
        String canonical = CanonicalJson.write(tree);
        return new ParsedRuleSet(model, canonical, Hashes.sha256(canonical));
    }

    /**
     * Reads the canonical JSON stored with a version.
     *
     * @param canonicalJson the stored canonical JSON
     * @return the model
     */
    public static Definitions readCanonical(String canonicalJson) {
        return read(SourceFormat.JSON, canonicalJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)).model();
    }

    private static Definitions model(JsonNode root) {
        for (String key : root.propertyNames()) {
            if (!ROOT_KEYS.contains(key)) {
                throw invalid("Unknown property '" + key + "'");
            }
        }
        JsonNode schemaVersion = required(root, "schemaVersion");
        if (!schemaVersion.isIntegralNumber() || schemaVersion.asInt() != 1) {
            throw invalid("schemaVersion must be 1");
        }
        String name = text(root, "ruleSet");
        if (!RULE_SET_NAME.matcher(name).matches() || name.length() > 100) {
            throw invalid("ruleSet must be kebab-case, at most 100 characters");
        }
        String language = optionalText(root, "expressionLanguage", EXPR_V1);
        if (!EXPR_V1.equals(language)) {
            throw invalid("expressionLanguage must be " + EXPR_V1);
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(optionalText(root, "timeZone", "UTC"));
        } catch (DateTimeException e) {
            throw invalid("timeZone is not an IANA zone id");
        }
        HitPolicy hitPolicy = switch (text(root, "hitPolicy")) {
            case "first" -> HitPolicy.FIRST;
            case "unique" -> HitPolicy.UNIQUE;
            case "collect" -> HitPolicy.COLLECT;
            default -> throw invalid("hitPolicy must be first, unique or collect");
        };
        Instant effectiveFrom = null;
        if (root.has("effectiveFrom")) {
            try {
                effectiveFrom = OffsetDateTime.parse(text(root, "effectiveFrom")).toInstant();
            } catch (DateTimeException e) {
                throw invalid("effectiveFrom must be an ISO 8601 instant with an offset");
            }
        }
        RoundingMode rounding;
        try {
            rounding = RoundingMode.valueOf(optionalText(root, "moneyRounding", "HALF_EVEN"));
        } catch (IllegalArgumentException e) {
            throw invalid("moneyRounding is not a supported rounding mode");
        }
        if (rounding == RoundingMode.HALF_DOWN || rounding == RoundingMode.UNNECESSARY) {
            throw invalid("moneyRounding is not a supported rounding mode");
        }

        List<InputClause> inputs = new ArrayList<>();
        Set<String> inputNames = new HashSet<>();
        for (JsonNode input : array(root, "inputs", 0)) {
            String inputName = identifier(input, "inputs");
            onlyKeys(input, INPUT_KEYS, "Input '" + inputName + "'");
            if (!inputNames.add(inputName)) {
                throw invalid("Duplicate input '" + inputName + "'");
            }
            JsonNode mask = input.get("mask");
            if (mask != null && !mask.isBoolean()) {
                throw invalid("Input '" + inputName + "': mask must be true or false");
            }
            inputs.add(new InputClause(inputName, type(input, inputName), mask != null && mask.booleanValue()));
        }
        List<OutputClause> outputs = new ArrayList<>();
        Set<String> outputNames = new HashSet<>();
        for (JsonNode output : array(root, "outputs", 1)) {
            String outputName = identifier(output, "outputs");
            onlyKeys(output, OUTPUT_KEYS, "Output '" + outputName + "'");
            if (!outputNames.add(outputName)) {
                throw invalid("Duplicate output '" + outputName + "'");
            }
            Cell defaultValue = output.has("default") ? cell(output.get("default"), outputName) : null;
            OutputClause.Aggregate aggregate = switch (output.has("collect") ? text(output, "collect") : "none") {
                case "none" -> OutputClause.Aggregate.NONE;
                case "sum" -> OutputClause.Aggregate.SUM;
                case "min" -> OutputClause.Aggregate.MIN;
                case "max" -> OutputClause.Aggregate.MAX;
                case "count" -> OutputClause.Aggregate.COUNT;
                default -> throw invalid("Output '" + outputName + "': collect must be sum, min, max or count");
            };
            outputs.add(new OutputClause(outputName, type(output, outputName), defaultValue, aggregate));
        }
        List<Rule> rules = new ArrayList<>();
        Set<String> ruleIds = new HashSet<>();
        for (JsonNode rule : array(root, "rules", 1)) {
            if (!rule.isObject()) {
                throw invalid("Every rule must be a mapping");
            }
            String id = text(rule, "id");
            onlyKeys(rule, RULE_KEYS, "Rule " + id);
            if (!RULE_ID.matcher(id).matches() || id.length() > 64) {
                throw invalid("Rule id '" + id + "' is not a valid id");
            }
            if (!ruleIds.add(id)) {
                throw invalid("Duplicate rule id '" + id + "'");
            }
            When when = when(required(rule, "when"), inputNames, id);
            JsonNode thenNode = required(rule, "then");
            if (!thenNode.isObject()) {
                throw invalid("Rule " + id + ": then must be a mapping");
            }
            Map<String, Cell> then = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> entry : thenNode.properties()) {
                if (!outputNames.contains(entry.getKey())) {
                    throw invalid("Rule " + id + ": unknown output '" + entry.getKey() + "'");
                }
                then.put(entry.getKey(), cell(entry.getValue(), id));
            }
            rules.add(new Rule(id, when, then));
        }
        DecisionTable table = new DecisionTable(hitPolicy, rounding, inputs, outputs, rules);
        return new Definitions(name, language, zone, effectiveFrom, List.of(new Decision(name, name, table)));
    }

    /**
     * Refuses YAML anchors, aliases and explicit tags before Jackson reads the document. Jackson keeps an alias as
     * its name and drops a tag, so the stored document would differ from what a reviewer reads in the source. The
     * check walks the low-level events of snakeyaml-engine, the YAML parser Jackson itself uses (a transitive
     * dependency of jackson-dataformat-yaml), because Jackson does not report anchors on scalars.
     */
    private static void refuseAnchorsAliasesAndTags(byte[] source) {
        LoadSettings settings = LoadSettings.builder().setMaxAliasesForCollections(0).build();
        try {
            for (Event event : new Parse(settings).parseInputStream(new java.io.ByteArrayInputStream(source))) {
                String line = event.getStartMark().map(mark -> " (line " + (mark.getLine() + 1) + ")").orElse("");
                if (event instanceof AliasEvent) {
                    throw invalid("YAML aliases are not allowed" + line + "; repeat the value");
                }
                if (event instanceof NodeEvent node && node.getAnchor().isPresent()) {
                    throw invalid("YAML anchors are not allowed" + line);
                }
                if ((event instanceof ScalarEvent scalar && scalar.getTag().isPresent())
                        || (event instanceof CollectionStartEvent collection && collection.getTag().isPresent())) {
                    throw invalid("YAML tags are not allowed" + line);
                }
            }
        } catch (YamlEngineException e) {
            throw invalid("Document is not well-formed YAML: " + e.getMessage());
        }
    }

    /**
     * Refuses a number that the RFC 8785 form would change (I-JSON): the hash, the stored canonical JSON and
     * the evaluation must all carry the value the checker read in the source.
     */
    private static void requireExactNumbers(JsonNode node, String path) {
        if (node.isNumber()) {
            BigDecimal value = node.decimalValue();
            boolean exact;
            try {
                exact = new BigDecimal(CanonicalJson.number(value)).compareTo(value) == 0;
            } catch (IllegalArgumentException | ArithmeticException e) {
                exact = false;
            }
            if (!exact) {
                throw invalid("Number at " + (path.isEmpty() ? "/" : path)
                        + " cannot be stored exactly; use at most 15 significant digits or a string expression");
            }
        } else if (node.isArray()) {
            int i = 0;
            for (JsonNode element : node) {
                requireExactNumbers(element, path + "/" + i++);
            }
        } else if (node.isObject()) {
            for (Map.Entry<String, JsonNode> member : node.properties()) {
                requireExactNumbers(member.getValue(), path + "/" + member.getKey());
            }
        }
    }

    private static When when(JsonNode node, Set<String> inputNames, String ruleId) {
        if (!node.isObject()) {
            throw invalid("Rule " + ruleId + ": when must be a mapping");
        }
        Map<String, String> cells = new LinkedHashMap<>();
        List<When> any = new ArrayList<>();
        List<When> all = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (key.equals("any") || key.equals("all")) {
                if (!value.isArray() || value.isEmpty()) {
                    throw invalid("Rule " + ruleId + ": " + key + " must be a non-empty list");
                }
                for (JsonNode nested : value) {
                    (key.equals("any") ? any : all).add(when(nested, inputNames, ruleId));
                }
            } else {
                if (!inputNames.contains(key)) {
                    throw invalid("Rule " + ruleId + ": unknown input '" + key + "'");
                }
                if (!value.isString() || value.stringValue().isBlank()) {
                    throw invalid("Rule " + ruleId + ": condition of '" + key + "' must be a non-empty string");
                }
                cells.put(key, value.stringValue());
            }
        }
        return new When(cells, any, all);
    }

    private static Cell cell(JsonNode value, String where) {
        if (value.isNull()) {
            return new Cell.Literal(null);
        }
        if (value.isBoolean()) {
            return new Cell.Literal(value.booleanValue());
        }
        if (value.isNumber()) {
            return new Cell.Literal(value.decimalValue());
        }
        if (value.isString()) {
            return new Cell.Expression(value.stringValue());
        }
        throw invalid(where + ": a cell must be a string, number, boolean or null");
    }

    private static ValueType type(JsonNode clause, String name) {
        try {
            return ValueType.parse(text(clause, "type").toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid("'" + name + "' has an unknown type");
        }
    }

    private static String identifier(JsonNode clause, String where) {
        if (!clause.isObject()) {
            throw invalid("Every entry of " + where + " must be a mapping");
        }
        String name = text(clause, "name");
        if (!IDENTIFIER.matcher(name).matches() || name.length() > 64) {
            throw invalid("'" + name + "' in " + where + " is not a valid identifier");
        }
        return name;
    }

    private static List<JsonNode> array(JsonNode root, String key, int minItems) {
        JsonNode node = required(root, key);
        if (!node.isArray() || node.size() < minItems) {
            throw invalid(key + " must be a list with at least " + minItems + " item(s)");
        }
        List<JsonNode> items = new ArrayList<>();
        node.forEach(items::add);
        return items;
    }

    /** additionalProperties: false of the schema, so a misspelt key such as {@code maks} is never ignored. */
    private static void onlyKeys(JsonNode node, Set<String> allowed, String where) {
        for (String key : node.propertyNames()) {
            if (!allowed.contains(key)) {
                throw invalid(where + ": Unknown property '" + key + "'");
            }
        }
    }

    private static JsonNode required(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null) {
            throw invalid("Missing required property '" + key + "'");
        }
        return value;
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = required(node, key);
        if (!value.isString()) {
            throw invalid("'" + key + "' must be a string");
        }
        return value.stringValue();
    }

    private static String optionalText(JsonNode node, String key, String defaultValue) {
        return node.has(key) ? text(node, key) : defaultValue;
    }

    private static ValidationFailedException invalid(String detail) {
        return new ValidationFailedException(detail);
    }
}
