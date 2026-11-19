package io.github.dhluong90.bpm.host.autoconfigure;

import io.github.dhluong90.bpm.host.spi.EngineAccessDeniedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * An RFC 9457 problem with the engine contract's {@code code} and {@code correlationId} (ADR-0010).
 *
 * <p>The body is written here, without a JSON library, because it is also written from the security
 * filter chain where no message converter runs. It never contains an exception message, a stack trace
 * or SQL (TR-SEC-08): {@code detail} is always one of the fixed texts below.
 */
record Problem(int status, String code, String title, String detail) {

    static final String MEDIA_TYPE = "application/problem+json";
    static final String TYPE_BASE = "https://github.com/dhluong90/horus-Agentic-BPM/wiki/Common-Error-Codes#";

    static Problem unauthenticated() {
        return new Problem(401, "UNAUTHENTICATED", "Authentication required",
                "Sign in, or send a token issued for this host.");
    }

    static Problem noTenant() {
        return new Problem(401, "UNAUTHENTICATED", "Authentication required",
                "No tenant could be resolved for the caller. Signing in again with the same account does not help.");
    }

    static Problem permissionDenied() {
        return new Problem(403, "PERMISSION_DENIED", "Permission denied",
                "The caller lacks the permission this endpoint checks.");
    }

    static Problem tenantNotAllowed() {
        return new Problem(403, "TENANT_NOT_ALLOWED", "Tenant not allowed",
                "The request names a tenant the caller is not signed in for, and tenant switching is off.");
    }

    /** The problem for a refusal of {@code EngineAccess}. */
    static Problem of(EngineAccessDeniedException.Reason reason) {
        return switch (reason) {
            case UNAUTHENTICATED -> unauthenticated();
            case NO_TENANT -> noTenant();
            case TENANT_NOT_ALLOWED -> tenantNotAllowed();
            case PERMISSION_DENIED -> permissionDenied();
        };
    }

    /** The JSON body for this request. */
    String toJson(HttpServletRequest request) {
        StringBuilder json = new StringBuilder(320).append('{');
        field(json, "type", TYPE_BASE + code.toLowerCase(Locale.ROOT)).append(',');
        field(json, "title", title).append(',');
        json.append("\"status\":").append(status).append(',');
        field(json, "code", code).append(',');
        field(json, "detail", detail).append(',');
        field(json, "instance", request.getRequestURI()).append(',');
        field(json, "correlationId", CorrelationIds.of(request));
        return json.append('}').toString();
    }

    /** Writes status, headers and body; used from filters, where no message converter runs. */
    void write(HttpServletRequest request, HttpServletResponse response) throws IOException {
        byte[] body = toJson(request).getBytes(StandardCharsets.UTF_8);
        response.setStatus(status);
        response.setContentType(MEDIA_TYPE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        response.setHeader(CorrelationIds.HEADER, CorrelationIds.of(request));
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    private static StringBuilder field(StringBuilder json, String name, String value) {
        json.append('"').append(name).append("\":\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20 || c == '<' || c == '>' || c == '&' || c == 0x2028 || c == 0x2029) {
                        json.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        return json.append('"');
    }
}
