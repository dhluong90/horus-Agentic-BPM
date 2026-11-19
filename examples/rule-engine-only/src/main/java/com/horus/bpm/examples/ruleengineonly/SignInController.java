package com.horus.bpm.examples.ruleengineonly;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Serves the host's sign-in page at {@code GET /login}. Spring Security still processes {@code POST /login},
 * so the form, CSRF protection and the redirect back to the page the user asked for are the framework's own.
 *
 * <p>Language: {@code ?lang=en|vi}, else the browser's preferred language when it is Vietnamese, else the
 * cockpit's default language. The page script also applies the language chosen earlier in the cockpit.
 */
@Controller
class SignInController {

    private final SignInPage page;
    private final String defaultLanguage;

    SignInController(SignInPage page, @Value("${horus.bpm.cockpit.default-locale:en}") String defaultLanguage) {
        this.page = page;
        this.defaultLanguage = SignInPage.LANGUAGES.contains(defaultLanguage) ? defaultLanguage : "en";
    }

    @GetMapping(path = "/login", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    ResponseEntity<String> signIn(HttpServletRequest request, CsrfToken csrf, @RequestParam(name = "lang", required = false) String lang) {
        String language = language(lang, request.getHeader("Accept-Language") != null ? request.getLocale() : null);
        boolean error = request.getParameter("error") != null;
        boolean signedOut = !error && request.getParameter("logout") != null;
        String html = page.render(new SignInPage.View(language, csrf.getParameterName(), csrf.getToken(), error, signedOut));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy", SignInPage.CONTENT_SECURITY_POLICY)
                .header("Referrer-Policy", "no-referrer")
                .header("Content-Language", language)
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .body(html);
    }

    String language(String requested, Locale browser) {
        if (requested != null && SignInPage.LANGUAGES.contains(requested)) {
            return requested;
        }
        if (browser != null && "vi".equals(browser.getLanguage())) {
            return "vi";
        }
        return defaultLanguage;
    }
}
