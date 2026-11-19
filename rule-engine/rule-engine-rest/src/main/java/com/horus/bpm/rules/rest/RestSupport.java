package com.horus.bpm.rules.rest;

import com.horus.bpm.rules.api.PayloadTooLargeException;
import com.horus.bpm.rules.api.PermissionDeniedException;
import com.horus.bpm.rules.api.RuleSetVersion;
import com.horus.bpm.rules.api.UnauthenticatedException;
import com.horus.bpm.rules.internal.json.EngineValues;
import com.horus.bpm.rules.internal.read.RuleSetReader;
import com.horus.bpm.rules.spi.CallerResolver;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** Shared helpers of the engine controllers. */
final class RestSupport {

    /** Base path of every Rule Engine endpoint. */
    static final String BASE = "/api/bpm/rules/v1";

    private RestSupport() {
    }

    static void require(CallerResolver callers, String permission) {
        String caller = callers.currentCallerId();
        if (caller == null || caller.isBlank()) {
            throw new UnauthenticatedException("Authentication required");
        }
        if (!callers.currentPermissions().contains(permission)) {
            throw new PermissionDeniedException(permission);
        }
    }

    static ResponseEntity<byte[]> json(HttpStatus status, JsonNode body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(EngineValues.write(body).getBytes(StandardCharsets.UTF_8));
    }

    static ResponseEntity<byte[]> version(HttpStatus status, RuleSetVersion version) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
                .header(HttpHeaders.ETAG, "\"" + version.lockVersion() + "\"");
        if (status == HttpStatus.CREATED) {
            builder.header(HttpHeaders.LOCATION, BASE + "/rule-sets/" + version.ruleSet() + "/versions/" + version.version());
        }
        return builder.contentType(MediaType.APPLICATION_JSON)
                .body(EngineValues.write(EngineValues.version(version)).getBytes(StandardCharsets.UTF_8));
    }

    /** Parses {@code If-Match: "3"} into 3; null when absent. */
    static Long ifMatch(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = header.strip();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        value = value.replace("\"", "");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new com.horus.bpm.rules.api.InputInvalidException("If-Match must be a quoted lock version, for example \"3\"");
        }
    }

    /**
     * Reads an import body of at most {@link RuleSetReader#MAX_SOURCE_BYTES} bytes. A larger declared
     * {@code Content-Length} is refused before anything is read; otherwise at most one byte more than the limit is
     * read, so an oversized body never sits in memory whole.
     *
     * @param body the request body stream
     * @param contentLength the declared length, or null
     * @return the body bytes
     * @throws PayloadTooLargeException when the body is larger than the limit
     * @throws IOException when the stream fails
     */
    static byte[] boundedBody(InputStream body, Long contentLength) throws IOException {
        int limit = RuleSetReader.MAX_SOURCE_BYTES;
        if (contentLength != null && contentLength > limit) {
            throw new PayloadTooLargeException(limit);
        }
        byte[] bytes = body.readNBytes(limit + 1);
        if (bytes.length > limit) {
            throw new PayloadTooLargeException(limit);
        }
        return bytes;
    }
}
