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

    /**
     * Start of the HKDF {@code info} of a tenant subkey; the tenant id follows. The {@code v1} is part of the stored
     * token contract: changing it changes every token, so it never changes within {@code hmac:<keyId>:<hex>}.
     */
    public static final String TENANT_INFO_PREFIX = "horus-bpm/masking/v1/tenant/";

    /** Key id of a key configured without one. */
    public static final String DEFAULT_KEY_ID = "k1";

    /**
     * The message whose HMAC under a key gives the key's check value. It is not a value any input can hold in a
     * token's place, because every token is made over an input value and this label is fixed by the engine.
     */
    public static final String CHECK_VALUE_LABEL = "horus-bpm/masking/v1/kcv";

    /** Bytes of the HMAC kept in a check value. */
    public static final int CHECK_VALUE_BYTES = 8;

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
     * Derives the subkey of one tenant from a key shared by several tenants (the host key), so two tenants never get
     * the same token for one value: {@code HKDF-SHA256(this key, salt = none, info = "horus-bpm/masking/v1/tenant/"
     * + tenantId, 32 bytes)} (RFC 5869). The subkey keeps this key's id. A key configured for one tenant only is
     * used as it is and never derived.
     *
     * @param tenantId the tenant: not blank, no control character, no unpaired surrogate, so its UTF-8 bytes in the
     *     derivation are exactly the id
     * @return the tenant's subkey, with the same key id
     * @throws IllegalArgumentException for an id that cannot name a tenant; the message does not echo it
     */
    public MaskingKey forTenant(String tenantId) {
        requireUsableTenantId(tenantId);
        byte[] ikm = key.getEncoded();
        try {
            return new MaskingKey(keyId, hkdfSha256(ikm, new byte[0],
                    (TENANT_INFO_PREFIX + tenantId).getBytes(StandardCharsets.UTF_8), MIN_BYTES));
        } finally {
            java.util.Arrays.fill(ikm, (byte) 0);
        }
    }

    private static void requireUsableTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("a tenant id for a masking subkey must not be blank");
        }
        for (int i = 0; i < tenantId.length(); i++) {
            char c = tenantId.charAt(i);
            if (Character.isISOControl(c)) {
                throw new IllegalArgumentException("a tenant id for a masking subkey must not contain a control"
                        + " character");
            }
            if (Character.isHighSurrogate(c) && i + 1 < tenantId.length()
                    && Character.isLowSurrogate(tenantId.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                // An unpaired surrogate has no UTF-8 form: two different ids would derive the same subkey.
                throw new IllegalArgumentException("a tenant id for a masking subkey must not contain an unpaired"
                        + " surrogate");
            }
        }
    }

    /**
     * Tells whether two keys hold the same bytes, comparing in constant time; the key ids are not compared.
     *
     * @param other the other key
     * @return true when the key bytes are equal
     */
    public boolean sameKeyAs(MaskingKey other) {
        Objects.requireNonNull(other, "other must not be null");
        byte[] a = key.getEncoded();
        byte[] b = other.key.getEncoded();
        try {
            return java.security.MessageDigest.isEqual(a, b);
        } finally {
            java.util.Arrays.fill(a, (byte) 0);
            java.util.Arrays.fill(b, (byte) 0);
        }
    }

    /**
     * HKDF with HMAC-SHA256 (RFC 5869), extract then expand, with the JDK's {@code Mac} only.
     *
     * @param ikm the input keying material
     * @param salt the salt; empty means HashLen zero bytes, as RFC 5869 section 2.2 says
     * @param info the context
     * @param length bytes of output, 1 to 8160
     * @return the output keying material
     */
    public static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int length) {
        if (length < 1 || length > 255 * 32) {
            throw new IllegalArgumentException("HKDF-SHA256 output length must be 1 to 8160 bytes");
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(salt.length == 0 ? new byte[32] : salt, ALGORITHM));
            byte[] prk = mac.doFinal(ikm);
            mac.init(new SecretKeySpec(prk, ALGORITHM));
            byte[] okm = new byte[length];
            byte[] block = new byte[0];
            int filled = 0;
            for (int i = 1; filled < length; i++) {
                mac.update(block);
                mac.update(info);
                mac.update((byte) i);
                block = mac.doFinal();
                int n = Math.min(block.length, length - filled);
                System.arraycopy(block, 0, okm, filled, n);
                filled += n;
            }
            java.util.Arrays.fill(prk, (byte) 0);
            return okm;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available in this JVM", e);
        }
    }

    /**
     * Returns the key check value: the first {@value #CHECK_VALUE_BYTES} bytes of
     * {@code HMAC-SHA256(key, "horus-bpm/masking/v1/kcv")} as 16 lower-case hex digits. Not secret: it tells two keys
     * apart without revealing either, so it may be stored, compared and shown beside the key id. Two keys with one
     * id but different check values are two different keys.
     *
     * @return the check value, 16 hex digits
     */
    public String checkValue() {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] full = mac.doFinal(CHECK_VALUE_LABEL.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(full, 0, CHECK_VALUE_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available in this JVM", e);
        }
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
