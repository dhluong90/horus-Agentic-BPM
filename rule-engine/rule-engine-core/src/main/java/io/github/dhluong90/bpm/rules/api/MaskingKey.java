package io.github.dhluong90.bpm.rules.api;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * A host-provided key for the keyed fingerprints of masked decision inputs (HMAC-SHA256, TR-SEC-05), with a short
 * key id that every fingerprint carries ({@code hmac:<keyId>:<hex>}), so a row in the permanent decision log always
 * says which key made it and a tenant can rotate keys without stranding old rows. The key bytes never leave this
 * object: it computes fingerprints itself, its {@link #toString()} is redacted and it has no
 * accessor, so the key cannot reach a log line, a response or an event by accident (TR-SEC-10). Thread-safe.
 */
public final class MaskingKey {

    /** Shortest key accepted, in bytes after Base64 decoding. */
    public static final int MIN_BYTES = 32;

    /** Prefix of every fingerprint, as stored in the decision log. */
    public static final String PREFIX = "hmac:";

    /** Key id of a key configured without one. */
    public static final String DEFAULT_KEY_ID = "k1";

    private static final String ALGORITHM = "HmacSHA256";
    private static final java.util.regex.Pattern KEY_ID = java.util.regex.Pattern.compile("^[A-Za-z0-9_-]{1,32}$");
    private static final java.util.regex.Pattern FINGERPRINT =
            java.util.regex.Pattern.compile("^hmac:[A-Za-z0-9_-]{1,32}:[0-9a-f]{64}$");

    private final String keyId;
    private final SecretKeySpec key;

    private MaskingKey(String keyId, byte[] bytes) {
        this.keyId = keyId;
        this.key = new SecretKeySpec(bytes, ALGORITHM);
    }

    /**
     * Creates a key from its id and bytes.
     *
     * @param keyId the key id, 1 to 32 characters of letters, digits, {@code _} and {@code -}; not secret
     * @param bytes the key, at least {@value #MIN_BYTES} bytes; copied
     * @return the key
     * @throws IllegalArgumentException for an invalid id or a short key, with a message that never contains the key
     */
    public static MaskingKey of(String keyId, byte[] bytes) {
        if (keyId == null || !KEY_ID.matcher(keyId).matches()) {
            throw new IllegalArgumentException("a masking key id must be 1 to 32 letters, digits, '_' or '-'");
        }
        Objects.requireNonNull(bytes, "bytes must not be null");
        if (bytes.length < MIN_BYTES) {
            throw new IllegalArgumentException("a masking key must be at least " + MIN_BYTES
                    + " bytes after Base64 decoding, this one has " + bytes.length);
        }
        return new MaskingKey(keyId, bytes.clone());
    }

    /**
     * Creates a key from its id and Base64 text.
     *
     * @param keyId the key id
     * @param base64 the encoded key
     * @return the key
     * @throws IllegalArgumentException as {@link #fromBase64(String)} and {@link #of(String, byte[])}
     */
    public static MaskingKey fromBase64(String keyId, String base64) {
        return of(keyId, decode(base64));
    }

    /**
     * Returns the key id, which every fingerprint of this key carries. Not secret.
     *
     * @return the key id
     */
    public String keyId() {
        return keyId;
    }

    /**
     * Creates a key with id {@value #DEFAULT_KEY_ID} from its bytes.
     *
     * @param bytes the key, at least {@value #MIN_BYTES} bytes; copied
     * @return the key, with id {@value #DEFAULT_KEY_ID}
     * @throws IllegalArgumentException when the key is shorter, with a message that never contains the key
     */
    public static MaskingKey of(byte[] bytes) {
        return of(DEFAULT_KEY_ID, bytes);
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
        return of(DEFAULT_KEY_ID, decode(base64));
    }

    private static byte[] decode(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalArgumentException("a masking key is required");
        }
        String text = base64.strip();
        try {
            return text.indexOf('-') >= 0 || text.indexOf('_') >= 0
                    ? Base64.getUrlDecoder().decode(text) : Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("a masking key must be Base64 text");
        }
    }

    /**
     * Returns the fingerprint of a message: {@value #PREFIX}, the key id, a colon and the lower-case hex of its
     * HMAC-SHA256.
     *
     * @param message the bytes to fingerprint
     * @return the fingerprint
     */
    public String fingerprint(byte[] message) {
        Objects.requireNonNull(message, "message must not be null");
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return PREFIX + keyId + ":" + HexFormat.of().formatHex(mac.doFinal(message));
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
     * Tells whether a text has the form of a fingerprint: the prefix, a key id, a colon and 64 lower-case hex digits.
     *
     * @param text the text
     * @return true for {@code hmac:<keyId>:<64 hex digits>}
     */
    public static boolean isFingerprint(String text) {
        return text != null && FINGERPRINT.matcher(text).matches();
    }

    @Override
    public String toString() {
        return "MaskingKey[keyId=" + keyId + ", key=redacted]";
    }
}
