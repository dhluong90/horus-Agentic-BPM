package io.github.dhluong90.bpm.rules.internal.store;

import io.github.dhluong90.bpm.rules.api.RuleSetHistory;
import java.time.Instant;

/**
 * A row of {@code rle_version_history} that belongs to the rule set rather than to one version: no
 * version id, statuses or lock version, and the evidence of an export.
 *
 * @param tenantId the tenant
 * @param id the row id (ULID)
 * @param ruleSetId the rule set id
 * @param action the action
 * @param actor who did it
 * @param occurredAt when
 * @param evidenceJson what was exported, a JSON object {@code { format, versions, decisionIds, contentSha256, bytes }}
 */
public record RuleSetEventRow(String tenantId, String id, String ruleSetId, RuleSetHistory.Action action, String actor,
        Instant occurredAt, String evidenceJson) {
}
