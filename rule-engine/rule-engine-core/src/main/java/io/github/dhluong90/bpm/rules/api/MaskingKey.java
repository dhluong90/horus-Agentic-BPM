package io.github.dhluong90.bpm.rules.api;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * A host-provided key for the keyed fingerprints of masked decision inputs (HMAC-SHA256, TR-SEC-05). The key bytes
 * never leave this object: it computes fingerprints itself, its {@link #toString()} is redacted and it has no
 * accessor, so the key cannot reach a log line, a response or an event by accident (TR-SEC-10). Thread-safe.
 */
public final class MaskingKey {

    /** Shortest key accepted, in bytes after Base64 decoding. */
    public static final int MIN_BYTES = 32;

    /** Prefix of every fingerprint, as stored in the decision log. */
    public static final String PREFIX = "hmac:";

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    private MaskingKey(byte[] bytes) {
        this.key = new SecretKeySpec(bytes, ALGORITHM);
    }

    /**
     * Creates a key from its bytes.
     *
     * @param bytes the key, at least {@value #MIN_BYTES} bytes; copied
     * @return the key
     * @throws IllegalArgumentException when the key is shorter, with a message that never contains the key
     */
    public static MaskingKey of(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes must not be null");
        if (bytes.length < MIN_BYTES) {
            throw new IllegalArgumentException("a masking key must be at least " + MIN_BYTES
                    + " bytes after Base64 decoding, this one has " + bytes.length);
        }
        return new MaskingKey(bytes.clone());
    }

    /**
     * Creates a key from Base64 text (standard or URL-safe alphabet, padding optional).
     *
     * @param base64 the encoded key
     * @return the key
     * @throws IllegalArgumentException when the text is blank, not Base64 or decodes to fewer than
     *     {@value #MIN_BYTES} bytes; the message never contains the text
     */
    public static MaskingKey fromBase64(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalArgumentException("a masking key is required");
        }
        String text = base64.strip();
        byte[] bytes;
        try {
            bytes = text.indexOf('-') >= 0 || text.indexOf('_') >= 0
                    ? Base64.getUrlDecoder().decode(text) : Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("a masking key must be Base64 text");
        }
        return of(bytes);
    }

    /**
     * Returns the fingerprint of a message: {@value #PREFIX} followed by the lower-case hex of its HMAC-SHA256.
     *
     * @param message the bytes to fingerprint
     * @return the fingerprint
     */
    public String fingerprint(byte[] message) {
        Objects.requireNonNull(message, "message must not be null");
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return PREFIX + HexFormat.of().formatHex(mac.doFinal(message));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available in this JVM", e);
        }
    }

    /**
     * Returns the fingerprint of a text: the HMAC-SHA256 of its UTF-8 bytes.
     *
     * @param text the text
     * @return the fingerprint
     */
    public String fingerprint(String text) {
        return fingerprint(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Tells whether a text has the form of a fingerprint: the prefix and 64 lower-case hex digits.
     *
     * @param text the text
     * @return true for {@code hmac:<64 hex digits>}
     */
    public static boolean isFingerprint(String text) {
        return text != null && text.matches("hmac:[0-9a-f]{64}");
    }

    @Override
    public String toString() {
        return "MaskingKey[redacted]";
    }
}
