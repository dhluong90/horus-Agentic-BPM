package io.github.dhluong90.bpm.rules.spi;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The keys that turn the approval seal of a version into a keyed hash (RLE-223). Without a key the seal is a plain
 * SHA-256, which stops a casual edit of an approved date but not someone who can also recompute it. A host that needs
 * protection against a database administrator returns a key that it holds outside the database, for example from a
 * vault, and the seal becomes an HMAC-SHA256 under it (ASVS level 3). The default is no key.
 *
 * <p>Every keyed seal names the id of the key that wrote it ({@code hmac-sha256:<id>:<hex>}), so a key can be rotated
 * without refusing the versions that are already approved: list the new key as {@link #current()}, keep the old one in
 * {@link #previous()}, then re-seal the approved versions ({@code VersionService.reseal}) and drop the old key once
 * none carries its id. A key that is listed nowhere refuses the versions sealed under it. To switch a key on after
 * versions were approved, set {@link #acceptUnkeyedSeals()} to true until they are re-sealed, then set it back to
 * false.
 *
 * <p>Implementations must be thread-safe and must never log a key.
 */
public interface ApprovalSealKey {

    /**
     * Returns the key that writes new seals.
     *
     * @return the key, or empty when seals are plain hashes
     */
    Optional<Key> current();

    /**
     * Returns the earlier keys that still verify seals already written.
     *
     * @return the keys; none by default
     */
    default List<Key> previous() {
        return List.of();
    }

    /**
     * Tells whether a plain seal, or the marker of a version approved before seals existed, is accepted although a
     * {@link #current()} key is set. This is the window for switching a key on after versions were approved; it
     * defaults to false, because a plain seal is something whoever changed the row could have written.
     *
     * @return true during the window
     */
    default boolean acceptUnkeyedSeals() {
        return false;
    }

    /**
     * Returns the default: no key, plain SHA-256 seals.
     *
     * @return the default
     */
    static ApprovalSealKey none() {
        return Optional::empty;
    }

    /**
     * Returns a source with one key and nothing else.
     *
     * @param key the key
     * @return the source
     */
    static ApprovalSealKey of(Key key) {
        Objects.requireNonNull(key, "key must not be null");
        return () -> Optional.of(key);
    }

    /**
     * A named key.
     *
     * @param id the key id written into every seal: 1 to 32 letters, digits, dots, underscores or hyphens
     * @param bytes the key, at least 32 bytes
     */
    record Key(String id, byte[] bytes) {

        private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.-]{1,32}");
        private static final int MIN_BYTES = 32;

        public Key {
            if (id == null || !ID.matcher(id).matches()) {
                throw new IllegalArgumentException("The approval seal key id must be 1 to 32 letters, digits, dots,"
                        + " underscores or hyphens");
            }
            if (bytes == null || bytes.length < MIN_BYTES) {
                throw new IllegalArgumentException("The approval seal key must be at least " + MIN_BYTES + " bytes");
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
