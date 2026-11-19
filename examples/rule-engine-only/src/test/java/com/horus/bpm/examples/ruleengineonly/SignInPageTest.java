package com.horus.bpm.examples.ruleengineonly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

/** The host's sign-in page as rendered HTML: language, states, demo notice and what it must never contain. */
class SignInPageTest {

    private static final ClassPathResource TEMPLATE = new ClassPathResource("sign-in/sign-in.html");
    private static final List<SignInPage.DemoUser> DEMO = HostSecurityConfiguration.DEMO_USERS;

    private static SignInPage.View view(String language, boolean error, boolean signedOut) {
        return new SignInPage.View(language, "_csrf", "token-<&\"'>", error, signedOut);
    }

    @Test
    void renders_the_product_the_service_and_the_form_with_the_csrf_field_in_english() {
        String html = new SignInPage(TEMPLATE, "Loan origination", "demo", List.of()).render(view("en", false, false));

        assertThat(html).contains("<html lang=\"en\">", "HORUS Cockpit", ">Sign in<", "Loan origination", "<span class=\"chip\">demo</span>")
                .contains("<form method=\"post\" action=\"/login\"")
                .contains("name=\"username\"", "name=\"password\"", "autocomplete=\"current-password\"")
                // The CSRF value is escaped, and the field keeps the shape hosts and tests read.
                .containsPattern("name=\"_csrf\"[^>]*value=\"token-&lt;&amp;&quot;&#39;&gt;\"")
                .doesNotContain("{{");
    }

    @Test
    void renders_vietnamese_and_marks_the_current_language() {
        String html = new SignInPage(TEMPLATE, "Khởi tạo khoản vay", null, List.of()).render(view("vi", false, false));

        assertThat(html).contains("<html lang=\"vi\">", "Đăng nhập", "Tên đăng nhập", "Mật khẩu")
                .contains("href=\"/login?lang=vi\" lang=\"vi\" data-lang=\"vi\" hreflang=\"vi\" aria-current=\"true\"")
                .doesNotContain("Username").doesNotContain("class=\"chip\"");
    }

    @Test
    void a_failed_sign_in_shows_the_error_in_the_page_language_and_marks_both_fields() {
        String en = new SignInPage(TEMPLATE, "Loans", null, List.of()).render(view("en", true, false));
        String vi = new SignInPage(TEMPLATE, "Loans", null, List.of()).render(view("vi", true, false));

        assertThat(en).contains("role=\"alert\"", "Wrong username or password. Try again.").contains("aria-invalid=\"true\" aria-describedby=\"sign-in-alert\"")
                .contains("/login?lang=vi&amp;error");
        assertThat(vi).contains("Sai tên đăng nhập hoặc mật khẩu. Hãy thử lại.");
    }

    @Test
    void after_sign_out_the_page_says_so() {
        assertThat(new SignInPage(TEMPLATE, "Loans", null, List.of()).render(view("en", false, true))).contains("role=\"status\"", "You have signed out.");
    }

    @Test
    void demo_users_appear_with_their_roles_only_when_given_and_never_a_password() {
        String withDemo = new SignInPage(TEMPLATE, "Loans", null, DEMO).render(view("en", false, false));
        String withoutDemo = new SignInPage(TEMPLATE, "Loans", null, List.of()).render(view("en", false, false));

        assertThat(withDemo).contains("Demo environment", "<code>lan</code><span>author</span>", "<code>hoa</code><span>author and approver</span>", "<code>tuan</code><span>teller</span>")
                .doesNotContainIgnoringCase("test-only-password").doesNotContain("HORUS_DEMO_PASSWORD");
        assertThat(withoutDemo).doesNotContain("Demo environment").doesNotContain("<code>lan</code>");
    }

    @Test
    void the_page_has_no_inline_script_style_or_event_handler_so_the_strict_policy_holds() {
        String html = new SignInPage(TEMPLATE, "Loans", "demo", DEMO).render(view("en", true, false));

        assertThat(html).doesNotContainPattern("<script(?![^>]*\\ssrc=)").doesNotContain("<style").doesNotContainPattern("\\sstyle=")
                .doesNotContainPattern("\\son[a-z]+=");
        assertThat(SignInPage.CONTENT_SECURITY_POLICY).contains("script-src 'self'", "style-src 'self'", "frame-ancestors 'none'").doesNotContain("unsafe");
    }

    @Test
    void english_and_vietnamese_messages_have_the_same_keys() throws Exception {
        java.util.Properties en = new java.util.Properties();
        java.util.Properties vi = new java.util.Properties();
        try (var a = new ClassPathResource("sign-in/messages.properties").getInputStream(); var b = new ClassPathResource("sign-in/messages_vi.properties").getInputStream()) {
            en.load(new java.io.InputStreamReader(a, StandardCharsets.UTF_8));
            vi.load(new java.io.InputStreamReader(b, StandardCharsets.UTF_8));
        }
        assertThat(vi.stringPropertyNames()).isEqualTo(en.stringPropertyNames());
    }

    @Test
    void a_host_template_with_an_unknown_placeholder_fails_loudly() {
        ByteArrayResource broken = new ByteArrayResource("<p>{{nope}}</p>".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> new SignInPage(broken, "Loans", null, List.of()).render(view("en", false, false)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("{{nope}}");
    }

    @Test
    void language_follows_the_address_then_a_vietnamese_browser_then_the_cockpit_default() {
        SignInController controller = new SignInController(new SignInPage(TEMPLATE, "Loans", null, List.of()), "en");
        assertThat(controller.language("vi", Locale.ENGLISH)).isEqualTo("vi");
        assertThat(controller.language("fr", Locale.of("vi", "VN"))).isEqualTo("vi");
        assertThat(controller.language(null, null)).isEqualTo("en");
        assertThat(new SignInController(new SignInPage(TEMPLATE, "Loans", null, List.of()), "vi").language(null, Locale.ENGLISH)).isEqualTo("vi");
    }

    @Test
    void demo_users_are_listed_on_the_dev_profile_with_a_password_only() {
        SignInConfiguration configuration = new SignInConfiguration();
        MockEnvironment production = new MockEnvironment();
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");

        assertThat(configuration.signInPage(production, TEMPLATE, "Loans", "", "secret").demoUsers()).isEmpty();
        assertThat(configuration.signInPage(dev, TEMPLATE, "Loans", "", "").demoUsers()).isEmpty();
        assertThat(configuration.signInPage(dev, TEMPLATE, "Loans", "", "secret").demoUsers()).extracting(SignInPage.DemoUser::username)
                .containsExactly("lan", "minh", "hoa", "tuan");
    }
}
