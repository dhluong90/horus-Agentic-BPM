package com.horus.bpm.cockpit.autoconfigure;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Response headers on every cockpit page and asset (cockpit DESIGN section 13, TR-SEC-09).
 * The cockpit has no inline script or style, so {@code script-src 'self'} and {@code style-src 'self'} hold.
 * {@code connect-src 'self'}: an embedded cockpit talks only to the host that serves it.
 * The same values are used by {@code cockpit/web/security-headers.ts} for the front-end tests.
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

    private CockpitSecurityHeaders() {
    }

    static void apply(HttpServletResponse response) {
        response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
    }
}
