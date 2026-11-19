package com.horus.bpm.rules.rest;

import com.horus.bpm.rules.api.PermissionDeniedException;
import com.horus.bpm.rules.api.RuleSetVersion;
import com.horus.bpm.rules.api.UnauthenticatedException;
import com.horus.bpm.rules.internal.json.EngineValues;
import com.horus.bpm.rules.spi.CallerResolver;
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
        if (callers.currentCallerId() == null) {
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
}
