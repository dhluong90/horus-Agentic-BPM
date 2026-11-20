package io.github.dhluong90.bpm.rules.api;

/**
 * A rule set's history across its versions and the evidence export of versions and decisions (PD-116). The tenant
 * comes from the host's {@code TenantResolver} only. Reading needs {@code rules:read}; an export that names decisions
 * also needs {@code rules:log:read}.
 */
public interface RuleSetHistoryService {

    /**
     * Lists the versions of a rule set, newest first, each with its lifecycle events oldest first. The first page also
     * carries the rule set's own events, newest first.
     *
     * @param ruleSet the rule set name
     * @param page the limit and the cursor of the previous page
     * @return one page
     * @throws RuleSetNotFoundException when the caller's tenant has no such rule set
     * @throws QueryInvalidException for a limit out of range or a cursor this list did not issue
     */
    RuleSetHistory history(String ruleSet, PageRequest page);

    /**
     * Writes an evidence file of chosen versions and decisions, records the export in the rule set's history and writes
     * the {@code rules.evidence.exported.v1} event, all in one transaction. With an idempotency key a repeat returns
     * the same file and writes nothing.
     *
     * @param ruleSet the rule set name
     * @param request the format, versions, decisions, idempotency key and correlation id
     * @return the file
     * @throws PermissionDeniedException without {@code rules:read}, or without {@code rules:log:read} when decisions
     *     are named
     * @throws InputInvalidException for no version and no decision, too many, repeats, or a decision of another rule
     *     set
     * @throws VersionNotFoundException for a version the rule set does not have
     * @throws DecisionNotFoundException for a decision the caller's tenant does not have
     * @throws EvidenceTooLargeException when the file would be larger than the export limit
     */
    EvidenceExport exportEvidence(String ruleSet, EvidenceRequest request);
}
