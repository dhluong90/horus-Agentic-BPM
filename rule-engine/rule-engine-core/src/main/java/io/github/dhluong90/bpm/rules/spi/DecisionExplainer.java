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

    /**
     * Returns the languages this renderer can write for one explanation: {@link #languages()} and, for a renderer
     * that uses the author's sentences, the languages those are written in.
     *
     * @param explanation the explanation, without text
     * @return language codes, lower case
     */
    default Set<String> languagesFor(DecisionExplanation explanation) {
        return languages();
    }

    /**
     * Writes the explanation in one language and says where the sentence came from. The default writes
     * {@link #text} and reports it as generated.
     *
     * @param explanation the explanation, without text
     * @param language a member of {@link #languagesFor(DecisionExplanation)}
     * @return the text and its source
     */
    default Rendered render(DecisionExplanation explanation, String language) {
        return new Rendered(text(explanation, language), DecisionExplanation.GENERATED);
    }

    /**
     * A sentence and its source.
     *
     * @param text the sentence
     * @param source {@link DecisionExplanation#TEMPLATE} or {@link DecisionExplanation#GENERATED}
     */
    record Rendered(String text, String source) {
    }
}
