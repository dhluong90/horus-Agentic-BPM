package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.internal.json.Hashes;
import io.github.dhluong90.bpm.rules.internal.store.StoredVersion;
import io.github.dhluong90.bpm.rules.spi.ApprovalSealKey;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The approval seal (RLE-223, ADR-0026 decision 4): a hash over what an approver approved, so the effective date,
 * which is outside the content hash, cannot move without being found. Written at approval, checked before a version is
 * compiled or served. Plain form {@code sha256:<hex>}; keyed form {@code hmac-sha256:<keyId>:<hex>} when the host
 * supplies an {@link ApprovalSealKey}, verified against its current and previous keys.
 */
final class ApprovalSeal {

    static final String PLAIN = "sha256:";
    static final String KEYED = "hmac-sha256:";
    /** Written by migration {@code V2} on a version approved before seals existed; nothing writes it afterwards. */
    static final String LEGACY = "legacy:pre-seal";

    private final ApprovalSealKey keys;

    ApprovalSeal(ApprovalSealKey keys) {
        this.keys = keys;
    }

    /** Computes the seal of an approved version, in the keyed form when a key is set. */
    String seal(StoredVersion approved) {
        String message = message(approved);
        Optional<ApprovalSealKey.Key> key = keys.current();
        return key.isPresent() ? KEYED + key.get().id() + ":" + hmac(key.get().bytes(), message)
                : PLAIN + Hashes.sha256(message);
    }

    /**
     * Tells whether a version's row still matches what was approved. Fails closed: an approved version with no seal
     * is refused, because only migration {@code V2} writes {@link #LEGACY} for versions approved before seals existed.
     *
     * @return true when the version was never approved or its seal verifies against the current or a previous key
     */
    boolean matches(StoredVersion version) {
        if (version.approvedAt() == null) {
            return true;
        }
        String stored = version.approvalSeal();
        if (stored == null) {
            return false;
        }
        boolean unkeyedOk = keys.current().isEmpty() || keys.acceptUnkeyedSeals();
        if (LEGACY.equals(stored)) {
            return unkeyedOk;
        }
        String message = message(version);
        if (stored.startsWith(KEYED)) {
            String[] parts = stored.substring(KEYED.length()).split(":", -1);
            if (parts.length != 2) {
                return false;
            }
            return keyring().stream().filter(k -> k.id().equals(parts[0])).findFirst()
                    .map(k -> constantTime(stored, KEYED + k.id() + ":" + hmac(k.bytes(), message))).orElse(false);
        }
        return unkeyedOk && constantTime(stored, PLAIN + Hashes.sha256(message));
    }

    /**
     * Tells whether a version that {@link #matches} did so only because the {@code acceptUnkeyedSeals} window is open:
     * a key is set and the seal is plain or the pre-seal marker.
     */
    boolean acceptedUnkeyed(StoredVersion version) {
        String stored = version.approvalSeal();
        return keys.current().isPresent() && stored != null && !stored.startsWith(KEYED);
    }

    /** Tells whether the stored seal is not what {@link #seal} writes now, so the version should be re-sealed. */
    boolean isStale(StoredVersion version) {
        return version.approvalSeal() == null || !version.approvalSeal().equals(seal(version));
    }

    private List<ApprovalSealKey.Key> keyring() {
        List<ApprovalSealKey.Key> ring = new ArrayList<>();
        keys.current().ifPresent(ring::add);
        ring.addAll(keys.previous());
        return ring;
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
