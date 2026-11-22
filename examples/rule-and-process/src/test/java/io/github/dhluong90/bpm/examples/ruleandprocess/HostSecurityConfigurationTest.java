package io.github.dhluong90.bpm.examples.ruleandprocess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;

/** The example host's start-up guards for the demo users, and its CSRF cookie. */
class HostSecurityConfigurationTest {

    private static final String JWT = "spring.security.oauth2.resourceserver.jwt.";

    @Test
    void dev_refuses_tokens_that_are_not_from_this_machine() {
        MockEnvironment devAndProd = new MockEnvironment();
        devAndProd.setActiveProfiles("dev", "prod");
        MockEnvironment remoteIssuer = new MockEnvironment().withProperty(JWT + "issuer-uri", "https://sso.bank-a.vn/realms/cards");
        MockEnvironment remoteKeys = new MockEnvironment().withProperty(JWT + "jwk-set-uri", "https://sso.bank-a.vn/certs");
        MockEnvironment publicKey = new MockEnvironment().withProperty(JWT + "public-key-location", "https://keys.bank-a.vn/issuer.pub");
        MockEnvironment localKeys = new MockEnvironment().withProperty(JWT + "jwk-set-uri", "http://localhost:8180/certs");

        assertThatThrownBy(() -> HostSecurityConfiguration.requireLocalDevelopment(devAndProd)).hasMessageContaining("'prod'");
        assertThatThrownBy(() -> HostSecurityConfiguration.requireLocalDevelopment(remoteIssuer)).hasMessageContaining("issuer-uri");
        assertThatThrownBy(() -> HostSecurityConfiguration.requireLocalDevelopment(remoteKeys)).hasMessageContaining("jwk-set-uri");
        assertThatThrownBy(() -> HostSecurityConfiguration.requireLocalDevelopment(publicKey)).hasMessageContaining("public key");
        assertThatCode(() -> HostSecurityConfiguration.requireLocalDevelopment(localKeys)).doesNotThrowAnyException();
    }

    @Test
    void the_xsrf_cookie_is_secure_and_same_site_lax() {
        var repository = HostSecurityConfiguration.csrfCookies(new MockEnvironment());
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        CsrfToken token = repository.generateToken(request);

        repository.saveToken(token, request, response);

        var cookie = response.getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
        // The page reads it and sends it back in X-XSRF-TOKEN
        assertThat(cookie.isHttpOnly()).isFalse();
    }
}
