package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.internal.json.EngineJson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The event schemas of {@code process-engine/api/events} change only additively within {@code .v1} (PRE-085,
 * DESIGN section 6.4): against the baseline copy of the previous release, no event type is removed, no payload
 * property is removed or changes its type, no payload member becomes required, and the envelope does not change. At a
 * release the baseline is replaced by the released schemas.
 */
class EventSchemaCompatibilityTest {

    private static final Path CURRENT = Path.of("../api/events");

    private static JsonNode read(Path file) throws IOException {
        return EngineJson.JSON.readTree(Files.readString(file));
    }

    private static JsonNode baseline(String name) throws IOException {
        try (InputStream in = EventSchemaCompatibilityTest.class.getResourceAsStream("/events-baseline/" + name)) {
            return EngineJson.JSON.readTree(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static Set<String> names(JsonNode array) {
        Set<String> names = new HashSet<>();
        array.forEach(n -> names.add(n.asString()));
        return names;
    }

    /** @return what a change from {@code before} to {@code after} breaks; empty for an additive change */
    static List<String> breaks(String name, JsonNode before, JsonNode after) {
        List<String> found = new ArrayList<>();
        if (!names(before.get("required")).equals(names(after.get("required")))) {
            found.add(name + ": the envelope members changed");
        }
        JsonNode oldPayload = before.at("/properties/payload");
        JsonNode newPayload = after.at("/properties/payload");
        oldPayload.get("properties").propertyNames().forEach(property -> {
            JsonNode now = newPayload.at("/properties/" + property);
            if (now.isMissingNode()) {
                found.add(name + ": payload property " + property + " was removed");
            } else if (!oldPayload.at("/properties/" + property + "/type").equals(now.get("type"))) {
                found.add(name + ": payload property " + property + " changed its type");
            }
        });
        Set<String> added = names(newPayload.get("required"));
        added.removeAll(names(oldPayload.get("required")));
        added.forEach(property -> found.add(name + ": payload member " + property + " became required"));
        for (String constant : List.of("eventType", "producer", "schemaVersion")) {
            if (!before.at("/properties/" + constant + "/const").equals(after.at("/properties/" + constant + "/const"))) {
                found.add(name + ": " + constant + " changed");
            }
        }
        return found;
    }

    @Test
    void PRE_085_event_schemas_change_only_additively() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(Path.of("src/test/resources/events-baseline"))) {
            for (Path old : files.filter(f -> f.toString().endsWith(".schema.json")).sorted().toList()) {
                String name = old.getFileName().toString();
                Path current = CURRENT.resolve(name);
                if (!Files.exists(current)) {
                    found.add(name + ": the event type was removed");
                    continue;
                }
                found.addAll(breaks(name, baseline(name), read(current)));
            }
        }
        assertThat(found).isEmpty();
    }

    @Test
    void PRE_085_the_check_detects_a_removed_property_a_changed_type_and_a_new_required_member() throws IOException {
        JsonNode before = baseline("processes.step.completed.v1.schema.json");
        // an added optional property is additive
        JsonNode additive = EngineJson.JSON.readTree(before.toString());
        ((tools.jackson.databind.node.ObjectNode) additive.at("/properties/payload/properties")).putObject("nodeKind")
                .put("type", "string");
        assertThat(breaks("step", before, additive)).isEmpty();
        // removing, retyping and requiring are not
        JsonNode broken = EngineJson.JSON.readTree(before.toString());
        ((tools.jackson.databind.node.ObjectNode) broken.at("/properties/payload/properties")).remove("durationMs");
        ((tools.jackson.databind.node.ObjectNode) broken.at("/properties/payload/properties/nodeId")).put("type", "integer");
        ((tools.jackson.databind.node.ArrayNode) broken.at("/properties/payload/required")).add("newMandatory");
        assertThat(breaks("step", before, broken)).hasSize(3).anyMatch(s -> s.contains("durationMs was removed"))
                .anyMatch(s -> s.contains("nodeId changed its type")).anyMatch(s -> s.contains("newMandatory"));
    }
}
