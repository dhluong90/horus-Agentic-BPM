package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.api.DecisionExplanation.Wording;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.DecisionExplanation;
import io.github.dhluong90.bpm.rules.api.ReadingsTooLargeException;
import io.github.dhluong90.bpm.rules.api.RuleReadings.OutcomeReading;
import io.github.dhluong90.bpm.rules.api.RuleReadings.Reading;
import io.github.dhluong90.bpm.rules.api.RuleReadings.Row;
import io.github.dhluong90.bpm.rules.internal.json.EngineValues;
import io.github.dhluong90.bpm.rules.internal.model.Cell;
import io.github.dhluong90.bpm.rules.internal.model.Definitions;
import io.github.dhluong90.bpm.rules.internal.model.InputClause;
import io.github.dhluong90.bpm.rules.internal.model.Rule;
import io.github.dhluong90.bpm.rules.internal.model.When;
import io.github.dhluong90.bpm.rules.internal.read.ExplanationTemplates;
import io.github.dhluong90.bpm.rules.internal.read.RuleSetReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import tools.jackson.databind.JsonNode;

/**
 * The one reader of rule rows (PD-027, PD-123, RLE-090 to RLE-093): it words a row from the stored table, the labels of
 * the rule set and the message catalogue that words decision explanations, so a number, a sum of money, an input name
 * and a masked input read the same in every place. A reading is a full sentence and a short form, in every language of
 * the catalogue. The author's sentence of a row (PD-123) is the full sentence when every placeholder in it can be
 * filled from the table alone. A masked input is named and its test is never written. Stateless and deterministic.
 */
final class ReadingBuilder {

    static final String TEMPLATE = DecisionExplanation.TEMPLATE;
    static final String GENERATED = DecisionExplanation.GENERATED;

    private final CatalogueExplainer catalogue;

    ReadingBuilder(CatalogueExplainer catalogue) {
        this.catalogue = catalogue;
    }

    /** The languages every reading is written in, sorted. */
    List<String> languages() {
        return List.copyOf(new TreeSet<>(catalogue.languages()));
    }

    /**
     * Prepares one version's table for reading.
     *
     * @param ruleSet the rule set name
     * @param version the version number
     * @param canonicalJson the stored canonical document
     * @param maskByDefault whether the tenant masks an input that declares no {@code mask}
     * @return the table, ready to read rows from
     */
    Table table(String ruleSet, int version, String canonicalJson, boolean maskByDefault) {
        return table(ruleSet, version, RuleSetReader.readCanonical(canonicalJson),
                EngineValues.read(canonicalJson), maskByDefault);
    }

    /** As {@link #table(String, int, String, boolean)}, for a document that is already read. */
    Table table(String ruleSet, int version, Definitions model, JsonNode document, boolean maskByDefault) {
        List<InputClause> inputs = model.decision().table().inputs();
        Set<String> masked = new TreeSet<>();
        inputs.forEach(input -> {
            if (input.masked(maskByDefault)) {
                masked.add(input.name());
            }
        });
        Map<String, String> inputLabels = new LinkedHashMap<>();
        document.path("inputs").forEach(n -> label(n, inputLabels));
        Map<String, String> outputLabels = new LinkedHashMap<>();
        document.path("outputs").forEach(n -> label(n, outputLabels));
        Map<String, Map<String, String>> templates = new LinkedHashMap<>();
        document.path("rules").forEach(rule -> {
            Map<String, String> texts = new LinkedHashMap<>();
            rule.path("explain").properties().forEach(e -> {
                if (e.getValue().isString()) {
                    texts.put(e.getKey(), e.getValue().stringValue());
                }
            });
            if (!texts.isEmpty()) {
                templates.put(rule.path("id").asString(""), texts);
            }
        });
        Wording wording = new Wording(inputLabels, outputLabels, templates, Map.of(), Map.of(), Set.of());
        List<String> columns = inputs.stream().map(InputClause::name).toList();
        return new Table(ruleSet, version, model.decision().table().rules(), columns, masked, wording);
    }

    private static void label(JsonNode column, Map<String, String> labels) {
        if (column.path("label").isString()) {
            labels.put(column.path("name").asString(""), column.get("label").stringValue());
        }
    }

    /**
     * Reads one outcome of a simulation. The matched rules are read from {@code table}, so a rule of the baseline is
     * read in the baseline's version; the sample's inputs play no part.
     *
     * @param outcome the outcome
     * @param table the version the outcome came from
     * @return the reading of the outcome in every language
     */
    OutcomeReading outcome(DecisionResult.Outcome outcome, Table table) {
        Map<String, Reading> readings = new LinkedHashMap<>();
        return switch (outcome) {
            case DecisionResult.Matched matched -> {
                for (String language : languages()) {
                    readings.put(language, matched(matched, table, language));
                }
                yield new OutcomeReading("MATCHED", matched.ruleIds(), readings);
            }
            case DecisionResult.NoMatch ignored -> {
                for (String language : languages()) {
                    CatalogueExplainer.Words words = catalogue.words(language, table.wording);
                    readings.put(language, new Reading(
                            words.format("no-match", "ruleSet", table.ruleSet, "version", Integer.toString(table.version)),
                            words.get("short.no-match"), GENERATED));
                }
                yield new OutcomeReading("NO_MATCH", List.of(), readings);
            }
            case DecisionResult.Failed failed -> {
                for (String language : languages()) {
                    CatalogueExplainer.Words words = catalogue.words(language, table.wording);
                    String version = Integer.toString(table.version);
                    String full = failed.matchedRuleIds().isEmpty()
                            ? words.format("error", "ruleSet", table.ruleSet, "version", version, "errorCode",
                                    failed.code())
                            : words.format("error.rows", "ruleSet", table.ruleSet, "version", version, "errorCode",
                                    failed.code(), "ruleIds", words.list(failed.matchedRuleIds()));
                    readings.put(language, new Reading(full, words.format("short.error", "errorCode", failed.code()),
                            GENERATED));
                }
                yield new OutcomeReading("FAILED", failed.matchedRuleIds(), readings);
            }
        };
    }

    private Reading matched(DecisionResult.Matched matched, Table table, String language) {
        CatalogueExplainer.Words words = catalogue.words(language, table.wording);
        List<String> sentences = new ArrayList<>();
        for (String id : matched.ruleIds()) {
            sentences.add(table.byId.containsKey(id) ? table.conditionSentence(table.byId.get(id), words)
                    : words.format("reading.always", "ruleId", id));
        }
        List<String> outputs = new ArrayList<>();
        matched.outputs().forEach((name, value) -> outputs.add(words.format("reading.output", "name",
                words.outputName(name), "value", CatalogueExplainer.readableValue(EngineValues.value(value), words))));
        String joined = String.join(words.get("join.outputs"), outputs);
        List<String> shortOutputs = new ArrayList<>();
        matched.outputs().forEach((name, value) -> {
            JsonNode node = EngineValues.value(value);
            shortOutputs.add(words.format(node != null && node.isBoolean() ? "short.output.flag" : "short.output",
                    "name", words.outputName(name), "value", CatalogueExplainer.readableValue(node, words)));
        });
        String full = String.join(" ", sentences)
                + (joined.isEmpty() ? "" : " " + words.format("result", "outputs", joined));
        String shortForm = shortOutputs.isEmpty()
                ? words.format("short.matched.none", "ruleIds", words.list(matched.ruleIds()))
                : words.format("short.matched", "ruleIds", words.list(matched.ruleIds()), "outputs",
                        String.join(words.get("join.outputs"), shortOutputs));
        return new Reading(full, shortForm, GENERATED);
    }

    /** One version's table, prepared for reading. */
    final class Table {

        private final String ruleSet;
        private final int version;
        private final List<Rule> rules;
        private final Map<String, Rule> byId = new LinkedHashMap<>();
        private final Map<String, Integer> positions = new LinkedHashMap<>();
        private final List<String> columns;
        private final Set<String> masked;
        private final Wording wording;

        private Table(String ruleSet, int version, List<Rule> rules, List<String> columns, Set<String> masked,
                Wording wording) {
            this.ruleSet = ruleSet;
            this.version = version;
            this.rules = rules;
            this.columns = columns;
            this.masked = masked;
            this.wording = wording;
            for (int i = 0; i < rules.size(); i++) {
                byId.put(rules.get(i).id(), rules.get(i));
                positions.put(rules.get(i).id(), i + 1);
            }
        }

        /**
         * The number of rows.
         *
         * @return the rows
         */
        int size() {
            return rules.size();
        }

        /**
         * Whether the table has no more rows than one readings request covers.
         *
         * @return true when it can be read
         */
        boolean readable() {
            return rules.size() <= EngineDefaults.READINGS_MAX;
        }

        /**
         * Refuses a table with more rows than one request reads.
         *
         * @throws ReadingsTooLargeException when the table is larger than {@link EngineDefaults#READINGS_MAX}
         */
        void requireReadable() {
            if (!readable()) {
                throw new ReadingsTooLargeException("rows", rules.size(), EngineDefaults.READINGS_MAX);
            }
        }

        /**
         * Reads every row, in table order.
         *
         * @return one entry per row
         */
        List<Row> rows() {
            return rules.stream().map(this::row).toList();
        }

        /**
         * Reads one row by rule id.
         *
         * @param ruleId the rule id
         * @return the row, or empty when this version has no such rule
         */
        Optional<Row> row(String ruleId) {
            return Optional.ofNullable(byId.get(ruleId)).map(this::row);
        }

        private Row row(Rule rule) {
            Map<String, Reading> readings = new LinkedHashMap<>();
            for (String language : languages()) {
                readings.put(language, reading(rule, language, catalogue.words(language, wording)));
            }
            return new Row(rule.id(), positions.get(rule.id()), readings);
        }

        private Reading reading(Rule rule, String language, CatalogueExplainer.Words words) {
            Map<String, Then> thens = thenValues(rule, words);
            Map<String, String> outputs = new LinkedHashMap<>();
            thens.forEach((name, then) -> outputs.put(name, then.text()));
            String conditionsFull = conditionSentence(rule, words);
            String conditionsShort = capitalise(shortConditions(rule, words));
            String then = outputs.isEmpty() ? "" : String.join(words.get("join.outputs"),
                    outputs.entrySet().stream().map(e -> words.format("reading.output", "name",
                            words.outputName(e.getKey()), "value", e.getValue())).toList());
            String thenShort = outputs.isEmpty() ? "" : String.join(words.get("join.outputs"),
                    thens.entrySet().stream().map(e -> words.format(e.getValue().flag() ? "short.output.flag"
                            : "short.output", "name", words.outputName(e.getKey()), "value", e.getValue().text()))
                            .toList());
            String authored = authored(rule, outputs, language, words);
            String full = authored != null ? authored : conditionsFull
                    + (then.isEmpty() ? "" : " " + words.format("reading.then", "outputs", then));
            String shortForm = conditionsShort + (thenShort.isEmpty() ? "" : words.format("short.then",
                    "outputs", thenShort));
            return new Reading(full, shortForm, authored != null ? TEMPLATE : GENERATED);
        }

        /** The sentence about the row's conditions only, with no result. */
        String conditionSentence(Rule rule, CatalogueExplainer.Words words) {
            List<String> parts = parts(rule.when(), words, "reading.", "reading.masked");
            return parts.isEmpty() ? words.format("reading.always", "ruleId", rule.id())
                    : words.format("reading.rule", "ruleId", rule.id(), "conditions",
                            String.join(words.get("join.and"), parts));
        }

        private String shortConditions(Rule rule, CatalogueExplainer.Words words) {
            List<String> parts = parts(rule.when(), words, "short.", "short.masked");
            return parts.isEmpty() ? words.get("short.always") : String.join(words.get("join.list"), parts);
        }

        private List<String> parts(When when, CatalogueExplainer.Words words, String prefix, String maskedKey) {
            List<String> parts = new ArrayList<>();
            when.cells().entrySet().stream()
                    .sorted(Comparator.comparingInt((Map.Entry<String, String> cell) -> {
                        int column = columns.indexOf(cell.getKey());
                        return column < 0 ? Integer.MAX_VALUE : column;
                    }).thenComparing(Map.Entry::getKey))
                    .forEach(cell -> {
                        String name = words.inputName(cell.getKey());
                        parts.add(masked.contains(cell.getKey()) ? words.format(maskedKey, "input", name)
                                : CatalogueExplainer.test(cell.getValue(), words, prefix, name));
                    });
            when.all().forEach(w -> parts.addAll(parts(w, words, prefix, maskedKey)));
            if (!when.any().isEmpty()) {
                parts.add(words.format(prefix + "any", "alternatives", String.join(words.get("join.alternatives"),
                        when.any().stream().map(w -> String.join(words.get("join.and"),
                                parts(w, words, prefix, maskedKey))).toList())));
            }
            return parts;
        }

        /** The values of the row's literal outputs, written for the language; an expression is left out. */
        private Map<String, Then> thenValues(Rule rule, CatalogueExplainer.Words words) {
            Map<String, Then> values = new LinkedHashMap<>();
            rule.then().forEach((name, cell) -> {
                boolean yesNo = cell instanceof Cell.Literal literal && literal.value() instanceof Boolean
                        || cell instanceof Cell.Expression expression && ("true".equals(expression.source())
                        || "false".equals(expression.source()));
                String value = switch (cell) {
                    case Cell.Literal literal -> literal.value() == null ? words.get("null")
                            : literal.value() instanceof Boolean flag ? words.get("bool." + flag)
                            : words.number(((BigDecimal) literal.value()).toPlainString());
                    case Cell.Expression expression -> expression.source() != null
                            && CatalogueExplainer.WHOLE_LITERAL.matcher(expression.source()).matches()
                            ? CatalogueExplainer.literal(expression.source(), words, true) : null;
                };
                if (value != null) {
                    values.put(name, new Then(value, yesNo));
                }
            });
            return values;
        }

        /** The author's sentence for the language, or null when there is none or a placeholder cannot be filled. */
        private String authored(Rule rule, Map<String, String> outputs, String language, CatalogueExplainer.Words words) {
            String text = wording.rowTemplates().getOrDefault(rule.id(), Map.of()).get(language);
            if (text == null) {
                return null;
            }
            Matcher m = ExplanationTemplates.PLACEHOLDER.matcher(text);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                String token = m.group(1);
                String value = switch (token) {
                    case "ruleId" -> rule.id();
                    case "ruleSet" -> ruleSet;
                    case "version" -> Integer.toString(version);
                    default -> token.startsWith("output.") ? outputs.get(token.substring("output.".length())) : null;
                };
                if (value == null) {
                    return null; // an input value is not known here, and a masked one is never written
                }
                m.appendReplacement(out, Matcher.quoteReplacement(value));
            }
            m.appendTail(out);
            return out.toString();
        }
    }

    /** The words of one literal output of a row, and whether it is a yes or no value. */
    private record Then(String text, boolean flag) { }

    private static String capitalise(String text) {
        return text.isEmpty() ? text : text.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + text.substring(1);
    }
}
