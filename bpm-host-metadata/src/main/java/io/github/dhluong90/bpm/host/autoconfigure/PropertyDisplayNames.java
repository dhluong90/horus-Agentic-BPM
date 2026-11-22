package io.github.dhluong90.bpm.host.autoconfigure;

import io.github.dhluong90.bpm.host.spi.DisplayNames;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The default {@link DisplayNames}: {@code horus.bpm.display-names.roles|groups|users.<id>.<language>}. The caller's
 * language is tried first, then {@code horus.bpm.display-names.default-language} ({@code en} by default); a name in
 * neither is unknown, and the caller shows the identifier.
 */
final class PropertyDisplayNames implements DisplayNames {

    private final HostMetadataProperties.DisplayNamesSettings settings;

    PropertyDisplayNames(HostMetadataProperties.DisplayNamesSettings settings) {
        this.settings = settings;
    }

    @Override
    public Optional<String> role(String tenantId, String role, Locale locale) {
        return name(settings.roles(), role, locale);
    }

    @Override
    public Optional<String> group(String tenantId, String group, Locale locale) {
        return name(settings.groups(), group, locale);
    }

    @Override
    public Optional<String> user(String tenantId, String userId, Locale locale) {
        return name(settings.users(), userId, locale);
    }

    private Optional<String> name(Map<String, Map<String, String>> names, String id, Locale locale) {
        Map<String, String> byLanguage = names.get(id);
        if (byLanguage == null) {
            return Optional.empty();
        }
        String language = locale == null ? "" : locale.getLanguage();
        String name = byLanguage.get(language);
        if (name == null || name.isBlank()) {
            name = byLanguage.get(settings.defaultLanguage());
        }
        return name == null || name.isBlank() ? Optional.empty() : Optional.of(name);
    }

    @Override
    public String toString() {
        return "the engines' default display names (horus.bpm.display-names)";
    }
}
