package io.github.dhluong90.bpm.process.autoconfigure;

import io.github.dhluong90.bpm.process.internal.json.EngineJson;
import io.github.dhluong90.bpm.process.spi.FormRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * The default {@link FormRegistry}: JSON documents on the classpath under one configured folder
 * ({@code horus.bpm.processes.tasks.form-resource-root}, default {@code forms/}), and nothing else.
 *
 * <p>A reference ending in {@code .json} is that file under the root; any other reference (a form key) is
 * {@code <key>.form.json}. A document is either the JSON Schema itself, or an envelope
 * {@code {"schema": {...}, "hints": {...}}}. A reference is a plain relative name (letters, digits, {@code . _ - /},
 * no {@code ..}), so a definition can never name a file outside the folder; a document above
 * {@value #MAX_BYTES} bytes, or one that is not a JSON object, is treated as not registered.
 */
final class ClasspathFormRegistry implements FormRegistry {

    static final int MAX_BYTES = 262_144;
    private static final Pattern REFERENCE = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}(/[A-Za-z0-9][A-Za-z0-9_.-]{0,99}){0,9}$");

    private final String root;
    private final ClassLoader loader;

    ClasspathFormRegistry(String root, ClassLoader loader) {
        this.root = root;
        this.loader = loader;
    }

    @Override
    public Optional<FormDefinition> find(String tenantId, String reference) {
        if (reference == null || !REFERENCE.matcher(reference).matches() || reference.contains("..")) {
            return Optional.empty();
        }
        String file = reference.endsWith(".json") ? reference : reference + ".form.json";
        try (InputStream in = loader.getResourceAsStream(root + file)) {
            if (in == null) {
                return Optional.empty();
            }
            byte[] bytes = in.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                return Optional.empty();
            }
            JsonNode document = EngineJson.JSON.readTree(bytes);
            if (document == null || !document.isObject()) {
                return Optional.empty();
            }
            if (document.has("schema") && document.get("schema").isObject()) {
                return Optional.of(new FormDefinition(map(document.get("schema")),
                        document.has("hints") && document.get("hints").isObject() ? map(document.get("hints"))
                                : Map.of()));
            }
            return Optional.of(new FormDefinition(map(document), Map.of()));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Map<String, Object> map(JsonNode node) {
        return new LinkedHashMap<>(io.github.dhluong90.bpm.process.internal.json.Values.readMap(
                EngineJson.JSON.writeValueAsString(node)));
    }
}
