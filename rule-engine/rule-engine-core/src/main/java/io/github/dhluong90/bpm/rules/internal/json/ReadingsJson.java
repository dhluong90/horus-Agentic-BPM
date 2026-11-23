package io.github.dhluong90.bpm.rules.internal.json;

import io.github.dhluong90.bpm.rules.api.RuleReadings.OutcomeReading;
import io.github.dhluong90.bpm.rules.api.RuleReadings.Reading;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The JSON of one read outcome, shared by the REST body and the rows a simulation report stores, so the two never
 * differ (RLE-200). Member order is fixed and languages are sorted, so the same outcome has one byte form.
 */
public final class ReadingsJson {

    private ReadingsJson() {
    }

    /**
     * Writes one outcome.
     *
     * @param outcome the outcome, or null
     * @return an object with {@code kind}, {@code ruleIds} and {@code readings}, or the JSON null
     */
    public static JsonNode outcome(OutcomeReading outcome) {
        if (outcome == null) {
            return EngineJson.NODES.nullNode();
        }
        ObjectNode node = EngineJson.NODES.objectNode();
        node.put("kind", outcome.kind());
        ArrayNode ids = node.putArray("ruleIds");
        outcome.ruleIds().forEach(ids::add);
        ObjectNode readings = node.putObject("readings");
        new TreeMap<>(outcome.readings()).forEach((language, reading) -> readings.set(language,
                EngineJson.NODES.objectNode().put("full", reading.full()).put("short", reading.shortForm())
                        .put("source", reading.source())));
        return node;
    }

    /**
     * Reads an outcome written by {@link #outcome(OutcomeReading)}.
     *
     * @param node the JSON, or null or the JSON null
     * @return the outcome, or null
     */
    public static OutcomeReading readOutcome(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }
        List<String> ids = new ArrayList<>();
        node.path("ruleIds").forEach(id -> ids.add(id.asString()));
        Map<String, Reading> readings = new LinkedHashMap<>();
        node.path("readings").properties().forEach(e -> readings.put(e.getKey(), new Reading(
                e.getValue().path("full").asString(), e.getValue().path("short").asString(),
                e.getValue().path("source").asString("GENERATED"))));
        return new OutcomeReading(node.path("kind").asString(), ids, readings);
    }
}
