package io.github.dhluong90.bpm.rules.autoconfigure;

import io.github.dhluong90.bpm.rules.internal.service.CatalogueExplainer;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Reads a host's explanation catalogues from its class path: every {@code horus-bpm/rules/explain_<language>.properties}
 * (UTF-8), laid over the engine's English and Vietnamese entries (DESIGN section 4.7, PD-027). Files of one language
 * are applied in the order of their URLs, so a later file's entry wins; a host keeps one file per language.
 */
final class RuleEngineExplainCatalogues {

    /** Where a host puts its catalogues. */
    static final String LOCATION = "classpath*:horus-bpm/rules/explain_*.properties";

    private static final Pattern FILE = Pattern.compile("^explain_([a-z]{2,3})\\.properties$");

    private RuleEngineExplainCatalogues() {
    }

    /**
     * Builds the explainer.
     *
     * @param defaultLanguage {@code horus.bpm.rules.explain.default-language}
     * @param classLoader the host's class loader
     * @return the explainer
     * @throws IllegalStateException when a file name is not {@code explain_<two or three lower-case letters>}, or the
     *     default language has no catalogue
     */
    static CatalogueExplainer explainer(String defaultLanguage, ClassLoader classLoader) {
        Map<String, Map<String, String>> overrides = new LinkedHashMap<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver(classLoader).getResources(LOCATION);
            Arrays.sort(resources, Comparator.comparing(RuleEngineExplainCatalogues::url));
            for (Resource resource : resources) {
                String name = resource.getFilename() == null ? "" : resource.getFilename();
                Matcher m = FILE.matcher(name);
                if (!m.matches()) {
                    throw new IllegalStateException("Explanation catalogue " + url(resource) + " must be named"
                            + " explain_<language>.properties with a two- or three-letter lower-case language");
                }
                Map<String, String> entries = overrides.computeIfAbsent(m.group(1), ignored -> new LinkedHashMap<>());
                try (InputStream in = resource.getInputStream()) {
                    Properties properties = new Properties();
                    properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                    properties.stringPropertyNames().forEach(key -> entries.put(key, properties.getProperty(key)));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the explanation catalogues at " + LOCATION, e);
        }
        try {
            return CatalogueExplainer.of(defaultLanguage, overrides);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private static String url(Resource resource) {
        try {
            return resource.getURL().toString();
        } catch (IOException e) {
            return resource.getDescription();
        }
    }
}
