package io.github.dhluong90.bpm.examples.ruleengineonly;

import java.nio.charset.StandardCharsets;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Test helper for the many host tests that approve or reject a version only to reach another behaviour. An approval
 * or rejection must name the {@code canonicalHash} of the content it reviewed (RLE-084, PD-139), so a request
 * helper calls {@link #withHash} to read the version first and add {@code expectedCanonicalHash} to the body. The
 * tests of the binding itself ({@code ApprovalBindsToContentHostIT}) never use it.
 */
final class ReviewedContent {

    /** A well-formed hash that matches no content, sent when the version cannot be read (a 404 test still gets its 404). */
    static final String NO_SUCH_CONTENT = "0".repeat(64);

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int COMMAND_LIMIT = 16_384;

    private ReviewedContent() {
    }

    /** Reads the JSON of a version from its path, as the caller who decides. */
    @FunctionalInterface
    interface Reader {
        String read(String path) throws Exception;
    }

    /** Whether the request path is an approval or a rejection. */
    static boolean decides(String path) {
        return path.endsWith(":approve") || path.endsWith(":reject");
    }

    /**
     * Returns the body of a decision with {@code expectedCanonicalHash} added when it names none.
     *
     * @param path the request path ending in {@code :approve} or {@code :reject}
     * @param body the body, or null for none
     * @param readVersion reads the JSON of a version from its path, as the same caller
     * @return the body to send; the same array when it is left as it is (not a small JSON object, or already bound)
     */
    static byte[] withHash(String path, byte[] body, Reader readVersion) {
        if (body != null && (body.length == 0 || body[0] != '{' || body.length > COMMAND_LIMIT)) {
            return body;
        }
        ObjectNode object;
        try {
            JsonNode parsed = body == null ? JSON.createObjectNode() : JSON.readTree(body);
            if (!(parsed instanceof ObjectNode node) || node.has("expectedCanonicalHash")) {
                return body;
            }
            object = node;
        } catch (RuntimeException notJson) {
            return body;
        }
        String hash = NO_SUCH_CONTENT;
        try {
            JsonNode version = JSON.readTree(readVersion.read(path.substring(0, path.lastIndexOf(':'))));
            if (version.path("canonicalHash").isString()) {
                hash = version.path("canonicalHash").asString();
            }
        } catch (Exception unreadable) {
            // keep NO_SUCH_CONTENT: the decision then reaches its own refusal (404, 403) as it did before
        }
        object.put("expectedCanonicalHash", hash);
        return JSON.writeValueAsString(object).getBytes(StandardCharsets.UTF_8);
    }
}
