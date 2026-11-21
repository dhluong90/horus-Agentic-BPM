package io.github.dhluong90.bpm.rules.internal.read;

import io.github.dhluong90.bpm.rules.api.Violation;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Checks the explanation sentences an author writes in a rule set document (RLE-073, PD-123): a sentence per language
 * for each row, and one for a decision in which no row matched. A template is plain text with named placeholders, so
 * it can neither compute nor call anything. Stateless.
 */
public final class ExplanationTemplates {

    /** Longest template, in characters, per language. */
    public static final int MAX_LENGTH = 500;

    /** Most languages of one template. */
    public static final int MAX_LANGUAGES = 10;

    /** A language tag as the engine accepts it: a lower-case primary subtag, such as {@code en} or {@code vi}. */
    private static final Pattern TAG = Pattern.compile("^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$");

    /** A placeholder token: everything between braces. */
    public static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]*)}");

    private static final Pattern NAME = Pattern.compile("^(input|output)\\.([A-Za-z_][A-Za-z0-9_]*)$");

    /** The placeholders that need no input or output: the row, the rule set and its version. */
    private static final Set<String> ROW_FIELDS = Set.of("ruleId", "ruleSet", "version");

    private ExplanationTemplates() {
    }

    /**
     * Checks the templates of one row.
     *
     * @param ruleId the row, or null for the no-match sentences
     * @param texts the {@code explain} value of the row, or {@code explanations.noMatch}
     * @param inputs declared inputs: name to {@code mask} as declared (null when undeclared)
     * @param outputs declared output names
     * @return the first problem, placed on the row and the column {@code explain}, or null when the texts are valid
     */
    static Violation check(String ruleId, JsonNode texts, Map<String, Boolean> inputs, Set<String> outputs) {
        if (!texts.isObject() || texts.isEmpty()) {
            return new Violation(ruleId, "explain", "must be a mapping from language to sentence", "INPUT_INVALID");
        }
        if (texts.size() > MAX_LANGUAGES) {
            return new Violation(ruleId, "explain", "has " + texts.size() + " languages; at most " + MAX_LANGUAGES
                    + " are allowed", "INPUT_INVALID");
        }
        for (Map.Entry<String, JsonNode> entry : texts.properties()) {
            String tag = entry.getKey();
            if (!TAG.matcher(tag).matches()) {
                return new Violation(ruleId, "explain", "'" + tag + "' is not a BCP 47 language tag such as en or vi",
                        "INPUT_INVALID");
            }
            if (!entry.getValue().isString() || entry.getValue().stringValue().isBlank()) {
                return new Violation(ruleId, "explain", "the sentence for " + tag + " must be non-empty text",
                        "INPUT_INVALID");
            }
            String text = entry.getValue().stringValue();
            if (text.length() > MAX_LENGTH) {
                return new Violation(ruleId, "explain", "the sentence for " + tag + " has " + text.length()
                        + " characters; at most " + MAX_LENGTH + " are allowed", "TEMPLATE_TOO_LONG");
            }
            Violation problem = placeholders(ruleId, tag, text, inputs, outputs);
            if (problem != null) {
                return problem;
            }
        }
        return null;
    }

    private static Violation placeholders(String ruleId, String tag, String text, Map<String, Boolean> inputs,
            Set<String> outputs) {
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            String token = m.group(1);
            Matcher name = NAME.matcher(token);
            boolean row = ruleId != null;
            if (name.matches() && name.group(1).equals("input") && inputs.containsKey(name.group(2))) {
                if (Boolean.TRUE.equals(inputs.get(name.group(2)))) {
                    return new Violation(ruleId, "explain", "{" + token + "} in " + tag + " names an input declared"
                            + " mask: true; a sentence never prints a masked value", "MASKED_INPUT_IN_TEMPLATE");
                }
            } else if (row && name.matches() && name.group(1).equals("output") && outputs.contains(name.group(2))) {
                continue;
            } else if ((row ? ROW_FIELDS : Set.of("ruleSet", "version")).contains(token)) {
                continue;
            } else {
                return new Violation(ruleId, "explain", "{" + token + "} in " + tag + " is not a placeholder; use "
                        + allowed(row), "UNKNOWN_PLACEHOLDER");
            }
        }
        return null;
    }

    private static String allowed(boolean row) {
        return row ? "{input.<name>} or {output.<name>} of a declared input or output, {ruleId}, {ruleSet} or {version}"
                : "{input.<name>} of a declared input, {ruleSet} or {version}";
    }

    /**
     * Checks every template of a document.
     *
     * @param root the document
     * @param inputs declared inputs: name to declared {@code mask} (null when undeclared)
     * @param outputs declared output names
     * @return the first problem, or empty
     */
    static List<Violation> checkAll(JsonNode root, Map<String, Boolean> inputs, Set<String> outputs) {
        JsonNode noMatch = root.path("explanations").path("noMatch");
        if (!noMatch.isMissingNode()) {
            Violation v = check(null, noMatch, inputs, outputs);
            if (v != null) {
                return List.of(v);
            }
        }
        for (JsonNode rule : root.path("rules")) {
            if (rule.has("explain")) {
                Violation v = check(rule.path("id").asString(""), rule.get("explain"), inputs, outputs);
                if (v != null) {
                    return List.of(v);
                }
            }
        }
        return List.of();
    }
}
