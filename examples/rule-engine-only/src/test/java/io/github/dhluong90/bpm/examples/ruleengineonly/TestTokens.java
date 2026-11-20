package io.github.dhluong90.bpm.examples.ruleengineonly;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Bearer tokens for the lifecycle tests, validated by the example host's own resource server: an RSA key made
 * per test run, the audience {@code rule-engine-only}, the tenant in {@code tenant_id} and engine permissions in
 * {@code permissions}. Users: alice (author and approver) and bob (approver) at bank-a, teller (evaluate) at
 * bank-a, carol and dave as alice and bob at tenant-b, and the auditors dan (bank-a) and erin (tenant-b) with
 * {@code rules:log:read} only.
 */
final class TestTokens {

    static final String AUDIENCE = "rule-engine-only";

    private static final KeyPair KEYS = rsaKeys();
    private static final List<String> AUTHOR_APPROVER = List.of("rules:author", "rules:approve", "rules:read", "rules:evaluate");
    private static final List<String> APPROVER = List.of("rules:approve", "rules:read", "rules:evaluate");
    private static final Map<String, Map.Entry<String, List<String>>> USERS = Map.of(
            "alice", Map.entry("bank-a", AUTHOR_APPROVER),
            "bob", Map.entry("bank-a", APPROVER),
            "teller", Map.entry("bank-a", List.of("rules:evaluate")),
            "carol", Map.entry("tenant-b", AUTHOR_APPROVER),
            "dave", Map.entry("tenant-b", APPROVER),
            "dan", Map.entry("bank-a", List.of("rules:log:read")),
            "erin", Map.entry("tenant-b", List.of("rules:log:read")));

    private TestTokens() {
    }

    /** Registers the public key and the audience with the host's token validation. */
    static void register(DynamicPropertyRegistry registry) {
        properties().forEach((name, value) -> registry.add(name, () -> value));
    }

    /** The token-validation properties of the host, for a host started with command-line arguments. */
    static Map<String, String> properties() {
        try {
            Path publicKey = Files.createTempFile("rule-lifecycle-it", ".pub");
            Files.writeString(publicKey, "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                            .encodeToString(KEYS.getPublic().getEncoded())
                    + "\n-----END PUBLIC KEY-----\n");
            publicKey.toFile().deleteOnExit();
            return Map.of("spring.security.oauth2.resourceserver.jwt.public-key-location", "file:" + publicKey,
                    "spring.security.oauth2.resourceserver.jwt.audiences", AUDIENCE);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The {@code Authorization} header value of a known user. */
    static String bearer(String user) {
        Map.Entry<String, List<String>> account = USERS.get(user);
        if (account == null) {
            throw new IllegalArgumentException("unknown test user " + user);
        }
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(user).audience(AUDIENCE).claim("tenant_id", account.getKey())
                    .claim("permissions", account.getValue())
                    .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(600)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(new RSASSASigner((RSAPrivateKey) KEYS.getPrivate()));
            return "Bearer " + jwt.serialize();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A token for any user, tenant and engine permissions ({@code permissions} claim). */
    static String bearerFor(String user, String tenant, List<String> permissions) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(user).audience(AUDIENCE).claim("tenant_id", tenant).claim("permissions", permissions)
                    .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(600)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(new RSASSASigner((RSAPrivateKey) KEYS.getPrivate()));
            return "Bearer " + jwt.serialize();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A token that carries host roles ({@code roles} claim) instead of engine permissions. */
    static String bearerWithRoles(String user, String tenant, List<String> roles) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(user).audience(AUDIENCE).claim("tenant_id", tenant).claim("roles", roles)
                    .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(600)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(new RSASSASigner((RSAPrivateKey) KEYS.getPrivate()));
            return "Bearer " + jwt.serialize();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair rsaKeys() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
