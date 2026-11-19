package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.api.QueryInvalidException;
import io.github.dhluong90.bpm.rules.internal.read.RuleSetReader;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signed keyset cursors: {@code base64url("<list>:<last key>") + "." + base64url(mac)}, the MAC being the first
 * 16 bytes of HMAC-SHA256 over tenant, scope (the rule set of a version list, empty for the rule set list) and the
 * payload. Only a cursor this engine issued to the same tenant for the same list is accepted (RLE-039): a forged,
 * edited, cross-tenant or cross-list cursor is {@code 400 QUERY_INVALID}. The key is one random secret per
 * database ({@code rle_engine_key}), so every replica accepts the cursors of the others.
 *
 * <p>The signature is integrity, not confidentiality: the payload is readable, and every query still filters by the
 * caller's tenant.
 */
final class Cursors {

    /** Longest cursor accepted, in characters; a rule set name is at most 100 characters. */
    static final int MAX_LENGTH = 400;

    private static final int MAC_BYTES = 16;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final byte[] key;

    Cursors(byte[] key) {
        if (key == null || key.length < 32) {
            throw new IllegalArgumentException("cursor key must be at least 32 bytes");
        }
        this.key = key.clone();
    }

    /** The unsigned payload alone; tests use it to forge cursors the engine must refuse. */
    static String encode(String list, String lastKey) {
        return ENCODER.encodeToString((list + ":" + lastKey).getBytes(StandardCharsets.UTF_8));
    }

    String sign(String tenant, String scope, String list, String lastKey) {
        String payload = encode(list, lastKey);
        return payload + "." + ENCODER.encodeToString(mac(tenant, scope, payload));
    }

    /**
     * Returns the key a cursor carries, or null for no cursor.
     *
     * @throws QueryInvalidException when this engine did not issue the cursor to this tenant for this list
     */
    String verify(String tenant, String scope, String list, String cursor) {
        if (cursor == null) {
            return null;
        }
        if (cursor.isEmpty() || cursor.length() > MAX_LENGTH) {
            throw invalid();
        }
        int dot = cursor.indexOf('.');
        if (dot <= 0 || dot != cursor.lastIndexOf('.')) {
            throw invalid();
        }
        String payload = cursor.substring(0, dot);
        byte[] given;
        String text;
        try {
            given = Base64.getUrlDecoder().decode(cursor.substring(dot + 1));
            text = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        if (!MessageDigest.isEqual(given, mac(tenant, scope, payload))) {
            throw invalid();
        }
        String prefix = list + ":";
        if (!text.startsWith(prefix) || text.length() == prefix.length()) {
            throw invalid();
        }
        return text.substring(prefix.length());
    }

    /**
     * Returns the key when it is a rule set name, the only key the rule set list issues, or null for no cursor.
     * A signed cursor always passes; the check keeps a NUL byte or an overlong name away from the database even if
     * the signature were ever bypassed.
     */
    static String ruleSetName(String key) {
        if (key == null) {
            return null;
        }
        if (!RuleSetReader.isRuleSetName(key)) {
            throw invalid();
        }
        return key;
    }

    static int positiveInt(String key) {
        try {
            int value = Integer.parseInt(key);
            if (value < 1) {
                throw invalid();
            }
            return value;
        } catch (NumberFormatException e) {
            throw invalid();
        }
    }

    private byte[] mac(String tenant, String scope, String payload) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(key, "HmacSHA256"));
            hmac.update(tenant.getBytes(StandardCharsets.UTF_8));
            hmac.update((byte) 0);
            hmac.update(scope.getBytes(StandardCharsets.UTF_8));
            hmac.update((byte) 0);
            return Arrays.copyOf(hmac.doFinal(payload.getBytes(StandardCharsets.UTF_8)), MAC_BYTES);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 is required by every Java platform", e);
        }
    }

    private static QueryInvalidException invalid() {
        return new QueryInvalidException("cursor was not issued by this list; pass nextCursor unchanged");
    }
}
