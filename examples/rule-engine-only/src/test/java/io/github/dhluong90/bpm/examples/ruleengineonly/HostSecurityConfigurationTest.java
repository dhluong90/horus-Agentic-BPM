package io.github.dhluong90.bpm.examples.ruleengineonly;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

/** The example host's start-up guards for the demo users and for bearer tokens. */
@ExtendWith(OutputCaptureExtension.class)
class HostSecurityConfigurationTest {

    private static final String JWT = "spring.security.oauth2.resourceserver.jwt.";

    @Test
    void dev_must_run_alone_or_with_test_only() {
        MockEnvironment devAndProd = new MockEnvironment();
        devAndProd.setActiveProfiles("dev", "prod");
        MockEnvironment devAndTest = new MockEnvironment();
        devAndTest.setActiveProfiles("dev", "test");

        assertThatThrownBy(() -> HostSecurityConfiguration.requireLocalDevelopment(devAndProd))
                .hasMessageContaining("profile 'prod' is active too");
        assertThatCode(() -> HostSecurityConfiguration.requireLocalDevelopment(devAndTest)).doesNotThrowAnyException();
    }

    @Test
    void demo_users_log_a_warning_that_they_are_for_local_evaluation_only(CapturedOutput output) {
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");

        new HostSecurityConfiguration().hostUsers(dev, "demo-only");

        assertThat(output).contains("Demo users are on (dev profile): [lan, minh, hoa, tuan] share one password")
                .contains("Local evaluation only")
                .doesNotContain("demo-only");
    }

    @Test
    void no_demo_users_and_no_warning_outside_dev(CapturedOutput output) {
        new HostSecurityConfiguration().hostUsers(new MockEnvironment(), "demo-only");

        assertThat(output).doesNotContain("Demo users are on");
    }

    @Test
    void dev_refuses_an_issuer_that_is_not_on_this_machine() {
        MockEnvironment remote = new MockEnvironment().withProperty(JWT + "issuer-uri", "https://sso.bank-a.vn/realms/loans");
        MockEnvironment local = new MockEnvironment().withProperty(JWT + "issuer-uri", "http://localhost:8180/realms/dev");

        assertThatThrownBy(() -> HostSecurityConfiguration.requireLocalDevelopment(remote))
                .hasMessageContaining("is not on this machine");
        assertThatCode(() -> HostSecurityConfiguration.requireLocalDevelopment(local)).doesNotThrowAnyException();
    }

    @Test
    void dev_refuses_a_key_set_or_public_key_that_is_not_on_this_machine() {
        MockEnvironment remoteKeys = new MockEnvironment().withProperty(JWT + "jwk-set-uri", "https://sso.bank-a.vn/certs");
        MockEnvironment localKeys = new MockEnvironment().withProperty(JWT + "jwk-set-uri", "http://127.0.0.1:8180/certs");
        MockEnvironment publicKey = new MockEnvironment().withProperty(JWT + "public-key-location", "https://keys.bank-a.vn/issuer.pub");
        MockEnvironment localKey = new MockEnvironment().withProperty(JWT + "public-key-location", "classpath:issuer.pub");

        assertThatThrownBy(() -> HostSecurityConfiguration.requireLocalDevelopment(remoteKeys))
                .hasMessageContaining("jwk-set-uri").hasMessageContaining("is not on this machine");
        assertThatCode(() -> HostSecurityConfiguration.requireLocalDevelopment(localKeys)).doesNotThrowAnyException();
        assertThatThrownBy(() -> HostSecurityConfiguration.requireLocalDevelopment(publicKey))
                .hasMessageContaining("public key https://keys.bank-a.vn/issuer.pub");
        assertThatCode(() -> HostSecurityConfiguration.requireLocalDevelopment(localKey)).doesNotThrowAnyException();
    }

    @Test
    void the_xsrf_cookie_is_secure_and_same_site_lax() {
        var repository = HostSecurityConfiguration.csrfCookies(new MockEnvironment());
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        repository.saveToken(repository.generateToken(request), request, response);

        var cookie = response.getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
        // The page reads it and sends it back in X-XSRF-TOKEN
        assertThat(cookie.isHttpOnly()).isFalse();
    }

    @Test
    void TR_SEC_12_a_host_that_accepts_tokens_must_name_its_audience() {
        MockEnvironment noAudience = new MockEnvironment().withProperty(JWT + "issuer-uri", "https://sso.bank-a.vn/realms/loans");
        MockEnvironment withAudience = new MockEnvironment()
                .withProperty(JWT + "issuer-uri", "https://sso.bank-a.vn/realms/loans")
                .withProperty(JWT + "audiences", "rule-engine-only");

        assertThatThrownBy(() -> HostSecurityConfiguration.requireAudience(noAudience))
                .hasMessageContaining("names no audience");
        assertThatCode(() -> HostSecurityConfiguration.requireAudience(withAudience)).doesNotThrowAnyException();
        assertThatCode(() -> HostSecurityConfiguration.requireAudience(new MockEnvironment())).doesNotThrowAnyException();
    }

    @Test
    void CKP_001_sign_in_returns_to_the_page_asked_for_never_to_a_favicon_or_a_background_call() {
        assertThat(remembered(get("/bpm/cockpit/"))).isTrue();
        assertThat(remembered(get("/favicon.ico"))).isFalse();
        assertThat(remembered(get("/api/bpm/me"))).isFalse();
        MockHttpServletRequest background = get("/bpm/cockpit/cockpit-hosts.json");
        background.addHeader("X-Requested-With", "XMLHttpRequest");
        assertThat(remembered(background)).isFalse();
    }

    private static MockHttpServletRequest get(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        return request;
    }

    private static boolean remembered(MockHttpServletRequest request) {
        HttpSessionRequestCache cache = HostSecurityConfiguration.pagesOnly(
                PathPatternRequestMatcher.withDefaults().matcher("/api/bpm/**"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        cache.saveRequest(request, response);
        return cache.getRequest(request, response) != null;
    }
}
