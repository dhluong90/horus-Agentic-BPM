package com.horus.bpm.examples.ruleengineonly;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The example host with the cockpit starter, over real HTTP with the host's form sign-in. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "HORUS_DEMO_PASSWORD=test-only-password")
@ActiveProfiles("dev")
@Testcontainers
class CockpitEmbeddedIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private Environment environment;

    private String url(String path) {
        return "http://localhost:" + environment.getProperty("local.server.port") + path;
    }

    @Test
    void TR_SEC_09_without_a_host_session_the_cockpit_sends_the_user_to_the_host_sign_in() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        for (String path : new String[] {"/bpm/cockpit/", "/bpm/cockpit/index.html", "/bpm/cockpit/cockpit-hosts.json"}) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(url(path))).header("Accept", "text/html").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(path).isEqualTo(302);
            assertThat(response.headers().firstValue("Location")).as(path).hasValueSatisfying(l -> assertThat(l).endsWith("/login"));
            assertThat(response.body()).as(path).doesNotContain("<div id=\"root\">").doesNotContain("Loan origination");
        }
    }

    @Test
    void CKP_001_signed_in_user_opens_the_cockpit_and_its_one_host_registry() throws Exception {
        HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        signIn(client, "lan");

        HttpResponse<String> page = client.send(
                HttpRequest.newBuilder(URI.create(url("/bpm/cockpit/index.html"))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("<div id=\"root\">");
        assertThat(page.headers().firstValue("Content-Security-Policy")).hasValueSatisfying(csp ->
                assertThat(csp).contains("script-src 'self'").doesNotContain("unsafe-inline"));
        assertThat(page.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        // Same-origin framing only, even though the host's Spring Security sends DENY everywhere else.
        assertThat(page.headers().firstValue("X-Frame-Options")).hasValue("SAMEORIGIN");

        HttpResponse<String> registry = client.send(
                HttpRequest.newBuilder(URI.create(url("/bpm/cockpit/cockpit-hosts.json"))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(registry.statusCode()).isEqualTo(200);
        assertThat(registry.body()).isEqualTo("{\"schemaVersion\":1,\"auth\":{\"mode\":\"host-session\"},"
                + "\"hosts\":[{\"id\":\"rule-engine-only\",\"label\":\"Loan origination\",\"baseUrl\":\"\",\"environment\":\"demo\"}],"
                + "\"ui\":{\"defaultLocale\":\"en\"}}");
    }

    private void signIn(HttpClient client, String user) throws Exception {
        HttpResponse<String> form = client.send(
                HttpRequest.newBuilder(URI.create(url("/login"))).GET().build(), HttpResponse.BodyHandlers.ofString());
        Matcher csrf = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(form.body());
        assertThat(csrf.find()).as("CSRF token on the sign-in form").isTrue();
        String body = "username=" + user + "&password=test-only-password&_csrf="
                + URLEncoder.encode(csrf.group(1), StandardCharsets.UTF_8);
        HttpResponse<String> result = client.send(
                HttpRequest.newBuilder(URI.create(url("/login")))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(result.statusCode()).isEqualTo(302);
        assertThat(result.headers().firstValue("Location")).hasValueSatisfying(l -> assertThat(l).doesNotContain("error"));
    }
}
