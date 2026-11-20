package io.github.dhluong90.bpm.process.internal.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Completion tokens of async step attempts (ADR-0008): 256 random bits from {@link SecureRandom}, base64url without
 * padding, issued at each claim. Only the SHA-256 is stored; a presented token is hashed and compared in constant
 * time. The attempt id is derived from public ids and is never a credential. Thread-safe.
 */
final class CompletionTokens {

    /** Random bytes per token: 256 bits, above the 128-bit floor. */
    static final int TOKEN_BYTES = 32;
    /** Longest token the completion API accepts; an issued one is 43 characters. */
    static final int MAX_LENGTH = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private CompletionTokens() {
    }

    /** @return a new token; never log it */
    static String issue() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * @param token a token
     * @return its SHA-256, 32 bytes
     */
    static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * @param presented the token a caller presented, or null
     * @param storedHash the stored hash, or null when none was issued
     * @return whether they match; constant time in the token content
     */
    static boolean matches(String presented, byte[] storedHash) {
        if (presented == null || storedHash == null || presented.length() > MAX_LENGTH) {
            return false;
        }
        return MessageDigest.isEqual(hash(presented), storedHash);
    }
}
