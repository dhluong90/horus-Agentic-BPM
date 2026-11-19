package com.horus.bpm.host.autoconfigure;

import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * The correlation id of a request: the caller's {@code X-Correlation-Id} when it is a safe token, otherwise
 * a new ULID. Computed once per request and kept as a request attribute, so a problem body, the response
 * header and a log entry all carry the same value.
 */
final class CorrelationIds {

    static final String HEADER = "X-Correlation-Id";
    private static final String ATTRIBUTE = CorrelationIds.class.getName();
    /** Letters, digits, dot, underscore and hyphen only: the value goes into logs and headers. */
    private static final Pattern SAFE = Pattern.compile("^[0-9A-Za-z._-]{1,64}$");
    private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private CorrelationIds() {
    }

    static String of(HttpServletRequest request) {
        Object existing = request.getAttribute(ATTRIBUTE);
        if (existing instanceof String id) {
            return id;
        }
        String supplied = request.getHeader(HEADER);
        String id = supplied != null && SAFE.matcher(supplied).matches() ? supplied : newUlid(System.currentTimeMillis());
        request.setAttribute(ATTRIBUTE, id);
        return id;
    }

    /** A ULID: 48 bits of milliseconds and 80 random bits, 26 Crockford base32 characters. */
    static String newUlid(long epochMillis) {
        byte[] random = new byte[10];
        RANDOM.nextBytes(random);
        long high = (epochMillis << 16) | ((random[0] & 0xFFL) << 8) | (random[1] & 0xFFL);
        long low = 0;
        for (int i = 2; i < 10; i++) {
            low = (low << 8) | (random[i] & 0xFFL);
        }
        char[] chars = new char[26];
        for (int i = 25; i >= 0; i--) {
            chars[i] = CROCKFORD[(int) (low & 31)];
            low = (low >>> 5) | (high << 59);
            high >>>= 5;
        }
        return new String(chars);
    }

    /** The value made safe for a log line: unsafe values are replaced, never echoed. */
    static String loggable(String value) {
        return value != null && SAFE.matcher(value).matches() ? value : "<unprintable>";
    }
}
