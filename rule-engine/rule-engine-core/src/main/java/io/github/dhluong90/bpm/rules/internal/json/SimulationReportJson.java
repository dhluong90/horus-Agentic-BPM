package io.github.dhluong90.bpm.rules.internal.json;

import io.github.dhluong90.bpm.rules.api.SimulationReport;
import io.github.dhluong90.bpm.rules.api.SimulationReportRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The JSON of a simulation report, for the REST body, for the rows the store keeps and for the idempotency record, so
 * the three never differ (RLE-199, RLE-200). Member order is fixed. It holds no input value.
 */
public final class SimulationReportJson {

    private SimulationReportJson() {
    }

    /**
     * Writes a report.
     *
     * @param report the report
     * @return the object
     */
    public static ObjectNode write(SimulationReport report) {
        ObjectNode node = EngineJson.NODES.objectNode();
        node.put("id", report.id());
        node.put("ruleSet", report.ruleSet());
        node.put("version", report.version());
        node.put("canonicalHash", report.canonicalHash());
        node.put("status", report.status().name());
        node.put("attached", report.attached());
        node.put("createdBy", report.createdBy());
        node.put("createdAt", report.createdAt().toString());
        ObjectNode scope = node.putObject("scope");
        scope.put("source", report.scope().source().name());
        scope.set("from", EngineValues.value(report.scope().from()));
        scope.set("to", EngineValues.value(report.scope().to()));
        scope.set("limit", EngineValues.value(report.scope().limit()));
        scope.put("truncated", report.scope().truncated());
        ObjectNode counts = node.putObject("counts");
        counts.put("total", report.counts().total());
        counts.put("changed", report.counts().changed());
        counts.put("unchanged", report.counts().unchanged());
        counts.put("failed", report.counts().failed());
        counts.put("notReplayable", report.counts().notReplayable());
        ArrayNode languages = node.putArray("languages");
        report.languages().forEach(languages::add);
        node.set("rows", writeRows(report.rows()));
        node.put("rowsTruncated", report.rowsTruncated());
        return node;
    }

    /**
     * Writes the rows of a report.
     *
     * @param rows the rows
     * @return a JSON array
     */
    public static ArrayNode writeRows(List<SimulationReport.Row> rows) {
        ArrayNode array = EngineJson.NODES.arrayNode();
        for (SimulationReport.Row row : rows) {
            ObjectNode item = array.addObject();
            item.set("reference", EngineValues.value(row.reference()));
            item.set("decisionId", EngineValues.value(row.decisionId()));
            item.put("category", row.category());
            item.set("before", ReadingsJson.outcome(row.before()));
            item.set("after", ReadingsJson.outcome(row.after()));
        }
        return array;
    }

    /**
     * Reads the rows written by {@link #writeRows}.
     *
     * @param json the JSON array text
     * @return the rows
     */
    public static List<SimulationReport.Row> readRows(String json) {
        return readRows(EngineValues.read(json));
    }

    private static List<SimulationReport.Row> readRows(JsonNode array) {
        List<SimulationReport.Row> rows = new ArrayList<>();
        array.forEach(item -> rows.add(new SimulationReport.Row(text(item, "reference"), text(item, "decisionId"),
                item.path("category").asString(), ReadingsJson.readOutcome(item.get("before")),
                ReadingsJson.readOutcome(item.get("after")))));
        return rows;
    }

    /**
     * Reads a report written by {@link #write}, as the idempotency record keeps it.
     *
     * @param json the JSON text
     * @return the report
     */
    public static SimulationReport read(String json) {
        JsonNode node = EngineValues.read(json);
        JsonNode scope = node.path("scope");
        JsonNode counts = node.path("counts");
        List<String> languages = new ArrayList<>();
        node.path("languages").forEach(l -> languages.add(l.asString()));
        return new SimulationReport(node.path("id").asString(), node.path("ruleSet").asString(),
                node.path("version").asInt(), node.path("canonicalHash").asString(),
                SimulationReport.Status.valueOf(node.path("status").asString()), node.path("attached").asBoolean(),
                node.path("createdBy").asString(), Instant.parse(node.path("createdAt").asString()),
                new SimulationReport.Scope(SimulationReportRequest.Source.valueOf(scope.path("source").asString()),
                        instant(scope, "from"), instant(scope, "to"),
                        scope.path("limit").isNumber() ? scope.path("limit").asInt() : null,
                        scope.path("truncated").asBoolean()),
                new SimulationReport.Counts(counts.path("total").asInt(), counts.path("changed").asInt(),
                        counts.path("unchanged").asInt(), counts.path("failed").asInt(),
                        counts.path("notReplayable").asInt()),
                languages, readRows(node.path("rows")), node.path("rowsTruncated").asBoolean());
    }

    private static String text(JsonNode node, String member) {
        JsonNode value = node.get(member);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static Instant instant(JsonNode node, String member) {
        String value = text(node, member);
        return value == null ? null : Instant.parse(value);
    }
}
