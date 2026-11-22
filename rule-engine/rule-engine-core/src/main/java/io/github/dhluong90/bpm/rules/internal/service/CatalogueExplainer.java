package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.rules.api.DecisionExplanation;
import io.github.dhluong90.bpm.rules.api.DecisionExplanation.Condition;
import io.github.dhluong90.bpm.rules.api.DecisionExplanation.MatchedRow;
import io.github.dhluong90.bpm.rules.api.DraftValidation;
import io.github.dhluong90.bpm.rules.api.Violation;
import io.github.dhluong90.bpm.rules.internal.json.EngineValues;
import io.github.dhluong90.bpm.rules.internal.read.ExplanationTemplates;
import io.github.dhluong90.bpm.rules.spi.DecisionExplainer;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * The default {@link DecisionExplainer}: sentences from a message catalogue per language (DESIGN section 4.7, PD-027,
 * PD-077). English and Vietnamese ship with the engine; a host adds entries or whole languages without code, and an
 * entry missing in a language falls back to English. Names are the labels the rule set declares, else the name in
 * words; numbers and money are grouped for the language by the catalogue's {@code format.group} and
 * {@code format.decimal} (money keeps its currency code), booleans read Yes or No, dates and instants stay ISO 8601
 * (#392). The author's sentence of a row is used for the requested language when there is one (PD-123). Placeholders
 * are plain {@code {name}} tokens, never expressions. Immutable and thread-safe.
 */
public final class CatalogueExplainer implements DecisionExplainer {

    /** The language every other one falls back to. */
    public static final String ENGLISH = "en";

    /** The languages whose catalogues ship with the engine. */
    public static final List<String> BUNDLED = List.of(ENGLISH, "vi");

    private static final Pattern LANGUAGE = Pattern.compile("^[a-z]{2,3}$");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z]+)}");
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]*$");
    private static final String SHORT = "short.";

    /** A literal as the normal form prints it: a quoted string, a number or money, a boolean, a date or datetime. */
    private static final String LITERAL = "'(?:[^'\\\\]|\\\\.)*'|-?[0-9]+(?:\\.[0-9]+)?(?: [A-Z]{3})?|true|false"
            + "|date\\('[^']*'\\)|datetime\\('[^']*'\\)";
    private static final Pattern COMPARISON = Pattern.compile("^(>=|<=|>|<|==|!=) (" + LITERAL + ")$");
    private static final Pattern MEMBERSHIP = Pattern.compile("^(not in|in) \\[((?:" + LITERAL + ")(?:, (?:" + LITERAL
            + "))*)?]$");
    private static final Pattern BETWEEN = Pattern.compile("^between (" + LITERAL + ") and (" + LITERAL + ")$");
    /** Exactly one literal, such as {@code 50000000 VND} or {@code 'GOLD'}. */
    static final Pattern WHOLE_LITERAL = Pattern.compile(LITERAL);
    private static final Pattern ONE_LITERAL = Pattern.compile(LITERAL);
    private static final Pattern GROUP = Pattern.compile("^(.*?/any)/([0-9]+)/");

    private static final Map<String, String> COMPARISON_KEYS = Map.of(">=", "test.ge", ">", "test.gt",
            "<=", "test.le", "<", "test.lt", "==", "test.eq", "!=", "test.ne");

    private final Map<String, Map<String, String>> catalogues;
    private final String defaultLanguage;

    private CatalogueExplainer(Map<String, Map<String, String>> catalogues, String defaultLanguage) {
        this.catalogues = catalogues;
        this.defaultLanguage = defaultLanguage;
    }

    /**
     * The bundled catalogues only, with English as the default.
     *
     * @return the explainer
     */
    public static CatalogueExplainer bundled() {
        return of(ENGLISH, Map.of());
    }

    /**
     * The bundled catalogues with a host's entries laid over them.
     *
     * @param defaultLanguage the language used when the caller asks for none available
     *     ({@code explain.default-language})
     * @param overrides entries by language code; a language not bundled is added, and its missing entries fall back
     *     to English
     * @return the explainer
     * @throws IllegalArgumentException when a language code is not two or three lower-case letters, or the default
     *     language has no catalogue
     */
    public static CatalogueExplainer of(String defaultLanguage, Map<String, Map<String, String>> overrides) {
        Objects.requireNonNull(defaultLanguage, "defaultLanguage must not be null");
        Map<String, Map<String, String>> merged = new LinkedHashMap<>();
        for (String language : BUNDLED) {
            merged.put(language, new LinkedHashMap<>(load(language)));
        }
        overrides.forEach((language, entries) -> {
            if (language == null || !LANGUAGE.matcher(language).matches()) {
                throw new IllegalArgumentException("An explanation catalogue language must be two or three lower-case"
                        + " letters, for example vi; was " + language);
            }
            merged.computeIfAbsent(language, ignored -> new LinkedHashMap<>()).putAll(entries);
        });
        if (!merged.containsKey(defaultLanguage)) {
            throw new IllegalArgumentException("horus.bpm.rules.explain.default-language is " + defaultLanguage
                    + ", which has no explanation catalogue; available: " + new TreeSet<>(merged.keySet()));
        }
        Map<String, Map<String, String>> frozen = new LinkedHashMap<>();
        merged.forEach((language, entries) -> frozen.put(language, Map.copyOf(entries)));
        return new CatalogueExplainer(Collections.unmodifiableMap(frozen), defaultLanguage);
    }

    private static Map<String, String> load(String language) {
        String resource = "explain_" + language + ".properties";
        try (InputStream in = CatalogueExplainer.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("rule-engine-core is missing " + resource + "; rebuild it with Maven.");
            }
            Properties properties = new Properties();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            Map<String, String> entries = new LinkedHashMap<>();
            properties.stringPropertyNames().forEach(key -> entries.put(key, properties.getProperty(key)));
            return entries;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Set<String> languages() {
        return Collections.unmodifiableSet(new TreeSet<>(catalogues.keySet()));
    }

    @Override
    public String defaultLanguage() {
        return defaultLanguage;
    }

    /**
     * Words one problem of a refused draft in every language of the catalogue (RLE-090). The sentence is chosen by
     * the code alone, so it never repeats text of the cell: a value of a masked input cannot reach it. A problem with
     * no code gets {@code RULE_INVALID}, {@code COLUMN_INVALID} or {@code DOCUMENT_INVALID} by where it is.
     *
     * @param violation the problem the compiler found
     * @return the problem with its code and its message by language
     */
    public DraftValidation.Problem problem(Violation violation) {
        String code = violation.code() != null ? violation.code()
                : violation.ruleId() != null ? "RULE_INVALID" : violation.column() != null ? "COLUMN_INVALID"
                : "DOCUMENT_INVALID";
        Map<String, String> message = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        String example = null;
        String syntax = "SYNTAX_ERROR".equals(code) ? violation.syntax() : null;
        for (String language : new TreeSet<>(catalogues.keySet())) {
            Words words = words(language, DecisionExplanation.Wording.NONE);
            if (syntax != null && words.has("syntax." + syntax + ".expected")) {
                expected.put(language, words.get("syntax." + syntax + ".expected"));
            }
            if (syntax != null && example == null && words.has("syntax." + syntax + ".example")) {
                example = words.get("syntax." + syntax + ".example");
            }
            String key = "problem." + code;
            message.put(language, words.has(key) ? words.get(key)
                    : words.get("problem." + (violation.ruleId() != null ? "RULE_INVALID"
                            : violation.column() != null ? "COLUMN_INVALID" : "DOCUMENT_INVALID")));
        }
        return new DraftValidation.Problem(violation.ruleId(), violation.column(), code, violation.position(), message,
                expected, example);
    }

    /** The catalogue entries of one language, with the wording of the rule set being read. */
    Words words(String language, DecisionExplanation.Wording wording) {
        return new Words(catalogues.getOrDefault(language, catalogues.get(defaultLanguage)), catalogues.get(ENGLISH),
                wording);
    }

    @Override
    public Set<String> languagesFor(DecisionExplanation e) {
        Set<String> languages = new TreeSet<>(catalogues.keySet());
        e.wording().rowTemplates().values().forEach(texts -> languages.addAll(texts.keySet()));
        languages.addAll(e.wording().noMatchTemplates().keySet());
        return Collections.unmodifiableSet(languages);
    }

    @Override
    public Rendered render(DecisionExplanation e, String language) {
        String templated = DecisionExplanation.OK_OUTCOME.equals(e.outcome()) ? template(e, language) : null;
        return templated != null ? new Rendered(templated, DecisionExplanation.TEMPLATE)
                : new Rendered(text(e, language), DecisionExplanation.GENERATED);
    }

    private Words words(DecisionExplanation e, String language) {
        return new Words(catalogues.getOrDefault(language, catalogues.get(defaultLanguage)), catalogues.get(ENGLISH),
                e.wording());
    }

    /**
     * The author's sentence for the decision in one language, or null: the sentence of no match, or the sentences of
     * the matched rows in table order joined by one space when every matched row has one in that language.
     */
    private String template(DecisionExplanation e, String language) {
        Words words = words(e, language);
        if (e.matched().isEmpty()) {
            String text = e.wording().noMatchTemplates().get(language);
            return text == null ? null : fill(text, e, null, words);
        }
        List<String> sentences = new ArrayList<>();
        for (MatchedRow row : e.matched()) {
            String text = e.wording().rowTemplates().getOrDefault(row.ruleId(), Map.of()).get(language);
            if (text == null) {
                return null; // a row without a sentence: the catalogue sentence explains the whole decision
            }
            sentences.add(fill(text, e, row, words));
        }
        return String.join(" ", sentences);
    }

    /** Replaces each placeholder once; a replacement is never scanned again, so a value cannot add a placeholder. */
    private static String fill(String template, DecisionExplanation e, MatchedRow row, Words words) {
        Matcher m = ExplanationTemplates.PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String token = m.group(1);
            String value = switch (token) {
                case "ruleId" -> row == null ? null : row.ruleId();
                case "ruleSet" -> e.ruleSet();
                case "version" -> Integer.toString(e.version());
                default -> placeholder(token, e, row, words);
            };
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? m.group() : value));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String placeholder(String token, DecisionExplanation e, MatchedRow row, Words words) {
        if (token.startsWith("input.")) {
            String name = token.substring("input.".length());
            String json = e.wording().inputsJson().get(name);
            if (json == null) {
                return null;
            }
            return e.wording().maskedInputs().contains(name) ? words.get("template.masked")
                    : value(EngineValues.read(json), words);
        }
        if (token.startsWith("output.") && row != null) {
            String name = token.substring("output.".length());
            JsonNode own = EngineValues.read(row.outputsJson()).get(name);
            JsonNode decision = e.outputsJson() == null ? null : EngineValues.read(e.outputsJson()).get(name);
            return value(own != null ? own : decision, words);
        }
        return null;
    }

    @Override
    public String text(DecisionExplanation e, String language) {
        Words words = words(e, language);
        String version = Integer.toString(e.version());
        List<String> ruleIds = e.matched().stream().map(MatchedRow::ruleId).toList();
        return switch (e.outcome()) {
            case DecisionExplanation.UNIQUE_HIT_VIOLATION -> words.format(ruleIds.size() == 2 ? "unique.two"
                    : "unique.many", "ruleIds", words.list(ruleIds), "ruleSet", e.ruleSet(), "version", version);
            case "OK" -> {
                if (e.matched().isEmpty()) {
                    yield words.format("no-match", "ruleSet", e.ruleSet(), "version", version);
                }
                List<String> sentences = new ArrayList<>();
                for (MatchedRow row : e.matched()) {
                    sentences.add(row.conditions().isEmpty()
                            ? words.format("row.always", "ruleId", row.ruleId(), "ruleSet", e.ruleSet(),
                                    "version", version)
                            : words.format("row.because", "ruleId", row.ruleId(), "ruleSet", e.ruleSet(),
                                    "version", version, "conditions", conditions(row.conditions(), words)));
                }
                String outputs = outputs(e.outputsJson(), words);
                if (!outputs.isEmpty()) {
                    sentences.add(words.format("result", "outputs", outputs));
                }
                yield String.join(" ", sentences);
            }
            default -> {
                String code = e.errorCode() == null ? e.outcome() : e.errorCode();
                yield ruleIds.isEmpty()
                        ? words.format("error", "ruleSet", e.ruleSet(), "version", version, "errorCode", code)
                        : words.format("error.rows", "ruleSet", e.ruleSet(), "version", version, "errorCode", code,
                                "ruleIds", words.list(ruleIds));
            }
        };
    }

    /** The plain conditions in order, then each {@code any} group as its alternatives. */
    private static String conditions(List<Condition> conditions, Words words) {
        List<String> parts = new ArrayList<>();
        Map<String, Map<String, List<String>>> groups = new LinkedHashMap<>();
        for (Condition c : conditions) {
            Matcher group = GROUP.matcher(c.path());
            if (c.result() == null && group.find()) {
                groups.computeIfAbsent(group.group(1), ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(group.group(2), ignored -> new ArrayList<>()).add(condition(c, words));
            } else {
                parts.add(condition(c, words));
            }
        }
        groups.values().forEach(alternatives -> parts.add(words.format("any", "alternatives",
                String.join(words.get("join.alternatives"), alternatives.values().stream()
                        .map(alternative -> String.join(words.get("join.and"), alternative)).toList()))));
        return String.join(words.get("join.conditions"), parts);
    }

    private static String condition(Condition c, Words words) {
        if (c.missing()) {
            return words.format("value.missing", "input", words.inputName(c.input()));
        }
        String value = c.masked() ? words.format("value.masked", "input", words.inputName(c.input()))
                : words.format("value", "input", words.inputName(c.input()), "value", value(EngineValues.read(c.valueJson()), words));
        String test = c.result() == null ? words.format("test.unknown", "test", c.test()) : test(c.test(), words, "", "");
        return words.format("condition", "value", value, "test", test);
    }

    /**
     * Words a test the normal form prints in one of the simple shapes; any other test is quoted as written. The
     * sentence keys are {@code prefix + "test.ge"} and so on, so the decision explanation ({@code ""}), the full
     * reading of a row and its short form share one parser; {@code {input}} is filled when the entry has it.
     */
    static String test(String test, Words words, String prefix, String input) {
        if ("is null".equals(test)) {
            return words.format(prefix + "test.is-null", "input", input);
        }
        if ("is not null".equals(test)) {
            return words.format(prefix + "test.is-not-null", "input", input);
        }
        boolean readable = !prefix.isEmpty(); // a reading words a code as the table does; an explanation as logged
        Matcher comparison = COMPARISON.matcher(test);
        if (comparison.matches()) {
            String key = COMPARISON_KEYS.get(comparison.group(1));
            if (SHORT.equals(prefix) && "test.eq".equals(key) && comparison.group(2).startsWith("'")) {
                key = "test.eq.text"; // "Gold", not "tier = Gold": the value alone names the case
            }
            return words.format(prefix + key, "input", input, "operand",
                    literal(comparison.group(2), words, readable));
        }
        Matcher membership = MEMBERSHIP.matcher(test);
        if (membership.matches()) {
            List<String> members = new ArrayList<>();
            if (membership.group(2) != null) {
                Matcher one = ONE_LITERAL.matcher(membership.group(2));
                while (one.find()) {
                    members.add(literal(one.group(), words, readable));
                }
            }
            return words.format(prefix + ("in".equals(membership.group(1)) ? "test.in" : "test.not-in"), "input",
                    input, "operand", String.join(words.get("join.list"), members));
        }
        Matcher between = BETWEEN.matcher(test);
        if (between.matches()) {
            return words.format(prefix + "test.between", "input", input, "low", literal(between.group(1), words, readable),
                    "high", literal(between.group(2), words, readable));
        }
        return words.format(prefix + "test.other", "input", input, "test", test);
    }

    /** A literal of a test as a reader would write it: a string without its quotes, a date without its function. */
    static String literal(String literal, Words words) {
        return literal(literal, words, false);
    }

    /** As {@link #literal(String, Words)}; {@code readable} words a code such as {@code GOLD} as the table does. */
    static String literal(String literal, Words words, boolean readable) {
        if (literal.startsWith("'")) {
            return readable ? codeLabel(unquote(literal)) : unquote(literal);
        }
        if (literal.startsWith("date('") || literal.startsWith("datetime('")) {
            return literal.substring(literal.indexOf('\'') + 1, literal.length() - 2);
        }
        if (literal.equals("true") || literal.equals("false")) {
            return words.get("bool." + literal);
        }
        return words.number(literal); // a number, or money such as 50000000 VND, grouped for the language
    }

    /**
     * A code as the rule table shows it: {@code GOLD} is {@code Gold}, {@code FIRST_TIME} is {@code First time}. A code
     * of one or two characters is an abbreviation, such as a country (VN), and other text stays as written, so a
     * reading and the table beside it always agree.
     */
    static String codeLabel(String value) {
        if (!CODE.matcher(value).matches() || value.length() <= 2) {
            return value;
        }
        String words = value.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return words.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + words.substring(1);
    }

    /** A decided output value worded for a reading: a code as the table shows it, anything else as {@link #value}. */
    static String readableValue(JsonNode node, Words words) {
        return node != null && node.isString() ? codeLabel(node.stringValue()) : value(node, words);
    }

    private static String unquote(String quoted) {
        StringBuilder out = new StringBuilder();
        for (int i = 1; i < quoted.length() - 1; i++) {
            char c = quoted.charAt(i);
            if (c == '\\' && i + 1 < quoted.length() - 1) {
                c = quoted.charAt(++i);
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String outputs(String outputsJson, Words words) {
        if (outputsJson == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        EngineValues.read(outputsJson).properties().forEach(entry -> parts.add(words.format("output", "name",
                words.outputName(entry.getKey()), "value", value(entry.getValue(), words))));
        return String.join(words.get("join.outputs"), parts);
    }

    /** A logged value written locale-neutral: money in major units with its code, a list in brackets. */
    static String value(JsonNode node, Words words) {
        if (node == null || node.isNull()) {
            return words.get("null");
        }
        if (node.isString()) {
            return node.stringValue();
        }
        if (node.isNumber()) {
            return words.number(node.decimalValue().toPlainString());
        }
        if (node.isBoolean()) {
            return words.get("bool." + node.booleanValue());
        }
        if (node.isArray()) {
            List<String> items = new ArrayList<>();
            node.forEach(item -> items.add(value(item, words)));
            return "[" + String.join(words.get("join.list"), items) + "]";
        }
        if (node.isObject() && node.size() == 2 && node.path("currency").isString()
                && node.path("amount").isIntegralNumber()) {
            try {
                return words.number(Money.ofMinor(node.get("currency").stringValue(),
                        node.get("amount").longValue()).toString());
            } catch (IllegalArgumentException e) {
                return words.number(node.get("amount").asString() + " " + node.get("currency").stringValue());
            }
        }
        return EngineValues.write(node);
    }

    /** One language's entries, falling back to English entry by entry. */
    record Words(Map<String, String> entries, Map<String, String> english, DecisionExplanation.Wording wording) {

        private static final Pattern NUMBER = Pattern.compile("^(-?)([0-9]+)(?:\\.([0-9]+))?( [A-Z]{3})?$");
        private static final Pattern WORD_BREAK = Pattern.compile(
                "(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|[_-]+");

        /** An input as a reader knows it: its declared label, else its name in words. */
        String inputName(String name) {
            return wording.inputLabels().getOrDefault(name, humanise(name));
        }

        /** An output as a reader knows it: its declared label, else its name in words. */
        String outputName(String name) {
            return wording.outputLabels().getOrDefault(name, humanise(name));
        }

        /** {@code txCount} as {@code tx count}, {@code customerID} as {@code customer ID}. */
        static String humanise(String name) {
            String[] parts = WORD_BREAK.split(name);
            List<String> words = new ArrayList<>();
            for (String part : parts) {
                if (!part.isEmpty()) {
                    words.add(part.length() > 1 && part.equals(part.toUpperCase(java.util.Locale.ROOT))
                            && !part.chars().allMatch(Character::isDigit) ? part
                            : part.toLowerCase(java.util.Locale.ROOT));
                }
            }
            return words.isEmpty() ? name : String.join(" ", words);
        }

        /**
         * Writes a plain number, or money such as {@code 60000000 VND} or {@code 12.50 USD}, for the language: the
         * integer part grouped with {@code format.group}, the decimal mark {@code format.decimal}, the currency code
         * kept. Anything that is not such a number is returned as it is.
         */
        String number(String plain) {
            Matcher m = NUMBER.matcher(plain);
            if (!m.matches()) {
                return plain;
            }
            String group = get("format.group");
            String digits = m.group(2);
            StringBuilder grouped = new StringBuilder();
            for (int i = 0; i < digits.length(); i++) {
                if (i > 0 && (digits.length() - i) % 3 == 0) {
                    grouped.append(group);
                }
                grouped.append(digits.charAt(i));
            }
            return m.group(1) + grouped + (m.group(3) == null ? "" : get("format.decimal") + m.group(3))
                    + (m.group(4) == null ? "" : m.group(4));
        }

        boolean has(String key) {
            return entries.containsKey(key) || english.containsKey(key);
        }

        String get(String key) {
            String value = entries.get(key);
            if (value == null) {
                value = english.get(key);
            }
            if (value == null) {
                throw new IllegalStateException("The explanation catalogue has no entry " + key);
            }
            return value;
        }

        /** Replaces each {@code {name}} by its value once; a value is never scanned for placeholders again. */
        String format(String key, String... namesAndValues) {
            Map<String, String> values = new LinkedHashMap<>();
            for (int i = 0; i < namesAndValues.length; i += 2) {
                values.put(namesAndValues[i], namesAndValues[i + 1]);
            }
            Matcher m = PLACEHOLDER.matcher(get(key));
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                String value = values.get(m.group(1));
                m.appendReplacement(out, Matcher.quoteReplacement(value == null ? m.group() : value));
            }
            m.appendTail(out);
            return out.toString();
        }

        /** {@code R1}, {@code R1 and R2}, {@code R1, R2 and R3}. */
        String list(List<String> items) {
            if (items.size() <= 1) {
                return String.join("", items);
            }
            return String.join(get("join.list"), items.subList(0, items.size() - 1)) + get("join.and") + items.getLast();
        }
    }
}
