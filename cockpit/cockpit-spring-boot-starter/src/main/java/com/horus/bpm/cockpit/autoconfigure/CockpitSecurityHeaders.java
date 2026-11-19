package com.horus.bpm.cockpit.autoconfigure;

import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Response headers on every cockpit page and asset (cockpit DESIGN section 13, TR-SEC-09).
 * The cockpit has no inline script or style, so {@code script-src 'self'} and {@code style-src 'self'} hold.
 * {@code connect-src 'self'}: an embedded cockpit talks only to the host that serves it.
 * {@code frame-ancestors 'self'} and {@code X-Frame-Options: SAMEORIGIN}: the host may frame the cockpit in
 * its own admin UI on the same origin; no other site can (DESIGN section 13).
 * {@code cockpit/web/security-headers.ts} holds the same values for the front-end tests;
 * {@code CockpitSecurityHeadersParityTest} fails when the two differ.
 */
final class CockpitSecurityHeaders {

    static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self'",
            "img-src 'self' data:",
            "font-src 'self'",
            "connect-src 'self'",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'self'");

    /** Every header the cockpit is served with, in a fixed order. */
    static final Map<String, String> HEADERS = headers();

    private CockpitSecurityHeaders() {
    }

    private static Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        headers.put("X-Content-Type-Options", "nosniff");
        headers.put("Referrer-Policy", "no-referrer");
        headers.put("X-Frame-Options", "SAMEORIGIN");
        return Map.copyOf(headers);
    }

    static void apply(HttpServletResponse response) {
        HEADERS.forEach(response::setHeader);
    }
}
