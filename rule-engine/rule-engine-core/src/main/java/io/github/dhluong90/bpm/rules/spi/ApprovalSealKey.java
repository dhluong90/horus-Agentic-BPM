package io.github.dhluong90.bpm.rules.spi;

import java.util.Optional;

/**
 * The key that turns the approval seal of a version into a keyed hash (RLE-223). Without a key the seal is a plain
 * SHA-256, which stops a casual edit of an approved date but not someone who can also recompute it. A host that needs
 * protection against a database administrator returns a key that it holds outside the database, for example from a
 * vault, and the seal becomes an HMAC-SHA256 under it (ASVS level 3). The default is no key.
 *
 * <p>With a key, the engine writes every new seal as an HMAC and refuses a version whose seal is a plain hash, because
 * a plain hash could have been written by whoever changed the row. Turn the key on before the first approval, or approve
 * the versions again. Implementations must be thread-safe and must never log the key.
 */
@FunctionalInterface
public interface ApprovalSealKey {

    /**
     * Returns the key.
     *
     * @return at least 32 bytes, or empty when seals are plain hashes
     */
    Optional<byte[]> key();

    /**
     * Returns the default: no key, plain SHA-256 seals.
     *
     * @return the default
     */
    static ApprovalSealKey none() {
        return Optional::empty;
    }
}
