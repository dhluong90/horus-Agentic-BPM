package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.api.QueryInvalidException;
import io.github.dhluong90.bpm.rules.internal.read.RuleSetReader;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signed keyset cursors: {@code base64url("<list>:<last key>") + "." + <key id> + "." + base64url(mac)}, the MAC being the first
 * 16 bytes of HMAC-SHA256 over key id, tenant, scope (the rule set of a version list, empty for the rule set list) and the
 * payload. Only a cursor this engine issued to the same tenant for the same list is accepted (RLE-039): a forged,
 * edited, cross-tenant or cross-list cursor is {@code 400 QUERY_INVALID}. The key is one random secret per
 * database ({@code rle_engine_key}), so every replica accepts the cursors of the others.
 *
 * <p>The key id names the key that signed the cursor, so a host rotates by adding a key and making it active: cursors
 * signed with an earlier key that is still configured keep verifying, and a cursor of an unknown key, or one issued
 * before key ids existed, is {@code 400 QUERY_INVALID} (AUD-13).
 *
 * <p>The signature is integrity, not confidentiality: the payload is readable, and every query still filters by the
 * caller's tenant.
 */
final class Cursors {

    /** Longest cursor accepted, in characters; a rule set name is at most 100 characters. */
    static final int MAX_LENGTH = 400;

    private static final int MAC_BYTES = 16;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    /** A key id: 1 to 32 letters, digits, {@code _} or {@code -}. */
    static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private final String activeId;
    private final Map<String, byte[]> keys;

    /** One key held in the database; its id is derived from the key, so a replaced key is a new id. */
    Cursors(byte[] key) {
        this(dbKeyId(key), Map.of(dbKeyId(key), key == null ? new byte[0] : key));
    }

    /**
     * A ring of configured keys.
     *
     * @param activeId the key that signs new cursors
     * @param keys every key that verifies, by id, each at least 32 bytes
     */
    Cursors(String activeId, Map<String, byte[]> keys) {
        if (activeId == null || keys == null || !keys.containsKey(activeId)) {
            throw new IllegalArgumentException("the active cursor key id must name one of the keys");
        }
        Map<String, byte[]> copy = new java.util.HashMap<>();
        keys.forEach((id, key) -> {
            if (!KEY_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("cursor key id must be 1 to 32 letters, digits, _ or -");
            }
            if (key == null || key.length < 32) {
                throw new IllegalArgumentException("cursor key must be at least 32 bytes");
            }
            copy.put(id, key.clone());
        });
        this.activeId = activeId;
        this.keys = Map.copyOf(copy);
    }

    /** The id of a key held in the database: {@code db-} and the first 8 hexadecimal characters of its SHA-256. */
    static String dbKeyId(byte[] key) {
        try {
            return "db-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    key == null ? new byte[0] : key), 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    /** The unsigned payload alone; tests use it to forge cursors the engine must refuse. */
    static String encode(String list, String lastKey) {
        return ENCODER.encodeToString((list + ":" + lastKey).getBytes(StandardCharsets.UTF_8));
    }

    String sign(String tenant, String scope, String list, String lastKey) {
        String payload = encode(list, lastKey);
        return payload + "." + activeId + "." + ENCODER.encodeToString(mac(activeId, tenant, scope, payload));
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
        int first = cursor.indexOf('.');
        int second = first < 0 ? -1 : cursor.indexOf('.', first + 1);
        if (first <= 0 || second <= first + 1 || second != cursor.lastIndexOf('.')) {
            // No key id (a cursor from before key ids) or more parts than ours.
            throw invalid();
        }
        String payload = cursor.substring(0, first);
        String keyId = cursor.substring(first + 1, second);
        if (!keys.containsKey(keyId)) {
            throw invalid();
        }
        byte[] given;
        String text;
        try {
            given = Base64.getUrlDecoder().decode(cursor.substring(second + 1));
            text = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        if (!MessageDigest.isEqual(given, mac(keyId, tenant, scope, payload))) {
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

    /** The key of a decision log position: {@code <createdAt>|<id>}. */
    static String decisionKey(java.time.Instant createdAt, String id) {
        return createdAt + "|" + id;
    }

    /**
     * Returns the decision log position a key names, or null for no cursor. A signed cursor always passes; the
     * check keeps anything but an instant and a ULID away from the database even if the signature were bypassed.
     */
    static io.github.dhluong90.bpm.rules.internal.store.DecisionLog.Position decisionPosition(String key) {
        if (key == null) {
            return null;
        }
        int bar = key.indexOf('|');
        if (bar <= 0 || bar != key.lastIndexOf('|')) {
            throw invalid();
        }
        String id = key.substring(bar + 1);
        if (!id.matches("[0-9A-HJKMNP-TV-Z]{26}")) {
            throw invalid();
        }
        try {
            return new io.github.dhluong90.bpm.rules.internal.store.DecisionLog.Position(
                    java.time.Instant.parse(key.substring(0, bar)), id);
        } catch (java.time.format.DateTimeParseException e) {
            throw invalid();
        }
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

    private byte[] mac(String keyId, String tenant, String scope, String payload) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(keys.get(keyId), "HmacSHA256"));
            hmac.update(keyId.getBytes(StandardCharsets.UTF_8));
            hmac.update((byte) 0);
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
