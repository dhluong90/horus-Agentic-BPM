package io.github.dhluong90.bpm.rules.internal.json;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 in lower-case hex. */
public final class Hashes {

    private Hashes() {
    }

    /**
     * Hashes bytes.
     *
     * @param bytes the input
     * @return 64 lower-case hex characters
     */
    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    /**
     * Hashes the UTF-8 bytes of a text.
     *
     * @param text the input
     * @return 64 lower-case hex characters
     */
    public static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Hashes several parts unambiguously, each part prefixed with its length.
     *
     * @param parts the parts; null is hashed as an empty marker distinct from ""
     * @return 64 lower-case hex characters
     */
    public static String sha256Parts(String... parts) {
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            if (part == null) {
                joined.append("-1:");
            } else {
                joined.append(part.length()).append(':').append(part);
            }
            joined.append('|');
        }
        return sha256(joined.toString());
    }
}
