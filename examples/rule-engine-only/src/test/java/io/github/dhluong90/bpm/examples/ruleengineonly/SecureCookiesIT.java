package io.github.dhluong90.bpm.examples.ruleengineonly;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
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

/**
 * The example's cookie and CSRF defaults, which hosts copy: the session cookie and {@code XSRF-TOKEN} carry
 * {@code Secure} and {@code SameSite=Lax}, and without a token decoder a junk bearer header does not turn the
 * CSRF check off. The {@code Set-Cookie} headers are read raw, with no cookie store in between.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "HORUS_DEMO_PASSWORD=test-only-password")
@ActiveProfiles("dev")
@Testcontainers
class SecureCookiesIT {

    private static final Pattern CSRF_FIELD = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private Environment environment;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void session_and_xsrf_cookies_are_secure_and_same_site_lax() throws Exception {
        SignedIn signedIn = signIn();

        assertThat(signedIn.xsrfSetCookie()).startsWith("XSRF-TOKEN=").containsIgnoringCase("; Secure")
                .containsIgnoringCase("SameSite=Lax");
        assertThat(signedIn.sessionSetCookie()).startsWith("JSESSIONID=").containsIgnoringCase("; Secure")
                .containsIgnoringCase("HttpOnly").containsIgnoringCase("SameSite=Lax");
    }

    @Test
    void without_a_token_decoder_a_junk_bearer_header_still_needs_the_csrf_token() throws Exception {
        SignedIn signedIn = signIn();

        HttpResponse<String> forged = client.send(HttpRequest.newBuilder(url("/api/bpm/rules/v1/rule-sets"))
                .header("Cookie", signedIn.session())
                .header("Authorization", "Bearer junk")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(forged.statusCode()).isEqualTo(403);
    }

    private record SignedIn(String xsrfSetCookie, String sessionSetCookie) {
        String session() {
            return sessionSetCookie.substring(0, sessionSetCookie.indexOf(';'));
        }
    }

    private SignedIn signIn() throws Exception {
        HttpResponse<String> form = client.send(HttpRequest.newBuilder(url("/login")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String xsrf = setCookie(form.headers().allValues("Set-Cookie"), "XSRF-TOKEN=").orElseThrow();
        Matcher csrf = CSRF_FIELD.matcher(form.body());
        assertThat(csrf.find()).as("CSRF token on the sign-in form").isTrue();
        HttpResponse<String> result = client.send(HttpRequest.newBuilder(url("/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cookie", xsrf.substring(0, xsrf.indexOf(';')))
                .POST(HttpRequest.BodyPublishers.ofString("username=lan&password=test-only-password&_csrf="
                        + URLEncoder.encode(csrf.group(1), StandardCharsets.UTF_8)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(result.statusCode()).isEqualTo(302);
        String session = setCookie(result.headers().allValues("Set-Cookie"), "JSESSIONID=").orElseThrow();
        return new SignedIn(xsrf, session);
    }

    private static Optional<String> setCookie(List<String> headers, String prefix) {
        return headers.stream().filter(header -> header.startsWith(prefix)).findFirst();
    }

    private URI url(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + path);
    }
}
