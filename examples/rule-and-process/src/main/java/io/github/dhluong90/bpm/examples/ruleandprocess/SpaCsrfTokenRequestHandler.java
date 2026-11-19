package io.github.dhluong90.bpm.examples.ruleandprocess;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Supplier;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

/**
 * CSRF for a single-page client beside server-rendered forms, the recipe of the Spring Security reference
 * that works on Spring Security 6.5 and 7 alike (Spring Boot 3.5 and 4).
 *
 * <p>The token is loaded on every request so that {@code CookieCsrfTokenRepository} always sets the
 * {@code XSRF-TOKEN} cookie. A client that sends it back in the {@code X-XSRF-TOKEN} header sends the raw
 * cookie value; a form sends the masked value rendered into the page, which keeps BREACH protection.
 */
final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

    private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
    private final CsrfTokenRequestHandler masked = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
        masked.handle(request, response, csrfToken);
        csrfToken.get();
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        String header = request.getHeader(csrfToken.getHeaderName());
        return (StringUtils.hasText(header) ? plain : masked).resolveCsrfTokenValue(request, csrfToken);
    }
}
