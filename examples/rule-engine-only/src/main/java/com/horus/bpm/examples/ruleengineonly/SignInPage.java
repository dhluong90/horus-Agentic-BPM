package com.horus.bpm.examples.ruleengineonly;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.core.io.Resource;

/**
 * The host's sign-in page: an HTML template with the product name, the host's form and, on the {@code dev}
 * profile, the demo user names. The template is read once at start-up; a host overrides it with
 * {@code host.sign-in.template}. Text comes from {@code sign-in/messages*.properties} in English and
 * Vietnamese. Every value is HTML-escaped; only the blocks this class builds are inserted as markup.
 *
 * <p>Passwords are never shown, and the page carries no inline script or style, so it runs under the
 * strict content security policy {@link #CONTENT_SECURITY_POLICY}.
 */
final class SignInPage {

    /** Same directives as the cockpit, except that no site may frame the sign-in page. */
    static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self'",
            "img-src 'self' data:",
            "font-src 'self'",
            "connect-src 'self'",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    static final List<String> LANGUAGES = List.of("en", "vi");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\{?)([A-Za-z][A-Za-z0-9]*)\\}?\\}\\}");
    private static final ResourceBundle.Control NO_FALLBACK = ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

    /** A demo user: name and the host roles shown beside it. */
    record DemoUser(String username, List<String> roles) {
    }

    /** What one request shows. */
    record View(String language, String csrfParameter, String csrfToken, boolean error, boolean signedOut) {
    }

    private final String template;
    private final String serviceLabel;
    private final String environment;
    private final List<DemoUser> demoUsers;

    SignInPage(Resource template, String serviceLabel, String environment, List<DemoUser> demoUsers) {
        try {
            this.template = template.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("The sign-in template " + template + " cannot be read; check host.sign-in.template", e);
        }
        this.serviceLabel = serviceLabel;
        this.environment = environment;
        this.demoUsers = List.copyOf(demoUsers);
    }

    List<DemoUser> demoUsers() {
        return demoUsers;
    }

    String render(View view) {
        ResourceBundle messages = messages(view.language());
        Map<String, String> values = new HashMap<>();
        for (String key : messages.keySet()) {
            if (!key.contains(".")) {
                values.put(key, escape(messages.getString(key)));
            }
        }
        values.put("lang", view.language());
        values.put("assets", "/sign-in");
        values.put("action", "/login");
        values.put("csrfParameter", escape(view.csrfParameter()));
        values.put("csrfToken", escape(view.csrfToken()));
        values.put("languageLinks", languageLinks(view));
        String service = serviceLabel == null || serviceLabel.isBlank() ? messages.getString("poweredBy") : serviceLabel;
        values.put("service", escape(service));
        values.put("subtitle", escape(messages.getString("subtitle").replace("{service}", service)));
        values.put("environmentTag", environment == null || environment.isBlank() ? "" : "<span class=\"tag tag--env\">" + escape(environment) + "</span>");
        values.put("alert", alert(view, messages));
        values.put("usernameInvalid", view.error() ? " aria-invalid=\"true\" aria-describedby=\"sign-in-alert\"" : "");
        values.put("passwordInvalid", view.error() ? " aria-invalid=\"true\" aria-describedby=\"sign-in-alert\"" : "");
        values.put("demo", demo(messages));
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 1024);
        while (matcher.find()) {
            String value = values.get(matcher.group(2));
            if (value == null) {
                throw new IllegalStateException("The sign-in template uses an unknown placeholder " + matcher.group());
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static ResourceBundle messages(String language) {
        return ResourceBundle.getBundle("sign-in/messages", "vi".equals(language) ? Locale.of("vi") : Locale.ROOT, NO_FALLBACK);
    }

    private static String languageLinks(View view) {
        String extra = view.error() ? "&amp;error" : view.signedOut() ? "&amp;logout" : "";
        return LANGUAGES.stream()
                .map(language -> "<a href=\"/login?lang=" + language + extra + "\" lang=\"" + language + "\" data-lang=\""
                        + language + "\" hreflang=\"" + language + "\"" + (language.equals(view.language()) ? " aria-current=\"true\"" : "")
                        + " title=\"" + ("vi".equals(language) ? "Tiếng Việt" : "English") + "\">"
                        + language.toUpperCase(Locale.ROOT) + "</a>")
                .collect(Collectors.joining());
    }

    private static String alert(View view, ResourceBundle messages) {
        if (view.error()) {
            return "<p class=\"alert alert--error\" role=\"alert\" id=\"sign-in-alert\"><span class=\"alert__icon\" aria-hidden=\"true\">!</span>"
                    + escape(messages.getString("error")) + "</p>";
        }
        if (view.signedOut()) {
            return "<p class=\"alert alert--info\" role=\"status\"><span class=\"alert__icon\" aria-hidden=\"true\">✓</span>"
                    + escape(messages.getString("signedOut")) + "</p>";
        }
        return "";
    }

    private String demo(ResourceBundle messages) {
        if (demoUsers.isEmpty()) {
            return "";
        }
        String users = demoUsers.stream().map(user -> {
            String role = capitalise(roles(user, messages));
            String pick = messages.getString("demoPick").replace("{user}", user.username()).replace("{role}", role);
            return "<li><button type=\"button\" class=\"demo__user\" data-username=\"" + escape(user.username()) + "\" aria-label=\""
                    + escape(pick) + "\"><span class=\"demo__name\">" + escape(user.username()) + "</span><span class=\"demo__role\">"
                    + escape(role) + "</span></button></li>";
        }).collect(Collectors.joining());
        return "<aside class=\"demo\" aria-labelledby=\"demo-body\"><p class=\"demo__head\"><span class=\"tag tag--demo\">"
                + escape(messages.getString("demoTag")) + "</span><span id=\"demo-body\">" + escape(messages.getString("demoBody"))
                + "</span></p><ul class=\"demo__users\">" + users + "</ul></aside>";
    }

    private static String capitalise(String text) {
        return text.isEmpty() ? text : text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
    }

    private static String roles(DemoUser user, ResourceBundle messages) {
        return user.roles().stream().map(role -> {
            try {
                return messages.getString("role." + role);
            } catch (MissingResourceException e) {
                return role;
            }
        }).collect(Collectors.joining(" " + messages.getString("roleSeparator") + " "));
    }

    static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
