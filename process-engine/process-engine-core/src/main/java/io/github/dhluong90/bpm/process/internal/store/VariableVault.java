package io.github.dhluong90.bpm.process.internal.store;

import io.github.dhluong90.bpm.process.api.InputInvalidException;
import io.github.dhluong90.bpm.process.api.VariableIntegrityException;
import io.github.dhluong90.bpm.process.api.VariableKeyUnavailableException;
import io.github.dhluong90.bpm.process.internal.json.Values;
import io.github.dhluong90.bpm.process.spi.VariableEncryptionRules;
import io.github.dhluong90.bpm.process.spi.VariableKeyring;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts the configured top-level variables of an instance before the row is written and decrypts them after it is
 * read (PRE-395). A protected value becomes an envelope inside the same JSON object,
 * {@code {"$enc":"aes-256-gcm","kid":"k1","iv":"...","ct":"..."}}, so the column and its type do not change. The
 * AEAD associated data binds tenant, instance id, variable name and key id, so a value copied to another row, another
 * variable or another tenant fails authentication.
 *
 * <p>It fails closed: a protected variable with no current key is refused, a stored envelope whose key id the keyring
 * does not hold is refused, and nothing is ever returned as ciphertext or written as plain text instead. An envelope is
 * opened whether or not the rules still list the variable, so switching a rule off never exposes stored ciphertext. A
 * name removed from the rules is stored in the clear again from the next write of that instance.
 *
 * <p>Messages carry key ids and variable names, never a key or a value. Thread-safe.
 */
public final class VariableVault {

    /** The reserved key of an envelope; an instance variable shaped like one is refused. */
    public static final String MARKER = "$enc";
    /** The algorithm name written into every envelope. */
    public static final String ALGORITHM = "aes-256-gcm";

    /** Encrypts nothing and has no key; stored envelopes are still refused, never passed through. */
    public static final VariableVault NONE = new VariableVault(VariableKeyring.none(), VariableEncryptionRules.NONE);

    private static final String DOCUMENT = "document";
    private static final String MARKER_TEXT = "\"" + MARKER + "\"";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final VariableKeyring keyring;
    private final VariableEncryptionRules rules;

    /**
     * @param keyring where the keys come from
     * @param rules which variables are encrypted
     */
    public VariableVault(VariableKeyring keyring, VariableEncryptionRules rules) {
        this.keyring = Objects.requireNonNull(keyring, "keyring must not be null");
        this.rules = Objects.requireNonNull(rules, "rules must not be null");
    }

    /**
     * @param definitionKey the process key
     * @param name the top-level variable name
     * @return true when the variable is encrypted at rest
     */
    public boolean encrypts(String definitionKey, String name) {
        return rules.encrypts(definitionKey, name);
    }

    /**
     * Encrypts the protected variables of an instance for storage.
     *
     * @param tenant the instance's tenant
     * @param instanceId the instance
     * @param definitionKey the process key
     * @param variablesJson the variables as the engine holds them
     * @return the JSON to store
     * @throws VariableKeyUnavailableException when a protected variable is present and no key is current
     * @throws InputInvalidException when a variable that is not protected is shaped like an envelope
     */
    public String seal(String tenant, String instanceId, String definitionKey, String variablesJson) {
        if (rules.isEmpty() && !variablesJson.contains(MARKER_TEXT)) {
            return variablesJson;
        }
        Map<String, Object> variables = Values.readMap(variablesJson);
        Map<String, Object> out = new LinkedHashMap<>();
        boolean changed = false;
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            String name = entry.getKey();
            Object value = entry.getValue();
            if (value != null && rules.encrypts(definitionKey, name)) {
                out.put(name, encrypt(tenant, instanceId, name, value));
                changed = true;
            } else if (isEnvelope(value)) {
                throw new InputInvalidException("Variable '" + name + "' uses the reserved key " + MARKER);
            } else {
                out.put(name, value);
            }
        }
        return changed ? Values.write(out) : variablesJson;
    }

    /**
     * Decrypts every envelope of a stored instance.
     *
     * @param tenant the instance's tenant
     * @param instanceId the instance
     * @param storedJson the variables as stored
     * @return the variables with their values
     * @throws VariableKeyUnavailableException when an envelope names a key the keyring does not hold
     * @throws VariableIntegrityException when an envelope does not authenticate
     */
    public String open(String tenant, String instanceId, String storedJson) {
        if (!storedJson.contains(MARKER_TEXT)) {
            return storedJson;
        }
        Map<String, Object> stored = Values.readMap(storedJson);
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : stored.entrySet()) {
            out.put(entry.getKey(), isEnvelope(entry.getValue())
                    ? decrypt(tenant, instanceId, entry.getKey(), envelope(entry.getValue(), entry.getKey()))
                    : entry.getValue());
        }
        return Values.write(out);
    }

    /**
     * Encrypts a stored answer of an idempotent call as one document, because the answer carries the instance with its
     * variables: a replay must not leave in the clear what the instance row keeps encrypted. Done whenever any variable
     * is protected; a vault with no rules leaves the answer as it is.
     *
     * @param tenant the tenant of the call
     * @param scope what the answer belongs to, for example the operation and the idempotency key
     * @param json the answer
     * @return the JSON to store
     * @throws VariableKeyUnavailableException when no key is current
     */
    public String sealDocument(String tenant, String scope, String json) {
        if (rules.isEmpty() || json == null) {
            return json;
        }
        return Values.write(encrypt(tenant, DOCUMENT, scope, json));
    }

    /**
     * Opens an answer written by {@link #sealDocument}; an answer stored in the clear, before encryption was turned on,
     * is returned as it is and ages out with the idempotency retention.
     *
     * @param tenant the tenant of the call
     * @param scope what the answer belongs to
     * @param storedJson the stored answer
     * @return the answer
     * @throws VariableKeyUnavailableException when the answer names a key the keyring does not hold
     * @throws VariableIntegrityException when it does not authenticate
     */
    public String openDocument(String tenant, String scope, String storedJson) {
        if (storedJson == null || !storedJson.contains(MARKER_TEXT)) {
            return storedJson;
        }
        Map<String, Object> stored = Values.readMap(storedJson);
        return isEnvelope(stored) ? (String) decrypt(tenant, DOCUMENT, scope, envelope(stored, scope)) : storedJson;
    }

    /**
     * Rewrites every envelope that a key other than the current one wrote, under the current key.
     *
     * @param tenant the instance's tenant
     * @param instanceId the instance
     * @param storedJson the variables as stored
     * @return the JSON to store, or null when nothing carries an older key
     * @throws VariableKeyUnavailableException when an envelope names a key the keyring does not hold, or none is current
     * @throws VariableIntegrityException when an envelope does not authenticate
     */
    public String reencrypt(String tenant, String instanceId, String storedJson) {
        if (!storedJson.contains(MARKER_TEXT)) {
            return null;
        }
        String currentId = keyring.current().map(VariableKeyring.Key::id).orElse(null);
        Map<String, Object> stored = Values.readMap(storedJson);
        Map<String, Object> out = new LinkedHashMap<>();
        boolean changed = false;
        for (Map.Entry<String, Object> entry : stored.entrySet()) {
            String name = entry.getKey();
            if (!isEnvelope(entry.getValue())) {
                out.put(name, entry.getValue());
                continue;
            }
            Envelope env = envelope(entry.getValue(), name);
            if (env.kid().equals(currentId)) {
                out.put(name, entry.getValue());
                continue;
            }
            out.put(name, encrypt(tenant, instanceId, name, decrypt(tenant, instanceId, name, env)));
            changed = true;
        }
        return changed ? Values.write(out) : null;
    }

    // ---- envelope -----------------------------------------------------------------------------------------

    private record Envelope(String kid, byte[] iv, byte[] ct) {
    }

    private static boolean isEnvelope(Object value) {
        return value instanceof Map<?, ?> map && map.containsKey(MARKER);
    }

    private static Envelope envelope(Object value, String name) {
        Map<?, ?> map = (Map<?, ?>) value;
        try {
            if (!ALGORITHM.equals(map.get(MARKER)) || map.size() != 4 || !(map.get("kid") instanceof String kid)
                    || !(map.get("iv") instanceof String iv) || !(map.get("ct") instanceof String ct)) {
                throw new IllegalArgumentException();
            }
            return new Envelope(kid, Base64.getUrlDecoder().decode(iv), Base64.getUrlDecoder().decode(ct));
        } catch (IllegalArgumentException e) {
            throw new VariableIntegrityException("The stored value of variable '" + name + "' is not a valid envelope");
        }
    }

    private Map<String, Object> encrypt(String tenant, String instanceId, String name, Object value) {
        VariableKeyring.Key key = keyring.current().orElseThrow(() -> new VariableKeyUnavailableException(
                "Variable '" + name + "' is encrypted, but the keyring has no current key"));
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.bytes(), "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(tenant, instanceId, name, key.id()));
            byte[] ct = cipher.doFinal(Values.write(Map.of("v", value)).getBytes(StandardCharsets.UTF_8));
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put(MARKER, ALGORITHM);
            envelope.put("kid", key.id());
            envelope.put("iv", Base64.getUrlEncoder().withoutPadding().encodeToString(iv));
            envelope.put("ct", Base64.getUrlEncoder().withoutPadding().encodeToString(ct));
            return envelope;
        } catch (GeneralSecurityException e) {
            throw new VariableIntegrityException("Variable '" + name + "' could not be encrypted");
        }
    }

    private Object decrypt(String tenant, String instanceId, String name, Envelope env) {
        VariableKeyring.Key key = keyring.find(env.kid()).orElseThrow(() -> new VariableKeyUnavailableException(
                "Variable '" + name + "' is encrypted under key '" + env.kid() + "', which the keyring does not hold"));
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.bytes(), "AES"),
                    new GCMParameterSpec(TAG_BITS, env.iv()));
            cipher.updateAAD(aad(tenant, instanceId, name, env.kid()));
            String plain = new String(cipher.doFinal(env.ct()), StandardCharsets.UTF_8);
            return Values.readMap(plain).get("v");
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // The cause can echo nothing secret, but a tag failure is all a caller needs to know.
            throw new VariableIntegrityException("The stored value of variable '" + name + "' failed authentication");
        }
    }

    private static byte[] aad(String tenant, String instanceId, String name, String kid) {
        return ("horus-pre-var-v1\u0000" + tenant + "\u0000" + instanceId + "\u0000" + name + "\u0000" + kid)
                .getBytes(StandardCharsets.UTF_8);
    }
}
