package io.github.dhluong90.bpm.examples.ruleengineonly;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.core.io.Resource;

/**
 * The host's sign-in page: an HTML template with the product name, the host's form and, on the {@code dev}
 * profile, the demo user names. The template is read once at start-up; a host overrides it with
 * {@code host.sign-in.template}. Text comes from {@code sign-in/messages*.properties} in English and
 * Vietnamese. {@code {{name}}} always inserts escaped text; {@code {{{name}}}} is allowed only for the
 * {@link #MARKUP_KEYS}, blocks this class builds from escaped parts. A template that mixes them up fails.
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

    /** {{{name}}} inserts markup built here; {{name}} inserts escaped text. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\{([A-Za-z][A-Za-z0-9]*)\\}\\}\\}|\\{\\{([A-Za-z][A-Za-z0-9]*)\\}\\}");

    /** The only placeholders that insert markup. Each is built by this class from escaped parts. */
    static final Set<String> MARKUP_KEYS = Set.of("environmentTag", "languageLinks", "alert", "usernameInvalid", "passwordInvalid", "demo");
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
        // Text values, raw: {{name}} escapes them on the way out, whatever they contain.
        Map<String, String> text = new HashMap<>();
        for (String key : messages.keySet()) {
            if (!key.contains(".")) {
                text.put(key, messages.getString(key));
            }
        }
        String service = serviceLabel == null || serviceLabel.isBlank() ? messages.getString("poweredBy") : serviceLabel;
        text.put("lang", view.language());
        text.put("assets", "/sign-in");
        text.put("action", "/login");
        text.put("csrfParameter", view.csrfParameter());
        text.put("csrfToken", view.csrfToken());
        text.put("service", service);
        text.put("subtitle", messages.getString("subtitle").replace("{service}", service));
        // Markup this class builds from escaped parts; only these keys may appear as {{{name}}}.
        Map<String, String> markup = new HashMap<>();
        // The environment tag shows once: in the demo list when there is one, otherwise beside the service name.
        boolean tagged = environment != null && !environment.isBlank() && demoUsers.isEmpty();
        markup.put("environmentTag", tagged ? "<span class=\"tag tag--env\">" + escape(environment) + "</span>" : "");
        markup.put("languageLinks", languageLinks(view));
        markup.put("alert", alert(view, messages));
        markup.put("usernameInvalid", view.error() ? " aria-invalid=\"true\" aria-describedby=\"sign-in-alert\"" : "");
        markup.put("passwordInvalid", view.error() ? " aria-invalid=\"true\" aria-describedby=\"sign-in-alert\"" : "");
        markup.put("demo", demo(messages));
        if (!markup.keySet().equals(MARKUP_KEYS)) {
            throw new IllegalStateException("Markup keys out of step with MARKUP_KEYS: " + markup.keySet());
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 1024);
        while (matcher.find()) {
            String rawName = matcher.group(1);
            String textName = matcher.group(2);
            String value;
            if (rawName != null) {
                if (!MARKUP_KEYS.contains(rawName)) {
                    throw new IllegalStateException("The sign-in template inserts " + matcher.group() + " as markup; only "
                            + MARKUP_KEYS + " may use triple braces");
                }
                value = markup.get(rawName);
            } else {
                if (MARKUP_KEYS.contains(textName)) {
                    throw new IllegalStateException("The sign-in template uses " + matcher.group() + " as text; it is markup, use {{{"
                            + textName + "}}}");
                }
                String raw = text.get(textName);
                if (raw == null) {
                    throw new IllegalStateException("The sign-in template uses an unknown placeholder " + matcher.group());
                }
                value = escape(raw);
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
            return "<p class=\"alert alert--error\" role=\"alert\" id=\"sign-in-alert\"><span class=\"alert__icon\" aria-hidden=\"true\">✕</span>"
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
                    + escape(pick) + "\"><span class=\"demo__name\">" + escape(user.username()) + "</span> <span class=\"demo__role\">"
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
