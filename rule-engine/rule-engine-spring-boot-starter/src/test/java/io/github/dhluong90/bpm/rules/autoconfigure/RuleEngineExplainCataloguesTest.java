package io.github.dhluong90.bpm.rules.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.DecisionExplanation;
import io.github.dhluong90.bpm.rules.spi.DecisionExplainer;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The explanation catalogues a host puts on its class path (DESIGN section 4.7, PD-027): read at start-up, laid over
 * the engine's English and Vietnamese entries, with the configured default language. This test class path holds
 * {@code horus-bpm/rules/explain_fr.properties} with two French entries.
 */
class RuleEngineExplainCataloguesTest {

    static final DecisionExplanation D7 = new DecisionExplanation("D7", "card-fee", 1, "FIRST", "OK", null,
            Instant.parse("2026-11-20T10:00:00Z"), List.of(new DecisionExplanation.MatchedRow("R2", 2,
                    List.of(new DecisionExplanation.Condition("txCount", "/when/txCount", ">= 3", "5", false, false,
                            true)), "{}")), "{\"fee\":{\"currency\":\"VND\",\"amount\":10000}}", List.of(), null, null);

    @Test
    void RLE_030_a_host_catalogue_on_the_class_path_adds_a_language_that_falls_back_to_english() {
        DecisionExplainer explainer = RuleEngineExplainCatalogues.explainer("en", getClass().getClassLoader());

        assertThat(explainer.languages()).contains("en", "vi", "fr");
        assertThat(explainer.defaultLanguage()).isEqualTo("en");
        assertThat(explainer.text(D7, "fr")).isEqualTo(
                "La règle R2 de card-fee version 1 s'applique car txCount is 5, which is at least 3. Résultat : fee 10000 VND.");
        assertThat(explainer.text(D7, "en")).isEqualTo(
                "Rule R2 of card-fee version 1 matched because txCount is 5, which is at least 3. Result: fee 10000 VND.");
    }

    @Test
    void RLE_030_a_misnamed_catalogue_or_a_default_language_without_one_stops_start_up(@TempDir Path dir)
            throws IOException {
        assertThatThrownBy(() -> RuleEngineExplainCatalogues.explainer("de", getClass().getClassLoader()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("horus.bpm.rules.explain.default-language");

        Path catalogues = Files.createDirectories(dir.resolve("horus-bpm/rules"));
        Files.writeString(catalogues.resolve("explain_EN-us.properties"), "result=x\n", StandardCharsets.UTF_8);
        try (URLClassLoader loader = new URLClassLoader(new URL[] {dir.toUri().toURL()}, null)) {
            assertThatThrownBy(() -> RuleEngineExplainCatalogues.explainer("en", loader))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("explain_<language>.properties");
        }
    }

    @Test
    void the_starter_registers_a_catalogue_explainer_with_the_configured_default_language() {
        RuleEngineProperties.Explain configured = new RuleEngineProperties.Explain(" vi ");
        RuleEngineProperties.Explain unset = new RuleEngineProperties.Explain(null);

        assertThat(configured.defaultLanguageOrDefault()).isEqualTo("vi");
        assertThat(unset.defaultLanguageOrDefault()).isEqualTo("en");
        assertThat(RuleEngineExplainCatalogues.explainer(configured.defaultLanguageOrDefault(),
                getClass().getClassLoader()).defaultLanguage()).isEqualTo("vi");
    }
}
