package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.api.EvidenceFormat;
import io.github.dhluong90.bpm.rules.api.LoggedDecision;
import io.github.dhluong90.bpm.rules.api.VersionHistoryEntry;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import io.github.dhluong90.bpm.rules.internal.json.EngineValues;
import io.github.dhluong90.bpm.rules.internal.json.Hashes;
import io.github.dhluong90.bpm.rules.internal.store.HistoryVersion;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes evidence files (PD-116). A file states who exported it, when, for which tenant and the SHA-256 of its content,
 * and the content depends only on the versions and decisions chosen, so two exports of the same records have the same
 * content hash and an auditor can check a file by hashing its content:
 * <ul>
 *   <li>CSV: header lines starting with {@code #}, then the content: a column line and one line per record, each line
 *       ending in CRLF (RFC 4180). The hash covers the content bytes in UTF-8, from the column line to the end.</li>
 *   <li>JSON: {@code {"evidence":{...},"content":{...}}}, compact, with {@code content} last. The hash covers the
 *       bytes of the {@code content} value exactly as they appear in the file.</li>
 * </ul>
 * Stateless.
 */
final class EvidenceFiles {

    /** The columns of a CSV evidence file, in order. */
    static final List<String> CSV_COLUMNS = List.of("record", "version", "occurred_at", "action", "from_status",
            "to_status", "actor", "reason", "version_canonical_hash", "decision_id", "outcome", "error_code",
            "matched_rule_ids", "inputs", "outputs", "correlation_id", "eval_clock");

    static final String RECORD_VERSION_EVENT = "version event";
    static final String RECORD_DECISION = "decision";
    private static final String CRLF = "\r\n";

    private EvidenceFiles() {
    }

    /**
     * Who, when and for whom a file was exported.
     *
     * @param ruleSet the rule set name
     * @param tenantId the tenant
     * @param exportedBy the caller
     * @param exportedAt the engine clock
     */
    record Header(String ruleSet, String tenantId, String exportedBy, Instant exportedAt) {
    }

    /**
     * A written file.
     *
     * @param content the whole file
     * @param contentSha256 the SHA-256 of its content
     */
    record File(String content, String contentSha256) {
    }

    /**
     * Writes a file.
     *
     * @param format the format
     * @param header who, when and for whom
     * @param versions the versions, ascending, each with its events oldest first
     * @param decisions the decisions in the order requested
     * @return the file
     */
    static File write(EvidenceFormat format, Header header, Map<HistoryVersion, List<VersionHistoryEntry>> versions,
            List<LoggedDecision> decisions) {
        return switch (format) {
            case CSV -> csv(header, versions, decisions);
            case JSON -> json(header, versions, decisions);
        };
    }

    private static File csv(Header header, Map<HistoryVersion, List<VersionHistoryEntry>> versions,
            List<LoggedDecision> decisions) {
        StringBuilder content = new StringBuilder();
        line(content, CSV_COLUMNS);
        versions.forEach((version, events) -> {
            for (VersionHistoryEntry e : events) {
                line(content, List.of(RECORD_VERSION_EVENT, Integer.toString(version.version()),
                        e.occurredAt().toString(), e.action().name(),
                        e.fromStatus() == null ? "" : e.fromStatus().name(), e.toStatus().name(), e.actor(),
                        text(e.reason()), version.canonicalHash(), "", "", "", "", "", "", "", ""));
            }
        });
        for (LoggedDecision d : decisions) {
            line(content, List.of(RECORD_DECISION, Integer.toString(d.version()), d.evaluatedAt().toString(), "", "",
                    "", text(d.callerId()), "", "", d.decisionId(), d.outcome(), text(d.errorCode()),
                    EngineValues.write(EngineValues.value(d.matchedRuleIds())),
                    EngineValues.write(EngineValues.read(d.inputsJson())),
                    d.outputsJson() == null ? "" : EngineValues.write(EngineValues.read(d.outputsJson())),
                    text(d.correlationId()), d.evalClock().toString()));
        }
        String body = content.toString();
        String sha256 = Hashes.sha256(body);
        StringBuilder file = new StringBuilder();
        file.append("# HORUS Rule Engine evidence export").append(CRLF);
        file.append("# Rule set: ").append(plain(header.ruleSet())).append(CRLF);
        file.append("# Tenant: ").append(plain(header.tenantId())).append(CRLF);
        file.append("# Exported by: ").append(plain(header.exportedBy())).append(CRLF);
        file.append("# Exported at: ").append(header.exportedAt()).append(CRLF);
        file.append("# Content SHA-256: ").append(sha256).append(CRLF);
        file.append(body);
        return new File(file.toString(), sha256);
    }

    private static File json(Header header, Map<HistoryVersion, List<VersionHistoryEntry>> versions,
            List<LoggedDecision> decisions) {
        ObjectNode content = EngineJson.NODES.objectNode();
        content.put("ruleSet", header.ruleSet());
        ArrayNode versionNodes = content.putArray("versions");
        versions.forEach((version, events) -> {
            ObjectNode node = versionNodes.addObject();
            node.put("version", version.version());
            node.put("status", version.status().name());
            node.put("canonicalHash", version.canonicalHash());
            ArrayNode eventNodes = node.putArray("events");
            events.forEach(e -> eventNodes.add(EngineValues.historyEntry(e)));
        });
        ArrayNode decisionNodes = content.putArray("decisions");
        decisions.forEach(d -> decisionNodes.add(EngineValues.loggedDecision(d)));
        String contentText = EngineValues.write(content);
        String sha256 = Hashes.sha256(contentText);
        ObjectNode evidence = EngineJson.NODES.objectNode()
                .put("ruleSet", header.ruleSet())
                .put("tenantId", header.tenantId())
                .put("exportedBy", header.exportedBy())
                .put("exportedAt", header.exportedAt().toString())
                .put("contentSha256", sha256);
        // Written by hand around the content so its bytes in the file are exactly the bytes hashed.
        String file = "{\"evidence\":" + EngineValues.write(evidence) + ",\"content\":" + contentText + "}";
        return new File(file, sha256);
    }

    private static void line(StringBuilder out, List<String> cells) {
        List<String> written = new ArrayList<>(cells.size());
        cells.forEach(cell -> written.add(cell(cell)));
        out.append(String.join(",", written)).append(CRLF);
    }

    /**
     * One CSV cell: a cell a spreadsheet would run as a formula is prefixed with {@code '} (OWASP CSV injection), and a
     * cell holding a comma, a quote or a line break is quoted with its quotes doubled (RFC 4180).
     */
    static String cell(String value) {
        String v = value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    /** A header value on one line: control characters become spaces, so a header line can never be split. */
    private static String plain(String value) {
        StringBuilder out = new StringBuilder(value.length());
        value.codePoints().forEach(c -> out.appendCodePoint(Character.isISOControl(c) ? ' ' : c));
        return out.toString();
    }
}
