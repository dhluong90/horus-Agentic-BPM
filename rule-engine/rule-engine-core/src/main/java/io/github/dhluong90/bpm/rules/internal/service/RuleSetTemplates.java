package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.api.RuleSetTemplate;
import io.github.dhluong90.bpm.rules.api.SourceFormat;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import io.github.dhluong90.bpm.rules.internal.read.RuleSetReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The rule set templates a host offers (PD-112, RLE-197, RLE-198): the four the engine ships, minus any the host hides,
 * then the host's template files in the order given (the starter reads the folder and sorts by file name; the core does
 * no file I/O, TR-EMB-05). A file holds an {@code id}, a
 * {@code labels} and a {@code descriptions} object with {@code en} and {@code vi}, and a {@code document}: a schema
 * version 1 rule set without its {@code ruleSet} name. Every template is read once, at start-up, and checked as an
 * imported document would be, so a bad file stops the start and names itself; nothing is read per request.
 */
public final class RuleSetTemplates {

    /** The ids of the templates the engine ships, in the order they are listed. */
    public static final List<String> BUILT_IN_IDS = List.of("fee-by-tier", "eligibility-with-reason",
            "limit-by-segment", "blank");

    /** The most templates one host may offer, built-in ones included. */
    public static final int MAX_TEMPLATES = 100;

    /** The largest template file, in bytes. */
    public static final int MAX_FILE_BYTES = 65_536;

    private static final String RESOURCE_FOLDER = "templates/";
    private static final Pattern TEMPLATE_ID = Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*$");
    private static final Set<String> FILE_KEYS = Set.of("id", "labels", "descriptions", "document");
    private static final Set<String> LANGUAGES = Set.of("en", "vi");
    private static final int TEXT_MAX = 400;
    private static final String CHECK_NAME = "template-check";

    private final List<RuleSetTemplate> templates;

    private RuleSetTemplates(List<RuleSetTemplate> templates) {
        this.templates = List.copyOf(templates);
    }

    /**
     * Returns the four built-in templates only.
     *
     * @return the catalogue
     */
    public static RuleSetTemplates builtIn() {
        return of(Map.of(), Set.of());
    }

    /**
     * Reads the catalogue.
     *
     * @param hostFiles the host's template files by file name, in listing order (use a {@code LinkedHashMap} or a
     *     {@code TreeMap}); empty adds none
     * @param hiddenBuiltIn the built-in ids the host hides ({@code horus.bpm.rules.templates.hidden}); an id that is
     *     not built in stops the start, so a typo never leaves a template on show
     * @return the catalogue
     * @throws IllegalArgumentException naming the file and the problem, when a template is not valid, an id repeats,
     *     or a hidden id is unknown
     */
    public static RuleSetTemplates of(Map<String, byte[]> hostFiles, Set<String> hiddenBuiltIn) {
        for (String hidden : hiddenBuiltIn) {
            if (!BUILT_IN_IDS.contains(hidden)) {
                throw new IllegalArgumentException("horus.bpm.rules.templates.hidden names '" + hidden
                        + "', which is not a built-in template; the built-in templates are " + BUILT_IN_IDS);
            }
        }
        List<RuleSetTemplate> all = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (String id : BUILT_IN_IDS) {
            RuleSetTemplate template = parse(id + ".json", builtInBytes(id), RuleSetTemplate.BUILT_IN);
            if (!hiddenBuiltIn.contains(id)) {
                add(all, ids, template, id + ".json");
            }
        }
        hostFiles.forEach((name, bytes) -> {
            if (bytes.length > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("Template file " + name + " is larger than " + MAX_FILE_BYTES
                        + " bytes");
            }
            add(all, ids, parse(name, bytes, RuleSetTemplate.HOST), name);
        });
        return new RuleSetTemplates(all);
    }

    /**
     * Returns the templates in listing order.
     *
     * @return the templates, never null
     */
    public List<RuleSetTemplate> list() {
        return templates;
    }

    private static void add(List<RuleSetTemplate> all, Set<String> ids, RuleSetTemplate template, String file) {
        if (!ids.add(template.id())) {
            throw new IllegalArgumentException("Template file " + file + ": the id '" + template.id()
                    + "' is already used by another template; hide the built-in one or rename this one");
        }
        if (all.size() >= MAX_TEMPLATES) {
            throw new IllegalArgumentException("Template file " + file + ": a host offers at most " + MAX_TEMPLATES
                    + " templates");
        }
        all.add(template);
    }

    private static byte[] builtInBytes(String id) {
        try (InputStream in = RuleSetTemplates.class.getResourceAsStream(RESOURCE_FOLDER + id + ".json")) {
            if (in == null) {
                throw new IllegalStateException("The built-in template " + id + " is missing from the engine");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static RuleSetTemplate parse(String file, byte[] bytes, String source) {
        JsonNode root;
        try {
            root = EngineJson.JSON.readTree(bytes);
        } catch (JacksonException e) {
            throw problem(file, "is not well-formed JSON: " + e.getOriginalMessage());
        }
        if (root == null || !root.isObject()) {
            throw problem(file, "must be a JSON object");
        }
        for (String key : root.propertyNames()) {
            if (!FILE_KEYS.contains(key)) {
                throw problem(file, "has the unknown property '" + key + "'; expected id, labels, descriptions and"
                        + " document");
            }
        }
        String id = text(file, root, "id");
        if (!TEMPLATE_ID.matcher(id).matches() || id.length() > 64) {
            throw problem(file, "has the id '" + id + "'; use lower-case letters, digits and hyphens, at most 64"
                    + " characters");
        }
        Map<String, String> labels = languages(file, root, "labels");
        Map<String, String> descriptions = languages(file, root, "descriptions");
        JsonNode document = root.get("document");
        if (document == null || !document.isObject()) {
            throw problem(file, "needs a document: a rule set without its ruleSet name");
        }
        if (document.has("ruleSet")) {
            throw problem(file, "has a document with a ruleSet name; a template has none, the author names the new"
                    + " rule set");
        }
        // Checked as an imported rule set would be, with a stand-in name, so a template never offers what the
        // engine would refuse at the author's first save.
        ObjectNode named = EngineJson.NODES.objectNode();
        named.put("schemaVersion", document.path("schemaVersion").asInt(0));
        named.put("ruleSet", CHECK_NAME);
        document.properties().forEach(e -> {
            if (!e.getKey().equals("schemaVersion")) {
                named.set(e.getKey(), e.getValue());
            }
        });
        try {
            RuleSetReader.read(SourceFormat.JSON, EngineJson.JSON.writeValueAsBytes(named));
        } catch (ValidationFailedException e) {
            throw problem(file, "has a document that is not a valid rule set: " + e.getMessage());
        }
        List<RuleSetTemplate.Input> inputs = new ArrayList<>();
        document.path("inputs").forEach(input -> inputs.add(new RuleSetTemplate.Input(input.path("name").asString(),
                input.path("type").asString(), allowed(input.path("allowed")))));
        List<RuleSetTemplate.Output> outputs = new ArrayList<>();
        document.path("outputs").forEach(output -> outputs.add(new RuleSetTemplate.Output(
                output.path("name").asString(), output.path("type").asString())));
        List<RuleSetTemplate.Row> rows = new ArrayList<>();
        for (JsonNode rule : document.path("rules")) {
            Map<String, String> when = new LinkedHashMap<>();
            for (var cell : rule.path("when").properties()) {
                if (!cell.getValue().isString()) {
                    throw problem(file, "rule " + rule.path("id").asString() + " has a condition on '"
                            + cell.getKey() + "' that is not a text cell; a template uses plain conditions only");
                }
                when.put(cell.getKey(), cell.getValue().stringValue());
            }
            Map<String, Object> then = new LinkedHashMap<>();
            for (var cell : rule.path("then").properties()) {
                JsonNode value = cell.getValue();
                then.put(cell.getKey(), value.isBoolean() ? (Object) value.booleanValue()
                        : value.isNumber() ? (Object) value.decimalValue() : value.asString());
            }
            rows.add(new RuleSetTemplate.Row(rule.path("id").asString(), when, then));
        }
        return new RuleSetTemplate(id, labels, descriptions, source, document.path("hitPolicy").asString(), inputs,
                outputs, rows);
    }

    private static List<String> allowed(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.forEach(value -> values.add(value.asString()));
        return values;
    }

    private static String text(String file, JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            throw problem(file, "needs " + key + " as non-blank text");
        }
        return value.stringValue();
    }

    private static Map<String, String> languages(String file, JsonNode root, String key) {
        JsonNode node = root.get(key);
        if (node == null || !node.isObject()) {
            throw problem(file, "needs " + key + " with an English (en) and a Vietnamese (vi) text");
        }
        Map<String, String> texts = new LinkedHashMap<>();
        for (var entry : node.properties()) {
            if (!entry.getValue().isString() || entry.getValue().stringValue().isBlank()
                    || entry.getValue().stringValue().length() > TEXT_MAX) {
                throw problem(file, "has " + key + "." + entry.getKey() + " that is not text of 1 to " + TEXT_MAX
                        + " characters");
            }
            texts.put(entry.getKey(), entry.getValue().stringValue());
        }
        for (String language : LANGUAGES) {
            if (!texts.containsKey(language)) {
                throw problem(file, "needs " + key + "." + language);
            }
        }
        return texts;
    }

    private static IllegalArgumentException problem(String file, String detail) {
        return new IllegalArgumentException("Template file " + file + " " + detail);
    }
}
