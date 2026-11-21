package io.github.dhluong90.bpm.host.internal;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import org.jspecify.annotations.Nullable;

/**
 * Correlation ids and log-safe values.
 *
 * <p>Every request gets a ULID issued by this host, kept as a request attribute so that the problem body,
 * the response header and every log entry carry the same value. A caller's own {@code X-Correlation-Id}
 * is never adopted: it is logged as {@code clientCorrelationId}, so a caller cannot attach its requests to
 * another request's trail.
 *
 * <p>Internal: no compatibility promise. Hosts never import this package.
 */
public final class CorrelationIds {

    public static final String HEADER = "X-Correlation-Id";
    private static final String ATTRIBUTE = CorrelationIds.class.getName();
    private static final int LOG_VALUE_MAX = 128;
    private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private CorrelationIds() {
    }

    /** The id this host issued for the request. */
    public static String of(HttpServletRequest request) {
        if (request.getAttribute(ATTRIBUTE) instanceof String id) {
            return id;
        }
        String id = newUlid(System.currentTimeMillis());
        request.setAttribute(ATTRIBUTE, id);
        return id;
    }

    /** The caller's own correlation id, made log-safe, or {@code -} when it sent none. */
    public static String clientValue(HttpServletRequest request) {
        return loggable(request.getHeader(HEADER));
    }

    /** A ULID: 48 bits of milliseconds and 80 random bits, 26 Crockford base32 characters. */
    public static String newUlid(long epochMillis) {
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

    /**
     * The value made safe for one log line and still recoverable: letters, digits, {@code .}, {@code _} and
     * {@code -} are kept, every other UTF-8 byte is percent-encoded ({@code lan@bank-a.vn} becomes
     * {@code lan%40bank-a.vn}), and the result is cut at 128 characters with a trailing {@code ...}.
     * {@code null} becomes {@code -}.
     */
    public static String loggable(@Nullable String value) {
        if (value == null) {
            return "-";
        }
        StringBuilder out = new StringBuilder(Math.min(value.length(), LOG_VALUE_MAX) + 8);
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '.' || c == '_' || c == '-') {
                out.append(c);
            } else {
                out.append('%').append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
            }
            if (out.length() >= LOG_VALUE_MAX) {
                return out.substring(0, LOG_VALUE_MAX) + "...";
            }
        }
        return out.toString();
    }
}
