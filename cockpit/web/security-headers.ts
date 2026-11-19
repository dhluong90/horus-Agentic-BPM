/**
 * Response headers the cockpit is served with. The embedded starter sends the same values
 * (cockpit-spring-boot-starter, CockpitSecurityHeaders; CockpitSecurityHeadersParityTest fails when the
 * two differ). `vite preview` sends them too, so the
 * end-to-end tests prove the production bundle runs under this policy.
 */
export const CONTENT_SECURITY_POLICY = [
  "default-src 'self'",
  "script-src 'self'",
  "style-src 'self'",
  "img-src 'self' data:",
  "font-src 'self'",
  "connect-src 'self'",
  "object-src 'none'",
  "base-uri 'self'",
  "form-action 'self'",
  "frame-ancestors 'self'",
].join('; ');

export const SECURITY_HEADERS: Record<string, string> = {
  'Content-Security-Policy': CONTENT_SECURITY_POLICY,
  'X-Content-Type-Options': 'nosniff',
  'Referrer-Policy': 'no-referrer',
  // The host may frame the cockpit in its own admin UI on the same origin; no other site can.
  'X-Frame-Options': 'SAMEORIGIN',
};
