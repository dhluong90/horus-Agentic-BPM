package io.github.dhluong90.bpm.rules.api;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A logged decision explained in plain language (RLE-030, RLE-031, DESIGN section 4.7). Built from the decision row and
 * the stored version it names, and nothing else: no expression is evaluated, so a masked input stays its stored
 * fingerprint and a later version never changes the answer. {@link #matched()}, {@link #outputsJson()} and
 * {@link #reasons()} are the contract; {@link #text()} is rendered from the reasons by a {@code DecisionExplainer}.
 *
 * @param decisionId the decision id
 * @param ruleSet the rule set name
 * @param version the version the decision used
 * @param hitPolicy {@code FIRST}, {@code UNIQUE} or {@code COLLECT}
 * @param outcome {@code OK}, {@code UNIQUE_HIT_VIOLATION} or {@code EVALUATION_ERROR}
 * @param errorCode the reason of a failed decision, or null
 * @param clock the instant the decision reasoned about
 * @param matched the matched rows in table order; empty for no match
 * @param outputsJson the logged outputs as a JSON object, or null when the decision produced none
 * @param reasons the reason codes with their named parameters, in order
 * @param language the language of {@code text}, for example {@code en}; null before rendering
 * @param text the explanation in that language; null before rendering
 * @param textSource {@link #TEMPLATE} when {@code text} is the author's sentence, {@link #GENERATED} when it is the
 *     catalogue sentence; null before rendering
 * @param wording what a renderer needs beyond the contract: the declared labels, the author's templates and the
 *     recorded input values; never part of the response
 */
public record DecisionExplanation(String decisionId, String ruleSet, int version, String hitPolicy, String outcome,
        String errorCode, Instant clock, List<MatchedRow> matched, String outputsJson, List<Reason> reasons,
        String language, String text, String textSource, Wording wording) {

    /** The outcome of a decision that ran to the end, matched or not. */
    public static final String OK_OUTCOME = "OK";
    /** {@code textSource}: the author's sentence for the requested language (RLE-072). */
    public static final String TEMPLATE = "TEMPLATE";
    /** {@code textSource}: the sentence generated from the message catalogue. */
    public static final String GENERATED = "GENERATED";

    /** {@code RULE_MATCHED}: a row matched; params {@code ruleId}, {@code version}. */
    public static final String RULE_MATCHED = "RULE_MATCHED";
    /** {@code NO_RULE_MATCHED}: no row matched; params {@code ruleSet}, {@code version}. */
    public static final String NO_RULE_MATCHED = "NO_RULE_MATCHED";
    /** {@code UNIQUE_HIT_VIOLATION}: several rows matched a {@code unique} table; params {@code ruleIds}, {@code version}. */
    public static final String UNIQUE_HIT_VIOLATION = "UNIQUE_HIT_VIOLATION";
    /** {@code EVALUATION_ERROR}: the decision failed; params {@code errorCode}, {@code ruleIds}, {@code version}. */
    public static final String EVALUATION_ERROR = "EVALUATION_ERROR";
    /** {@code VALUE_MISSING}: a condition's input had no value; params {@code ruleId}, {@code input}. */
    public static final String VALUE_MISSING = "VALUE_MISSING";
    /** {@code VALUE_MASKED}: a condition's input is masked in the log; params {@code ruleId}, {@code input}. */
    public static final String VALUE_MASKED = "VALUE_MASKED";

    public DecisionExplanation {
        Objects.requireNonNull(decisionId, "decisionId must not be null");
        Objects.requireNonNull(ruleSet, "ruleSet must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        matched = List.copyOf(matched);
        reasons = List.copyOf(reasons);
        wording = wording == null ? Wording.NONE : wording;
    }

    /**
     * Creates an explanation with no wording data: names are written in words and no template exists.
     *
     * @param decisionId the decision id
     * @param ruleSet the rule set name
     * @param version the version
     * @param hitPolicy the hit policy
     * @param outcome the outcome
     * @param errorCode the error code, or null
     * @param clock the evaluation clock
     * @param matched the matched rows
     * @param outputsJson the logged outputs, or null
     * @param reasons the reasons
     * @param language the language of the text, or null
     * @param text the text, or null
     */
    public DecisionExplanation(String decisionId, String ruleSet, int version, String hitPolicy, String outcome,
            String errorCode, Instant clock, List<MatchedRow> matched, String outputsJson, List<Reason> reasons,
            String language, String text) {
        this(decisionId, ruleSet, version, hitPolicy, outcome, errorCode, clock, matched, outputsJson, reasons,
                language, text, text == null ? null : GENERATED, Wording.NONE);
    }

    /**
     * Returns this explanation with its text.
     *
     * @param language the language the text is written in
     * @param text the text
     * @return a copy with both set
     */
    public DecisionExplanation withText(String language, String text) {
        return withText(language, text, GENERATED);
    }

    /**
     * Returns this explanation with its text and where the text came from.
     *
     * @param language the language the text is written in
     * @param text the text
     * @param textSource {@link #TEMPLATE} or {@link #GENERATED}
     * @return a copy with all three set
     */
    public DecisionExplanation withText(String language, String text, String textSource) {
        return new DecisionExplanation(decisionId, ruleSet, version, hitPolicy, outcome, errorCode, clock, matched,
                outputsJson, reasons, Objects.requireNonNull(language, "language must not be null"),
                Objects.requireNonNull(text, "text must not be null"),
                Objects.requireNonNull(textSource, "textSource must not be null"), wording);
    }

    /**
     * Returns this explanation with its wording data.
     *
     * @param wording the labels, templates and recorded values
     * @return a copy with the wording set
     */
    public DecisionExplanation withWording(Wording wording) {
        return new DecisionExplanation(decisionId, ruleSet, version, hitPolicy, outcome, errorCode, clock, matched,
                outputsJson, reasons, language, text, textSource, wording);
    }

    /**
     * What a renderer needs besides the contract (DESIGN section 4.7): the labels the rule set declares, the
     * sentences its author wrote (PD-123) and the recorded input values. Read from the stored version and the
     * decision row, so a later version never changes it. Never serialised into the response.
     *
     * @param inputLabels declared {@code label} by input name; an input without one is written in words
     * @param outputLabels declared {@code label} by output name
     * @param rowTemplates the author's sentences by rule id, then by language
     * @param noMatchTemplates the author's sentence for a decision in which no row matched, by language
     * @param inputsJson the recorded input values by name, each as JSON text; a masked input is its fingerprint
     * @param maskedInputs the inputs whose recorded value is a masked fingerprint
     */
    public record Wording(Map<String, String> inputLabels, Map<String, String> outputLabels,
            Map<String, Map<String, String>> rowTemplates, Map<String, String> noMatchTemplates,
            Map<String, String> inputsJson, java.util.Set<String> maskedInputs) {

        /** No labels, no templates and no recorded values. */
        public static final Wording NONE = new Wording(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                java.util.Set.of());

        public Wording {
            inputLabels = Map.copyOf(inputLabels);
            outputLabels = Map.copyOf(outputLabels);
            rowTemplates = Map.copyOf(rowTemplates);
            noMatchTemplates = Map.copyOf(noMatchTemplates);
            inputsJson = Map.copyOf(inputsJson);
            maskedInputs = java.util.Set.copyOf(maskedInputs);
        }
    }

    /**
     * One matched row.
     *
     * @param ruleId the rule id
     * @param position the row's 1-based place in the table
     * @param conditions the row's conditions: the plain cells in column order (the order of the declared inputs),
     *     then those of its {@code all} and {@code any} groups
     * @param outputsJson the outputs this row gave, as a JSON object
     */
    public record MatchedRow(String ruleId, int position, List<Condition> conditions, String outputsJson) {

        public MatchedRow {
            Objects.requireNonNull(ruleId, "ruleId must not be null");
            conditions = List.copyOf(conditions);
            Objects.requireNonNull(outputsJson, "outputsJson must not be null");
        }
    }

    /**
     * One condition of a matched row, with the value the log recorded for its input.
     *
     * @param input the input name
     * @param path the JSON Pointer of the cell in the row, for example {@code /when/txCount}
     * @param test the cell's condition as stored, for example {@code >= 3}
     * @param valueJson the recorded value as JSON: money in minor units, {@code "hmac:<keyId>:<hex>"} when masked,
     *     {@code null} when missing
     * @param masked whether the value is a masked fingerprint
     * @param missing whether the input had no value
     * @param result {@code TRUE} for a condition that had to hold for the row to match; null inside an {@code any}
     *     group, where the log does not say which alternative held
     */
    public record Condition(String input, String path, String test, String valueJson, boolean masked, boolean missing,
            Boolean result) {

        public Condition {
            Objects.requireNonNull(input, "input must not be null");
            Objects.requireNonNull(path, "path must not be null");
            Objects.requireNonNull(test, "test must not be null");
            Objects.requireNonNull(valueJson, "valueJson must not be null");
        }
    }

    /**
     * A reason code with its named parameters. Parameter values are {@code String}, {@code Integer} or a
     * {@code List<String>}.
     *
     * @param code the reason code, one of the constants of {@link DecisionExplanation}
     * @param params the parameters by name, in a fixed order
     */
    public record Reason(String code, Map<String, Object> params) {

        public Reason {
            Objects.requireNonNull(code, "code must not be null");
            params = Collections.unmodifiableMap(new LinkedHashMap<>(params));
        }
    }
}
