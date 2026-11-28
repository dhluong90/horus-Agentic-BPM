package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.internal.json.Hashes;
import io.github.dhluong90.bpm.rules.internal.store.StoredVersion;
import io.github.dhluong90.bpm.rules.spi.ApprovalSealKey;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The approval seal (RLE-223, ADR-0026 decision 4): a hash over what an approver approved, so the effective date,
 * which is outside the content hash, cannot move without being found. Written at approval, checked before a version is
 * compiled or served. Plain form {@code sha256:<hex>}; keyed form {@code hmac-sha256:<hex>} when the host supplies a
 * {@link ApprovalSealKey}.
 */
final class ApprovalSeal {

    static final String PLAIN = "sha256:";
    static final String KEYED = "hmac-sha256:";
    private static final int MIN_KEY_BYTES = 32;

    private final ApprovalSealKey keys;

    ApprovalSeal(ApprovalSealKey keys) {
        this.keys = keys;
    }

    /** Computes the seal of an approved version, in the keyed form when a key is set. */
    String seal(StoredVersion approved) {
        String message = message(approved);
        Optional<byte[]> key = key();
        return key.isPresent() ? KEYED + hmac(key.get(), message) : PLAIN + Hashes.sha256(message);
    }

    /**
     * Tells whether a version's row still matches what was approved.
     *
     * @return true when the version was never approved, was approved before seals existed and no key is set, or the
     *     seal matches
     */
    boolean matches(StoredVersion version) {
        if (version.approvedAt() == null) {
            return true;
        }
        Optional<byte[]> key = key();
        String stored = version.approvalSeal();
        if (stored == null) {
            // Approved before seals existed: nothing to compare. With a key set, a missing seal is what someone who
            // cleared it would leave, so it is refused.
            return key.isEmpty();
        }
        String message = message(version);
        if (stored.startsWith(KEYED)) {
            return key.isPresent() && constantTime(stored, KEYED + hmac(key.get(), message));
        }
        return key.isEmpty() && constantTime(stored, PLAIN + Hashes.sha256(message));
    }

    private Optional<byte[]> key() {
        Optional<byte[]> key = keys.key();
        if (key.isPresent() && key.get().length < MIN_KEY_BYTES) {
            throw new IllegalStateException("The approval seal key must be at least " + MIN_KEY_BYTES + " bytes");
        }
        return key;
    }

    /** Tenant, rule set, version, hash, date, approver and time, each prefixed by its length. */
    private static String message(StoredVersion v) {
        return Hashes.sha256Parts(v.tenantId(), v.ruleSetId(), Integer.toString(v.version()), v.canonicalHash(),
                v.effectiveFrom() == null ? null : v.effectiveFrom().truncatedTo(ChronoUnit.MICROS).toString(),
                v.approvedBy(),
                v.approvedAt() == null ? null : v.approvedAt().truncatedTo(ChronoUnit.MICROS).toString());
    }

    private static String hmac(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 is required by every Java platform", e);
        }
    }

    private static boolean constantTime(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
