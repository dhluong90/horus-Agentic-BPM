package io.github.dhluong90.bpm.process.spi;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The keys that encrypt process variables at the application level (PRE-395). Encryption is off until the host
 * configures which variables to protect ({@link VariableEncryptionRules}); then every protected value is sealed with
 * AES-256-GCM under {@link #current()}, and the envelope names the key id that wrote it, so a key rotates without
 * refusing what is already stored: make the new key {@link #current()}, keep the old one in {@link #previous()}, let
 * the re-encryption sweep rewrite the rows, then drop the old key once no row carries its id. A row whose key id is
 * listed nowhere is refused with {@code VARIABLE_KEY_UNAVAILABLE}; it is never returned as ciphertext and never
 * treated as plain text.
 *
 * <p>Hold the keys outside the database, for example from a vault or an environment variable. Implementations must be
 * thread-safe and must never log a key.
 */
public interface VariableKeyring {

    /**
     * Returns the key that encrypts new values.
     *
     * @return the key, or empty when none is set; a protected variable is then refused, never stored in clear
     */
    Optional<Key> current();

    /**
     * Returns the earlier keys that still decrypt values already written.
     *
     * @return the keys; none by default
     */
    default List<Key> previous() {
        return List.of();
    }

    /**
     * Finds the key with an id among the current and the earlier keys.
     *
     * @param id the key id read from an envelope
     * @return the key, or empty when the keyring holds none by that id
     */
    default Optional<Key> find(String id) {
        Optional<Key> now = current();
        if (now.isPresent() && now.get().id().equals(id)) {
            return now;
        }
        return previous().stream().filter(k -> k.id().equals(id)).findFirst();
    }

    /**
     * Returns the default: no key.
     *
     * @return the default
     */
    static VariableKeyring none() {
        return Optional::empty;
    }

    /**
     * Returns a keyring with one key and nothing else.
     *
     * @param key the key
     * @return the keyring
     */
    static VariableKeyring of(Key key) {
        Objects.requireNonNull(key, "key must not be null");
        return () -> Optional.of(key);
    }

    /**
     * A named AES-256 key.
     *
     * @param id the key id written into every envelope: 1 to 32 letters, digits, dots, underscores or hyphens
     * @param bytes the key, exactly 32 bytes
     */
    record Key(String id, byte[] bytes) {

        private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.-]{1,32}");
        private static final int BYTES = 32;

        public Key {
            if (id == null || !ID.matcher(id).matches()) {
                throw new IllegalArgumentException("The variable key id must be 1 to 32 letters, digits, dots,"
                        + " underscores or hyphens");
            }
            if (bytes == null || bytes.length != BYTES) {
                throw new IllegalArgumentException("The variable key must be exactly " + BYTES + " bytes (AES-256)");
            }
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public String toString() {
            return "Key[id=" + id + ", bytes=redacted]";
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key k && id.equals(k.id) && java.security.MessageDigest.isEqual(bytes, k.bytes);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }
    }
}
