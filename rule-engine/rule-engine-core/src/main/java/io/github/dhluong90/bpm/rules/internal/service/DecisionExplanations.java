package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.api.DecisionExplanation;
import io.github.dhluong90.bpm.rules.api.DecisionExplanation.Condition;
import io.github.dhluong90.bpm.rules.api.DecisionExplanation.MatchedRow;
import io.github.dhluong90.bpm.rules.api.DecisionExplanation.Reason;
import io.github.dhluong90.bpm.rules.api.MaskingKey;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import io.github.dhluong90.bpm.rules.internal.json.EngineValues;
import io.github.dhluong90.bpm.rules.internal.model.DecisionTable;
import io.github.dhluong90.bpm.rules.internal.model.HitPolicy;
import io.github.dhluong90.bpm.rules.internal.model.InputClause;
import io.github.dhluong90.bpm.rules.internal.model.OutputClause;
import io.github.dhluong90.bpm.rules.internal.model.Rule;
import io.github.dhluong90.bpm.rules.internal.model.When;
import io.github.dhluong90.bpm.rules.internal.read.RuleSetReader;
import io.github.dhluong90.bpm.rules.internal.store.DecisionRecord;
import io.github.dhluong90.bpm.rules.internal.store.StoredVersion;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the explanation of a logged decision from its row and the stored version it names (RLE-030, RLE-031, DESIGN
 * section 4.7). Nothing is evaluated: every plain condition of a matched row held by definition, so the explanation
 * pairs each cell's test with the value the log recorded. A condition inside an {@code any} group is listed with no
 * result, because the log does not say which alternative held and finding out would mean evaluating again, which is
 * impossible for masked inputs. Rows that did not match are not explained. Stateless.
 */
final class DecisionExplanations {

    private static final String OK = "OK";

    private DecisionExplanations() {
    }

    /**
     * Explains one decision.
     *
     * @param row the decision row
     * @param version the stored version the row names
     * @return the explanation, without text
     */
    static DecisionExplanation explain(DecisionRecord row, StoredVersion version) {
        DecisionTable table = RuleSetReader.readCanonical(version.canonicalJson()).decision().table();
        JsonNode inputs = EngineValues.read(row.maskedInputsJson());
        JsonNode outputs = row.outputsJson() == null ? null : EngineValues.read(row.outputsJson());
        List<String> matchedIds = new ArrayList<>();
        EngineValues.read(row.matchedRuleIdsJson()).forEach(id -> matchedIds.add(id.stringValue()));
        Map<String, InputClause> declared = new LinkedHashMap<>();
        table.inputs().forEach(input -> declared.put(input.name(), input));

        List<MatchedRow> matched = new ArrayList<>();
        List<Reason> valueReasons = new ArrayList<>();
        for (int index = 0; index < matchedIds.size(); index++) {
            String ruleId = matchedIds.get(index);
            int position = position(table, ruleId);
            if (position < 0) {
                continue; // a stored version is immutable, so this cannot happen; never invent a row
            }
            Rule rule = table.rules().get(position - 1);
            List<Condition> conditions = new ArrayList<>();
            conditions(rule.when(), "/when", false, inputs, declared, conditions);
            for (Condition condition : conditions) {
                if (condition.masked()) {
                    valueReasons.add(reason(DecisionExplanation.VALUE_MASKED, "ruleId", ruleId, "input",
                            condition.input()));
                } else if (condition.missing()) {
                    valueReasons.add(reason(DecisionExplanation.VALUE_MISSING, "ruleId", ruleId, "input",
                            condition.input()));
                }
            }
            matched.add(new MatchedRow(ruleId, position, conditions,
                    EngineValues.write(rowOutputs(row, table, outputs, index))));
        }

        List<Reason> reasons = new ArrayList<>();
        switch (row.outcome()) {
            case OK -> {
                if (matched.isEmpty()) {
                    reasons.add(reason(DecisionExplanation.NO_RULE_MATCHED, "ruleSet", row.ruleSet(), "version",
                            row.version()));
                }
                for (MatchedRow m : matched) {
                    reasons.add(reason(DecisionExplanation.RULE_MATCHED, "ruleId", m.ruleId(), "version",
                            row.version()));
                    valueReasons.stream().filter(r -> m.ruleId().equals(r.params().get("ruleId"))).forEach(reasons::add);
                }
            }
            case DecisionExplanation.UNIQUE_HIT_VIOLATION -> {
                reasons.add(reason(DecisionExplanation.UNIQUE_HIT_VIOLATION, "ruleIds", List.copyOf(matchedIds),
                        "version", row.version()));
                reasons.addAll(valueReasons);
            }
            default -> {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("errorCode", row.errorCode() == null ? row.outcome() : row.errorCode());
                params.put("ruleIds", List.copyOf(matchedIds));
                params.put("version", row.version());
                reasons.add(new Reason(DecisionExplanation.EVALUATION_ERROR, params));
                reasons.addAll(valueReasons);
            }
        }
        return new DecisionExplanation(row.id(), row.ruleSet(), row.version(), row.hitPolicy(), row.outcome(),
                row.errorCode(), row.evalClock(), matched, row.outputsJson(), reasons, null, null);
    }

    private static int position(DecisionTable table, String ruleId) {
        for (int i = 0; i < table.rules().size(); i++) {
            if (table.rules().get(i).id().equals(ruleId)) {
                return i + 1;
            }
        }
        return -1;
    }

    /**
     * Lists the cells of a condition: the plain cells in column order, then each {@code all} group, then each
     * {@code any} group. A cell under an {@code any} group, at any depth, has no result.
     */
    private static void conditions(When when, String path, boolean underAny, JsonNode inputs,
            Map<String, InputClause> declared, List<Condition> out) {
        // In column order, the order of the declared inputs: the stored canonical document sorts the cells by name.
        List<String> columns = new ArrayList<>(declared.keySet());
        when.cells().entrySet().stream()
                .sorted(java.util.Comparator.comparingInt((Map.Entry<String, String> cell) -> {
                    int column = columns.indexOf(cell.getKey());
                    return column < 0 ? Integer.MAX_VALUE : column;
                }).thenComparing(Map.Entry::getKey))
                .forEach(cell -> out.add(condition(cell.getKey(), path + "/" + escape(cell.getKey()), cell.getValue(),
                        underAny, inputs, declared.get(cell.getKey()))));
        for (int i = 0; i < when.all().size(); i++) {
            conditions(when.all().get(i), path + "/all/" + i, underAny, inputs, declared, out);
        }
        for (int i = 0; i < when.any().size(); i++) {
            conditions(when.any().get(i), path + "/any/" + i, true, inputs, declared, out);
        }
    }

    private static Condition condition(String input, String path, String test, boolean underAny, JsonNode inputs,
            InputClause clause) {
        JsonNode value = inputs.get(input);
        boolean missing = value == null || value.isNull();
        // A fingerprint is masked unless the version declares the input mask: false, so a plain text that merely looks
        // like one is never reported as masked.
        boolean masked = !missing && value.isString() && MaskingKey.isFingerprint(value.stringValue())
                && (clause == null || !Boolean.FALSE.equals(clause.mask()));
        String valueJson = missing ? "null" : EngineValues.write(value);
        return new Condition(input, path, test, valueJson, masked, missing, underAny ? null : Boolean.TRUE);
    }

    /** JSON Pointer escaping (RFC 6901) of one path segment. */
    private static String escape(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }

    /**
     * The outputs one matched row gave: under {@code first} and {@code unique} the decision's outputs; under
     * {@code collect} the row's entry of each list output, aligned with the matched rows, while aggregated outputs
     * belong to the decision only. Empty for a failed decision, which has no outputs.
     */
    private static ObjectNode rowOutputs(DecisionRecord row, DecisionTable table, JsonNode outputs, int index) {
        ObjectNode node = EngineJson.NODES.objectNode();
        if (!OK.equals(row.outcome()) || outputs == null || !outputs.isObject()) {
            return node;
        }
        if (table.hitPolicy() != HitPolicy.COLLECT) {
            outputs.properties().forEach(entry -> node.set(entry.getKey(), entry.getValue()));
            return node;
        }
        for (OutputClause output : table.outputs()) {
            JsonNode value = outputs.get(output.name());
            if (output.aggregate() == OutputClause.Aggregate.NONE && value != null && value.isArray()
                    && index < value.size()) {
                node.set(output.name(), value.get(index));
            }
        }
        return node;
    }

    private static Reason reason(String code, String k1, Object v1, String k2, Object v2) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(k1, v1);
        params.put(k2, v2);
        return new Reason(code, params);
    }
}
