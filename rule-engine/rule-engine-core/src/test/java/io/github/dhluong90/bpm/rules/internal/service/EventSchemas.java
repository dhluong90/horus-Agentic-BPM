package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;

/**
 * Checks event payloads against the schema files in {@code rule-engine/api/schema}, so the outbox and the published
 * schemas cannot drift apart. Covers the keywords these schemas use: {@code required}, {@code const}, {@code type}
 * (one or a list), {@code enum}, {@code pattern}, {@code minimum}, {@code minLength} and {@code maxLength}.
 */
final class EventSchemas {

    static final Path DIRECTORY = Path.of("../api/schema");

    /** The envelope every event carries (DESIGN section 3.3). */
    static final List<String> ENVELOPE = List.of("eventId", "eventType", "occurredAt", "tenantId", "correlationId",
            "causationId", "producer", "schemaVersion");

    private EventSchemas() {
    }

    /** The schema file of an event type. */
    static Path fileOf(String eventType) {
        return DIRECTORY.resolve(eventType + ".schema.json");
    }

    /** Every event schema file: one per event type, named after it. */
    static List<Path> eventSchemaFiles() {
        try (Stream<Path> files = Files.list(DIRECTORY)) {
            return files.filter(f -> f.getFileName().toString().matches("rules\\.(version|decision)\\..+\\.schema\\.json"))
                    .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static JsonNode read(Path file) {
        try {
            return EngineJson.JSON.readTree(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Checks a payload against the schema of its own {@code eventType}. */
    static void conforms(JsonNode payload) {
        String type = payload.path("eventType").asString();
        Path file = fileOf(type);
        assertThat(Files.exists(file)).as("schema file for %s", type).isTrue();
        conformsTo(file, payload);
    }

    /** Checks a payload against one schema file. */
    static void conformsTo(Path schemaFile, JsonNode payload) {
        JsonNode schema = read(schemaFile);
        String name = schemaFile.getFileName().toString();
        assertThat(payload.isObject()).as("%s payload is an object", name).isTrue();
        for (JsonNode required : schema.path("required")) {
            assertThat(payload.has(required.asString())).as("%s requires %s", name, required.asString()).isTrue();
        }
        schema.path("properties").properties().forEach(property -> {
            JsonNode value = payload.get(property.getKey());
            if (value != null) {
                checkValue(name + " " + property.getKey(), property.getValue(), value);
            }
        });
    }

    private static void checkValue(String what, JsonNode rule, JsonNode value) {
        if (rule.has("const")) {
            assertThat(value).as("%s const", what).isEqualTo(rule.get("const"));
        }
        if (rule.has("enum")) {
            Set<JsonNode> allowed = new HashSet<>();
            rule.get("enum").forEach(allowed::add);
            assertThat(allowed).as("%s enum", what).contains(value);
        }
        Set<String> types = new HashSet<>();
        JsonNode type = rule.path("type");
        if (type.isArray()) {
            type.forEach(t -> types.add(t.asString()));
        } else if (type.isString()) {
            types.add(type.asString());
        }
        if (!types.isEmpty()) {
            assertThat(types).as("%s type", what).contains(typeOf(value));
        }
        if (value.isString()) {
            String text = value.asString();
            if (rule.has("pattern")) {
                assertThat(Pattern.compile(rule.get("pattern").asString()).matcher(text).find())
                        .as("%s matches %s, was %s", what, rule.get("pattern").asString(), text).isTrue();
            }
            if (rule.has("minLength")) {
                assertThat(text.codePointCount(0, text.length())).as("%s minLength", what)
                        .isGreaterThanOrEqualTo(rule.get("minLength").asInt());
            }
            if (rule.has("maxLength")) {
                assertThat(text.codePointCount(0, text.length())).as("%s maxLength", what)
                        .isLessThanOrEqualTo(rule.get("maxLength").asInt());
            }
        }
        if (value.isNumber() && rule.has("minimum")) {
            assertThat(value.asLong()).as("%s minimum", what).isGreaterThanOrEqualTo(rule.get("minimum").asLong());
        }
    }

    private static String typeOf(JsonNode value) {
        if (value.isNull()) {
            return "null";
        }
        if (value.isString()) {
            return "string";
        }
        if (value.isIntegralNumber()) {
            return "integer";
        }
        if (value.isNumber()) {
            return "number";
        }
        if (value.isBoolean()) {
            return "boolean";
        }
        return value.isObject() ? "object" : value.isArray() ? "array" : "other";
    }
}
