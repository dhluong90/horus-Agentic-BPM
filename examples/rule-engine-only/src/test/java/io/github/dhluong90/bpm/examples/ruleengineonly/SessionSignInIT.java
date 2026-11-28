package io.github.dhluong90.bpm.examples.ruleengineonly;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The host-session path the embedded cockpit uses, on the {@code dev} profile: sign in with the host's
 * form, then call the engine endpoints with the session cookie. The tenant comes from the signed-in
 * principal, never from a header.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // The test client is java.net.CookieManager over plain http, which never returns a Secure cookie;
        // SecureCookiesIT checks the attributes the example sends by default.
        properties = {"HORUS_DEMO_PASSWORD=test-only-password", "server.servlet.session.cookie.secure=false"})
@ActiveProfiles("dev")
@Testcontainers
class SessionSignInIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(TestImages.postgres());

    // Plain properties, not @ServiceConnection: the same test runs on both Spring Boot lines (TR-EMB-02).
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private Environment environment;

    @Test
    void RLE_019_signed_in_users_get_meta_and_me_with_their_session() throws Exception {
        for (String user : List.of("lan", "minh")) {
            HttpClient browser = signedIn(user);

            HttpResponse<String> meta = get(browser, "/api/bpm/meta");
            HttpResponse<String> me = get(browser, "/api/bpm/me");

            assertThat(meta.statusCode()).as(user).isEqualTo(200);
            assertThat(JSON.readTree(meta.body()).path("hostId").asString()).isEqualTo("rule-engine-only");
            // PD-094: the dev profile of this example turns cockpit draft editing on for the demo.
            assertThat(JSON.readTree(meta.body()).path("modules").get(0).path("settings").path("authoring.cockpit-drafts").asBoolean()).isTrue();
            assertThat(me.statusCode()).as(user).isEqualTo(200);
            JsonNode body = JSON.readTree(me.body());
            assertThat(body.path("user").path("id").asString()).isEqualTo(user);
            assertThat(body.path("tenant").path("current").asString()).isEqualTo("bank-a");
            assertThat(strings(body.path("permissions"))).containsExactly(
                    user.equals("lan") ? new String[] {"rules:read", "rules:author"} : new String[] {"rules:read", "rules:approve", "rules:simulate"});
        }
    }

    @Test
    void TR_SEC_03_a_session_caller_cannot_choose_its_tenant_with_a_header() throws Exception {
        HttpClient browser = signedIn("lan");

        HttpResponse<String> response = browser.send(HttpRequest.newBuilder(url("/api/bpm/me"))
                .header("X-Tenant-Id", "bank-b").GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(JSON.readTree(response.body()).path("code").asString()).isEqualTo("TENANT_NOT_ALLOWED");
    }

    @Test
    void session_callers_keep_csrf_protection_on_state_changing_calls() throws Exception {
        CookieManager cookies = new CookieManager();
        HttpClient browser = signedIn("lan", cookies);

        HttpResponse<String> withoutToken = browser.send(HttpRequest.newBuilder(url("/api/bpm/me"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        get(browser, "/api/bpm/me");
        String xsrf = cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("XSRF-TOKEN")).map(HttpCookie::getValue).findFirst().orElseThrow();
        HttpResponse<String> withToken = browser.send(HttpRequest.newBuilder(url("/api/bpm/me"))
                .header("X-XSRF-TOKEN", xsrf).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());

        // Without the token the CSRF filter refuses the call as a problem; with it the call reaches Spring MVC,
        // which answers that /me has no POST
        assertThat(withoutToken.statusCode()).isEqualTo(403);
        assertThat(JSON.readTree(withoutToken.body()).path("code").asString()).isEqualTo("PERMISSION_DENIED");
        assertThat(withToken.statusCode()).isEqualTo(405);
    }

    @Test
    void the_cockpit_and_other_pages_still_redirect_to_the_sign_in_form() throws Exception {
        HttpResponse<String> page = HttpClient.newHttpClient().send(HttpRequest.newBuilder(url("/some/page"))
                .header("Accept", "text/html").GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(page.statusCode()).isEqualTo(302);
        assertThat(page.headers().firstValue("Location")).hasValueSatisfying(l -> assertThat(l).endsWith("/login"));
    }

    private HttpClient signedIn(String user) throws Exception {
        return signedIn(user, new CookieManager());
    }

    private HttpClient signedIn(String user, CookieManager cookies) throws Exception {
        HttpClient browser = HttpClient.newBuilder().cookieHandler(cookies).build();
        HttpResponse<String> form = browser.send(HttpRequest.newBuilder(url("/login")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Matcher csrf = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(form.body());
        assertThat(csrf.find()).as("CSRF token on the sign-in form").isTrue();
        HttpResponse<String> result = browser.send(HttpRequest.newBuilder(url("/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=" + user + "&password=test-only-password&_csrf="
                        + URLEncoder.encode(csrf.group(1), StandardCharsets.UTF_8)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(result.statusCode()).isEqualTo(302);
        assertThat(result.headers().firstValue("Location")).hasValueSatisfying(l -> assertThat(l).doesNotContain("error"));
        return browser;
    }

    private HttpResponse<String> get(HttpClient browser, String path) throws Exception {
        return browser.send(HttpRequest.newBuilder(url(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI url(String path) {
        return URI.create("http://127.0.0.1:" + environment.getProperty("local.server.port") + path);
    }

    private static List<String> strings(JsonNode array) {
        return array.valueStream().map(JsonNode::asString).toList();
    }
}
