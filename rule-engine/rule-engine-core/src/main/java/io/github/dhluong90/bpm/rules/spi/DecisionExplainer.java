package io.github.dhluong90.bpm.rules.spi;

import io.github.dhluong90.bpm.rules.api.DecisionExplanation;
import java.util.Set;

/**
 * Renders the {@code text} of a decision explanation from its reason codes and matched rows (DESIGN section 4.7,
 * PD-027). The engine builds the explanation itself, from the decision row and the stored version; this seam only
 * words it, so the reason codes a client branches on never change with the wording. The Spring Boot starter provides a
 * default backed by the engine's English and Vietnamese message catalogues, which a host extends or overrides with
 * catalogue files on its class path; a host bean of this type replaces the renderer entirely
 * ({@code @ConditionalOnMissingBean}). Implementations must be thread-safe, deterministic (the same explanation and
 * language give the same text) and must never try to recover a masked value.
 */
public interface DecisionExplainer {

    /**
     * Returns the languages this renderer writes, for example {@code en} and {@code vi}.
     *
     * @return the language codes, lower case
     */
    Set<String> languages();

    /**
     * Returns the language used when the caller asks for none of {@link #languages()}.
     *
     * @return a member of {@link #languages()}
     */
    String defaultLanguage();

    /**
     * Writes the explanation in one language.
     *
     * @param explanation the explanation, without text
     * @param language a member of {@link #languages()}
     * @return the text, one or more sentences
     */
    String text(DecisionExplanation explanation, String language);
}
