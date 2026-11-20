package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.rules.api.DecisionExplanation;
import io.github.dhluong90.bpm.rules.api.DecisionExplanation.Condition;
import io.github.dhluong90.bpm.rules.api.DecisionExplanation.MatchedRow;
import io.github.dhluong90.bpm.rules.internal.json.EngineValues;
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
 * entry missing in a language falls back to English. Values are written locale-neutral, as the expression language
 * writes them: digits without grouping, {@code .} as the decimal mark, money in major units with its currency code,
 * dates and instants in ISO 8601. Placeholders are plain {@code {name}} tokens, never expressions. Immutable and
 * thread-safe.
 */
public final class CatalogueExplainer implements DecisionExplainer {

    /** The language every other one falls back to. */
    public static final String ENGLISH = "en";

    /** The languages whose catalogues ship with the engine. */
    public static final List<String> BUNDLED = List.of(ENGLISH, "vi");

    private static final Pattern LANGUAGE = Pattern.compile("^[a-z]{2,3}$");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z]+)}");

    /** A literal as the normal form prints it: a quoted string, a number or money, a boolean, a date or datetime. */
    private static final String LITERAL = "'(?:[^'\\\\]|\\\\.)*'|-?[0-9]+(?:\\.[0-9]+)?(?: [A-Z]{3})?|true|false"
            + "|date\\('[^']*'\\)|datetime\\('[^']*'\\)";
    private static final Pattern COMPARISON = Pattern.compile("^(>=|<=|>|<|==|!=) (" + LITERAL + ")$");
    private static final Pattern MEMBERSHIP = Pattern.compile("^(not in|in) \\[((?:" + LITERAL + ")(?:, (?:" + LITERAL
            + "))*)?]$");
    private static final Pattern BETWEEN = Pattern.compile("^between (" + LITERAL + ") and (" + LITERAL + ")$");
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

    @Override
    public String text(DecisionExplanation e, String language) {
        Words words = new Words(catalogues.getOrDefault(language, catalogues.get(defaultLanguage)),
                catalogues.get(ENGLISH));
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
            return words.format("value.missing", "input", c.input());
        }
        String value = c.masked() ? words.format("value.masked", "input", c.input())
                : words.format("value", "input", c.input(), "value", value(EngineValues.read(c.valueJson()), words));
        String test = c.result() == null ? words.format("test.unknown", "test", c.test()) : test(c.test(), words);
        return words.format("condition", "value", value, "test", test);
    }

    /** Words a test the normal form prints in one of the simple shapes; any other test is quoted as written. */
    private static String test(String test, Words words) {
        if ("is null".equals(test)) {
            return words.get("test.is-null");
        }
        if ("is not null".equals(test)) {
            return words.get("test.is-not-null");
        }
        Matcher comparison = COMPARISON.matcher(test);
        if (comparison.matches()) {
            return words.format(COMPARISON_KEYS.get(comparison.group(1)), "operand", literal(comparison.group(2)));
        }
        Matcher membership = MEMBERSHIP.matcher(test);
        if (membership.matches()) {
            List<String> members = new ArrayList<>();
            if (membership.group(2) != null) {
                Matcher one = ONE_LITERAL.matcher(membership.group(2));
                while (one.find()) {
                    members.add(literal(one.group()));
                }
            }
            return words.format("in".equals(membership.group(1)) ? "test.in" : "test.not-in", "operand",
                    String.join(words.get("join.list"), members));
        }
        Matcher between = BETWEEN.matcher(test);
        if (between.matches()) {
            return words.format("test.between", "low", literal(between.group(1)), "high", literal(between.group(2)));
        }
        return words.format("test.other", "test", test);
    }

    /** A literal of a test as a reader would write it: a string without its quotes, a date without its function. */
    private static String literal(String literal) {
        if (literal.startsWith("'")) {
            return unquote(literal);
        }
        if (literal.startsWith("date('") || literal.startsWith("datetime('")) {
            return literal.substring(literal.indexOf('\'') + 1, literal.length() - 2);
        }
        return literal;
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
                entry.getKey(), "value", value(entry.getValue(), words))));
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
            return node.decimalValue().toPlainString();
        }
        if (node.isBoolean()) {
            return Boolean.toString(node.booleanValue());
        }
        if (node.isArray()) {
            List<String> items = new ArrayList<>();
            node.forEach(item -> items.add(value(item, words)));
            return "[" + String.join(words.get("join.list"), items) + "]";
        }
        if (node.isObject() && node.size() == 2 && node.path("currency").isString()
                && node.path("amount").isIntegralNumber()) {
            try {
                return Money.ofMinor(node.get("currency").stringValue(), node.get("amount").longValue()).toString();
            } catch (IllegalArgumentException e) {
                return node.get("amount").asString() + " " + node.get("currency").stringValue();
            }
        }
        return EngineValues.write(node);
    }

    /** One language's entries, falling back to English entry by entry. */
    record Words(Map<String, String> entries, Map<String, String> english) {

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
