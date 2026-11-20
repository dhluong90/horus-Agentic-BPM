package io.github.dhluong90.bpm.process.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.Error;
import com.networknt.schema.ExecutionConfig;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Checks live request and response bodies against an OpenAPI 3.1 document, so a body that drifts from the published
 * contract fails the test that produced it. It knows nothing about any one module: it takes the path of the
 * contract, finds the operation by method and request path (the first server URL is stripped and path templates are
 * matched), resolves {@code $ref} request bodies and responses, and validates JSON bodies with JSON Schema 2020-12,
 * the dialect of OpenAPI 3.1, with format assertions on.
 *
 * <p>By default every object schema that declares {@code properties} and says nothing about additional properties
 * is treated as closed, so a renamed optional field fails as well as a missing required one. {@link #loadOpen} keeps
 * plain JSON Schema semantics.
 *
 * <p>A violation names the operation, the field and the schema path, for example
 * {@code startInstance POST /instances -> 201 application/json: required property 'definitionKey' not found
 * [field $, schema #/components/schemas/ProcessInstance/required]}.
 */
final class OpenApiBodies {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final List<String> METHODS = List.of("get", "put", "post", "delete", "options", "head", "patch",
            "trace");
    private static final ExecutionConfig STRICT = ExecutionConfig.builder().formatAssertionsEnabled(true)
            .locale(Locale.ENGLISH).build();

    private static final List<String> PROPERTY_KEYWORDS = List.of("required", "additionalProperties",
            "unevaluatedProperties");

    private final String iri;
    private final JsonNode document;
    private final String serverPrefix;
    private final SchemaRegistry registry;

    private OpenApiBodies(Path contract, boolean closedObjects) {
        try {
            this.document = YAML.readTree(Files.readAllBytes(contract));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the contract " + contract, e);
        }
        if (!document.path("openapi").asText().startsWith("3.1")) {
            throw new IllegalArgumentException(contract + " is not an OpenAPI 3.1 document");
        }
        if (closedObjects) {
            close(document);
        }
        this.iri = "https://contract.invalid/" + contract.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_");
        this.serverPrefix = stripTrailingSlash(document.path("servers").path(0).path("url").asText(""));
        String text = document.toString();
        this.registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemas(Map.of(iri, text)));
    }

    /**
     * @param contract the OpenAPI 3.1 document
     * @return a checker that treats object schemas with declared properties as closed
     */
    static OpenApiBodies load(Path contract) {
        return new OpenApiBodies(contract, true);
    }

    /**
     * @param contract the OpenAPI 3.1 document
     * @return a checker with plain JSON Schema semantics (undeclared properties are allowed)
     */
    static OpenApiBodies loadOpen(Path contract) {
        return new OpenApiBodies(contract, false);
    }

    /**
     * Checks the request and the response of one MockMvc exchange.
     *
     * @param result the exchange
     */
    void assertExchange(MvcResult result) {
        var request = result.getRequest();
        var response = result.getResponse();
        List<String> violations = new ArrayList<>(requestViolations(request.getMethod(), request.getRequestURI(),
                request.getContentType(), new String(bytes(request.getContentAsByteArray()), StandardCharsets.UTF_8)));
        try {
            violations.addAll(responseViolations(request.getMethod(), request.getRequestURI(), response.getStatus(),
                    response.getContentType(), response.getContentAsString(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(violations).as("contract violations").isEmpty();
    }

    /**
     * Checks only the response of one MockMvc exchange, for tests that send a deliberately invalid request.
     *
     * @param result the exchange
     */
    void assertResponseOf(MvcResult result) {
        var request = result.getRequest();
        var response = result.getResponse();
        try {
            assertResponse(request.getMethod(), request.getRequestURI(), response.getStatus(),
                    response.getContentType(), response.getContentAsString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * @param method HTTP method
     * @param path request path, with or without the server prefix
     * @param mediaType the request's {@code Content-Type}, or null
     * @param body the request body, empty when none
     */
    void assertRequest(String method, String path, String mediaType, String body) {
        assertThat(requestViolations(method, path, mediaType, body)).as("contract violations").isEmpty();
    }

    /**
     * @param method HTTP method
     * @param path request path, with or without the server prefix
     * @param status response status
     * @param mediaType the response's {@code Content-Type}, or null
     * @param body the response body, empty when none
     */
    void assertResponse(String method, String path, int status, String mediaType, String body) {
        assertThat(responseViolations(method, path, status, mediaType, body)).as("contract violations").isEmpty();
    }

    /**
     * @param method HTTP method
     * @param path request path, with or without the server prefix
     * @param mediaType the request's {@code Content-Type}, or null
     * @param body the request body, empty when none
     * @return one line per violation; empty when the body matches the contract
     */
    List<String> requestViolations(String method, String path, String mediaType, String body) {
        Operation op = operation(method, path);
        String where = op.label() + " request";
        if (!op.node().has("requestBody")) {
            return isEmpty(body) ? List.of() : List.of(where + ": the contract declares no request body");
        }
        Ref requestBody = resolve(new Ref(op.pointer() + "/requestBody", op.node().get("requestBody")));
        if (isEmpty(body)) {
            return requestBody.node().path("required").asBoolean(false)
                    ? List.of(where + ": the contract requires a request body") : List.of();
        }
        return content(where, requestBody, mediaType, body);
    }

    /**
     * @param method HTTP method
     * @param path request path, with or without the server prefix
     * @param status response status
     * @param mediaType the response's {@code Content-Type}, or null
     * @param body the response body, empty when none
     * @return one line per violation; empty when the status and body match the contract
     */
    List<String> responseViolations(String method, String path, int status, String mediaType, String body) {
        Operation op = operation(method, path);
        String where = op.label() + " -> " + status;
        JsonNode responses = op.node().path("responses");
        String key = String.valueOf(status);
        if (!responses.has(key)) {
            key = (status / 100) + "XX";
        }
        if (!responses.has(key)) {
            key = "default";
        }
        if (!responses.has(key)) {
            return List.of(where + ": status " + status + " is not documented for this operation");
        }
        Ref response = resolve(new Ref(op.pointer() + "/responses/" + escape(key), responses.get(key)));
        if (!response.node().has("content")) {
            return isEmpty(body) ? List.of() : List.of(where + ": the contract declares no response body");
        }
        return content(where, response, mediaType, body);
    }

    private List<String> content(String where, Ref holder, String mediaType, String body) {
        String type = baseType(mediaType);
        JsonNode content = holder.node().path("content");
        if (type == null || !content.has(type)) {
            return List.of(where + ": media type " + mediaType + " is not documented (documented: "
                    + names(content) + ")");
        }
        String label = where + " " + type;
        if (!isJson(type) || !content.get(type).has("schema")) {
            return List.of();
        }
        String pointer = holder.pointer() + "/content/" + escape(type) + "/schema";
        Schema schema = registry.getSchema(SchemaLocation.of(iri + "#" + pointer));
        List<Error> errors;
        try {
            errors = schema.validate(body, InputFormat.JSON, context -> context.setExecutionConfig(STRICT));
        } catch (RuntimeException e) {
            return List.of(label + ": the body is not JSON (" + e.getMessage() + ")");
        }
        List<String> violations = new ArrayList<>();
        for (Error error : errors) {
            String field = field(error.getInstanceLocation().toString());
            if (error.getProperty() != null && PROPERTY_KEYWORDS.contains(error.getKeyword())) {
                field = field + "." + error.getProperty();
            }
            violations.add(label + ": " + error.getMessage() + " [field " + field + ", schema "
                    + error.getSchemaLocation().toString().replace(iri, "") + "]");
        }
        return violations;
    }

    private Operation operation(String method, String requestPath) {
        String m = method.toLowerCase(Locale.ROOT);
        String path = stripQuery(requestPath);
        if (!serverPrefix.isEmpty() && path.startsWith(serverPrefix)) {
            path = path.substring(serverPrefix.length());
        }
        Operation templated = null;
        Iterator<Map.Entry<String, JsonNode>> paths = document.path("paths").fields();
        while (paths.hasNext()) {
            Map.Entry<String, JsonNode> entry = paths.next();
            JsonNode operation = entry.getValue().get(m);
            if (operation == null) {
                continue;
            }
            Operation candidate = new Operation(operation.path("operationId").asText(m + " " + entry.getKey()),
                    m.toUpperCase(Locale.ROOT), entry.getKey(), operation,
                    "/paths/" + escape(entry.getKey()) + "/" + m);
            if (entry.getKey().equals(path)) {
                return candidate;
            }
            if (templated == null && template(entry.getKey()).matcher(path).matches()) {
                templated = candidate;
            }
        }
        return Optional.ofNullable(templated).orElseThrow(() -> new AssertionError(
                method + " " + requestPath + " is not an operation of the contract"));
    }

    private Ref resolve(Ref ref) {
        Ref current = ref;
        for (int depth = 0; current.node().has("$ref") && depth < 10; depth++) {
            String target = current.node().get("$ref").asText();
            if (!target.startsWith("#/")) {
                throw new IllegalArgumentException("Only local references are supported: " + target);
            }
            String pointer = target.substring(1);
            JsonNode node = document.at(pointer);
            if (node.isMissingNode()) {
                throw new IllegalArgumentException("Unresolved reference " + target);
            }
            current = new Ref(pointer, node);
        }
        return current;
    }

    /** Marks object schemas with declared properties and no word on extra properties as closed. */
    private static void close(JsonNode node) {
        if (node instanceof ObjectNode object) {
            if (object.path("properties").isObject() && typeIncludesObject(object.path("type"))
                    && !object.has("additionalProperties") && !object.has("unevaluatedProperties")) {
                object.put("additionalProperties", false);
            }
        }
        node.forEach(OpenApiBodies::close);
    }

    private static boolean typeIncludesObject(JsonNode type) {
        if (type.isTextual()) {
            return "object".equals(type.asText());
        }
        for (JsonNode t : type) {
            if ("object".equals(t.asText())) {
                return true;
            }
        }
        return false;
    }

    private static Pattern template(String path) {
        StringBuilder regex = new StringBuilder();
        for (String part : path.split("(?=\\{)|(?<=\\})")) {
            regex.append(part.startsWith("{") && part.endsWith("}") ? "[^/]+" : Pattern.quote(part));
        }
        return Pattern.compile(regex.toString());
    }

    /** A JSON Pointer to the field as a path: {@code /tasks/0/status} is {@code $.tasks[0].status}. */
    private static String field(String pointer) {
        StringBuilder path = new StringBuilder("$");
        if (pointer.isEmpty() || "/".equals(pointer)) {
            return path.toString();
        }
        for (String token : pointer.substring(pointer.startsWith("/") ? 1 : 0).split("/", -1)) {
            String name = token.replace("~1", "/").replace("~0", "~");
            path.append(name.matches("\\d+") ? "[" + name + "]" : "." + name);
        }
        return path.toString();
    }

    private static String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    private static String baseType(String mediaType) {
        if (mediaType == null || mediaType.isBlank()) {
            return null;
        }
        int semicolon = mediaType.indexOf(';');
        return (semicolon < 0 ? mediaType : mediaType.substring(0, semicolon)).strip().toLowerCase(Locale.ROOT);
    }

    private static boolean isJson(String type) {
        return type.equals("application/json") || type.endsWith("+json");
    }

    private static String names(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return String.join(", ", names);
    }

    private static boolean isEmpty(String body) {
        return body == null || body.isEmpty();
    }

    private static byte[] bytes(byte[] body) {
        return body == null ? new byte[0] : body;
    }

    private static String stripQuery(String path) {
        int q = path.indexOf('?');
        return q < 0 ? path : path.substring(0, q);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private record Operation(String operationId, String method, String template, JsonNode node, String pointer) {
        String label() {
            return operationId + " " + method + " " + template;
        }
    }

    private record Ref(String pointer, JsonNode node) {
    }
}
