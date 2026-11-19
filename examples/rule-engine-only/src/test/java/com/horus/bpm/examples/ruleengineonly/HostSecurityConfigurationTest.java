package com.horus.bpm.examples.ruleengineonly;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

/** The example host's start-up guards for the demo users and for bearer tokens. */
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
    void dev_refuses_an_issuer_that_is_not_on_this_machine() {
        MockEnvironment remote = new MockEnvironment().withProperty(JWT + "issuer-uri", "https://sso.bank-a.vn/realms/loans");
        MockEnvironment local = new MockEnvironment().withProperty(JWT + "issuer-uri", "http://localhost:8180/realms/dev");

        assertThatThrownBy(() -> HostSecurityConfiguration.requireLocalDevelopment(remote))
                .hasMessageContaining("is not on this machine");
        assertThatCode(() -> HostSecurityConfiguration.requireLocalDevelopment(local)).doesNotThrowAnyException();
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
