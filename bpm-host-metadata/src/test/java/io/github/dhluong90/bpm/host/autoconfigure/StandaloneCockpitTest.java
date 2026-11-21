package io.github.dhluong90.bpm.host.autoconfigure;

import static io.github.dhluong90.bpm.host.autoconfigure.HostMetadataEndpointsTest.call;
import static io.github.dhluong90.bpm.host.autoconfigure.HostMetadataEndpointsTest.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.CorsFilter;

/**
 * A cockpit served from another origin than the host (TR-SEC-09, TR-SEC-12, ADR-0014 decision 4): CORS is off
 * unless exact origins are listed, and a token issued for another host is refused. The loans host serves
 * {@code https://loans.bank.example}; the cockpit lives at {@code https://cockpit.bank.example}.
 */
class StandaloneCockpitTest {

    private static final String COCKPIT = "https://cockpit.bank.example";
    private static final String ORIGINS = "horus.bpm.rest.cors.allowed-origins=" + COCKPIT;
    private static final String AUDIENCE = "horus.bpm.security.audiences=loans-bpm";

    // --- CORS (TR-SEC-09) ---

    @Test
    void TR_SEC_09_without_an_allowed_origin_a_preflight_from_any_origin_is_refused() {
        // Given a host that sets no allowed origin
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class).run(context -> {
            // Then the CORS filter is not applied at all
            assertThat(cors(context).isEnabled()).isFalse();

            // When any origin sends a preflight for the tenants of the engine API
            MvcResult preflight = call(mvc(context), preflight(COCKPIT));

            // Then no CORS header is sent, so the browser refuses the call
            assertThat(preflight.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
            assertThat(preflight.getResponse().getHeader("Access-Control-Allow-Methods")).isNull();
        });
    }

    @Test
    void TR_SEC_09_a_listed_origin_gets_its_preflight_answered_without_a_token() {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class)
                .withPropertyValues(ORIGINS, AUDIENCE).run(context -> {
                    // When the cockpit sends a preflight for GET /api/bpm/me with the headers it uses
                    MvcResult preflight = call(mvc(context), preflight(COCKPIT));

                    // Then the host answers for exactly that origin, with no credentials mode, before authentication
                    assertThat(cors(context).isEnabled()).isTrue();
                    assertThat(preflight.getResponse().getStatus()).isEqualTo(200);
                    assertThat(preflight.getResponse().getHeader("Access-Control-Allow-Origin")).isEqualTo(COCKPIT);
                    assertThat(preflight.getResponse().getHeader("Access-Control-Allow-Credentials")).isNull();
                    assertThat(preflight.getResponse().getHeader("Access-Control-Allow-Headers"))
                            .contains("authorization", "x-tenant-id");
                    assertThat(preflight.getResponse().getHeader("Access-Control-Max-Age")).isEqualTo("600");
                });
    }

    @Test
    void TR_SEC_09_a_refused_origin_gets_403_and_no_cors_header_on_preflight_and_on_the_call() {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class)
                .withPropertyValues(ORIGINS, AUDIENCE).run(context -> {
                    // When an origin that is not listed, a lookalike with the same host name, sends a preflight
                    // and then the call itself
                    MvcResult preflight = call(mvc(context), preflight("https://cockpit.bank.example.evil.test"));
                    MvcResult sameHostOtherScheme = call(mvc(context), preflight("http://cockpit.bank.example"));
                    MvcResult call = call(mvc(context), get("/api/bpm/me")
                            .header("Origin", "https://evil.example").with(HostMetadataEndpointsTest.lan()));

                    // Then every one is 403 without Access-Control-Allow-Origin
                    for (MvcResult refused : List.of(preflight, sameHostOtherScheme, call)) {
                        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
                        assertThat(refused.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
                        assertThat(refused.getResponse().getContentAsString()).doesNotContain("bank-a");
                    }
                });
    }

    @Test
    void TR_SEC_09_a_call_from_the_listed_origin_is_served_with_its_token_and_exposes_only_the_listed_headers() {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class)
                .withPropertyValues(ORIGINS, AUDIENCE).run(context -> {
                    MvcResult me = call(mvc(context), get("/api/bpm/me").header("Origin", COCKPIT)
                            .with(HostMetadataEndpointsTest.lan()));

                    assertThat(me.getResponse().getStatus()).isEqualTo(200);
                    assertThat(me.getResponse().getHeader("Access-Control-Allow-Origin")).isEqualTo(COCKPIT);
                    assertThat(me.getResponse().getHeader("Access-Control-Expose-Headers"))
                            .contains("ETag", "Retry-After", "X-Correlation-Id");
                    assertThat(json(me).path("tenant").path("current").asString()).isEqualTo("bank-a");
                });
    }

    @Test
    void TR_SEC_09_wildcards_and_plain_http_origins_stop_the_start_up_naming_the_property() {
        for (String origin : List.of("*", "https://*.bank.example", "https://cockpit.bank.example/",
                "https://cockpit.bank.example/app", "http://cockpit.bank.example", "cockpit.bank.example")) {
            TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class)
                    .withPropertyValues("horus.bpm.rest.cors.allowed-origins=" + origin, AUDIENCE)
                    .run(context -> assertThat(context).hasFailed().getFailure()
                            .hasStackTraceContaining("horus.bpm.rest.cors.allowed-origins"));
        }
        // A local cockpit on http is fine
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class)
                .withPropertyValues("horus.bpm.rest.cors.allowed-origins=http://localhost:5173", AUDIENCE)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void TR_SEC_12_an_allowed_origin_without_an_audience_stops_the_start_up_outside_dev() {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class).withPropertyValues(ORIGINS)
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("no token audience is")
                        .hasStackTraceContaining("horus.bpm.security.audiences"));

        // Spring Boot's own audience setting satisfies the check
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class)
                .withPropertyValues(ORIGINS, "spring.security.oauth2.resourceserver.jwt.audiences=loans-bpm")
                .run(context -> assertThat(context).hasNotFailed());

        // The dev profile is exempt
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class)
                .withPropertyValues(ORIGINS, "spring.profiles.active=dev")
                .run(context -> assertThat(context).hasNotFailed());
    }

    // --- Audience (TR-SEC-12) ---

    @Test
    void TR_SEC_12_a_token_issued_for_another_host_is_rejected_and_this_hosts_token_accepted(@TempDir Path dir)
            throws Exception {
        KeyPair keys = rsaKeys();
        Path publicKey = dir.resolve("idp.pub");
        Files.writeString(publicKey, "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(keys.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n");
        // Given the loans host with horus.bpm.security.audiences=loans-bpm and Spring Boot's decoder
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class)
                .withPropertyValues(AUDIENCE,
                        "spring.security.oauth2.resourceserver.jwt.public-key-location=file:" + publicKey)
                .run(context -> {
                    JwtDecoder decoder = context.getBean(JwtDecoder.class);

                    // When the identity provider's token for the kyc host, and one for loans, are presented
                    Jwt loans = decoder.decode(token(keys, "loans-bpm"));
                    assertThatThrownBy(() -> decoder.decode(token(keys, "kyc-bpm")))
                            .isInstanceOf(JwtValidationException.class);
                    assertThatThrownBy(() -> decoder.decode(token(keys, new String[0])))
                            .isInstanceOf(JwtValidationException.class);

                    // Then only the token for this host is accepted; a token with several audiences needs one match
                    assertThat(loans.getAudience()).containsExactly("loans-bpm");
                    assertThat(decoder.decode(token(keys, "kyc-bpm", "loans-bpm")).getAudience())
                            .contains("loans-bpm");
                });
    }

    @Test
    void TR_SEC_12_the_audience_validator_checks_the_aud_claim_and_is_absent_when_no_audience_is_set() {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class)
                .withPropertyValues("horus.bpm.security.audiences=loans-bpm, loans-admin").run(context -> {
                    @SuppressWarnings("unchecked")
                    OAuth2TokenValidator<Jwt> validator = context.getBean("horusBpmAudienceValidator",
                            OAuth2TokenValidator.class);

                    assertThat(validator.validate(jwt("loans-admin")).hasErrors()).isFalse();
                    assertThat(validator.validate(jwt("kyc-bpm")).hasErrors()).isTrue();
                });
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class)
                .run(context -> assertThat(context).doesNotHaveBean("horusBpmAudienceValidator"));
    }

    @Test
    void TR_SEC_12_an_audience_with_a_space_stops_the_start_up() {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class)
                .withPropertyValues("horus.bpm.security.audiences=loans bpm")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("horus.bpm.security.audiences"));
    }

    // --- helpers ---

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder preflight(String origin) {
        return options("/api/bpm/me").header("Origin", origin)
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization,x-tenant-id");
    }

    @SuppressWarnings("unchecked")
    private static FilterRegistrationBean<CorsFilter> cors(ApplicationContext context) {
        return context.getBean("horusBpmCorsFilter", FilterRegistrationBean.class);
    }

    /** What the container does: the CORS filter runs before the security chain, and only when it is enabled. */
    private static MockMvc mvc(ApplicationContext context) {
        var builder = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context);
        FilterRegistrationBean<CorsFilter> cors = cors(context);
        if (cors.isEnabled()) {
            builder.addFilter(cors.getFilter(), "/api/bpm/*");
        }
        return builder.apply(springSecurity()).build();
    }

    private static Jwt jwt(String audience) {
        return Jwt.withTokenValue("t").header("alg", "RS256").subject("lan").audience(List.of(audience)).build();
    }

    private static String token(KeyPair keys, String... audiences) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().subject("lan").claim("tenant_id", "bank-a")
                .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(600)));
        if (audiences.length > 0) {
            claims.audience(List.of(audiences));
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        jwt.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
        return jwt.serialize();
    }

    private static KeyPair rsaKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
