package io.github.dhluong90.bpm.host.autoconfigure;

import java.util.List;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * Where a host names the token audiences it accepts (TR-SEC-12): this starter's
 * {@code horus.bpm.security.audiences}, or Spring Boot's own
 * {@code spring.security.oauth2.resourceserver.jwt.audiences}. Bound, not read as one string, so a YAML list
 * and a comma-separated environment variable both count.
 */
final class AudiencesConfigured {

    static final String STARTER_PROPERTY = "horus.bpm.security.audiences";
    static final String BOOT_PROPERTY = "spring.security.oauth2.resourceserver.jwt.audiences";

    private AudiencesConfigured() {
    }

    /** Whether at least one non-blank audience is configured in either place. */
    static boolean in(Environment environment) {
        Binder binder = Binder.get(environment);
        return hasValue(binder, STARTER_PROPERTY) || hasValue(binder, BOOT_PROPERTY);
    }

    private static boolean hasValue(Binder binder, String property) {
        return binder.bind(property, Bindable.listOf(String.class)).orElse(List.of()).stream()
                .anyMatch(value -> !value.isBlank());
    }
}
